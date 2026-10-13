package com.pismo.messenger.data

import com.pismo.messenger.core.JwtAuth
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Файловый сервис: вложение лежит на диске сервера, в сообщении — ссылка.
 *
 * Зачем это вместо базы. Файл дописывался в LONGBLOB порциями через CONCAT,
 * и на каждую порцию сервер переписывал весь накопленный блоб — работа росла
 * квадратом от размера. Прогресс был виден только по границам порций, видео
 * нельзя было перемотать, а телефон возил мегабайты по протоколу MySQL.
 *
 * Протокол тот же, что у tus и у черновика IETF «Resumable Uploads»:
 * POST создаёт загрузку, HEAD говорит, сколько уже принято, PATCH дописывает
 * с этого места. Обрыв стоит килобайтов, а не всей отправки.
 *
 * Сервиса может не быть — тогда всё работает по-старому, через базу. Это не
 * исключительная ситуация: сервис ставится отдельно (ws-server/install-files.sh),
 * и до этого момента клиент обязан оставаться рабочим.
 */
object FileService {

    /** Вложения меньше этого шлём по-старому: ради мелочи ходить некуда. */
    const val MIN_SIZE = 1024 * 1024

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)   // заливка идёт сколько нужно
        .retryOnConnectionFailure(true)
        .build()

    private const val PROBE_TTL_MS = 5 * 60 * 1000L
    @Volatile private var probedAt = 0L
    @Volatile private var alive = false

    fun base(): String = Prefs.filesUrl

    /**
     * Жив ли сервис. Ответ держим пять минут: спрашивать перед каждым файлом
     * незачем, а «его нет» не должно стоить таймаута на каждой отправке.
     */
    suspend fun available(): Boolean = withContext(Dispatchers.IO) {
        val b = base()
        if (b.isEmpty()) return@withContext false
        val now = System.currentTimeMillis()
        if (now - probedAt < PROBE_TTL_MS) return@withContext alive
        probedAt = now
        alive = runCatching {
            val req = Request.Builder().url("$b/health").get().build()
            http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
                .newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        alive
    }

    private fun token(): String =
        JwtAuth.create(UserSession.effectiveId, UserSession.effectiveName.ifBlank { "user" })

    private fun sha256(data: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder(64)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }

    /** Ссылка на уже загруженный файл. */
    private fun urlOf(sha: String, name: String): String =
        base() + "/files/" + sha + "/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    /**
     * Заливает файл. Возвращает ссылку или null, если не вышло (тогда
     * отправка пойдёт по-старому, через базу).
     *
     * [onProgress] зовётся по мере ухода байтов, а не по границам порций:
     * тело пишется в сокет нами, и мы видим каждый килобайт.
     */
    suspend fun upload(
        data: ByteArray,
        name: String,
        onProgress: ((Float) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        val b = base()
        if (b.isEmpty() || data.isEmpty()) return@withContext null

        runCatching {
            val sha = sha256(data)
            val nameB64 = android.util.Base64.encodeToString(
                name.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )

            // 1. Создаём загрузку. Если такой файл уже лежит — ответ придёт
            //    сразу с ключом, и по сети не уйдёт ни байта.
            val create = Request.Builder()
                .url("$b/files")
                .post(RequestBody.create(null, ByteArray(0)))
                .header("Authorization", "Bearer ${token()}")
                .header("Upload-Length", data.size.toString())
                .header("Upload-Sha", sha)
                .header("Upload-Name", nameB64)
                .build()

            var uploadId: String? = null
            http.newCall(create).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                res.header("File-Key")?.let { key ->
                    onProgress?.invoke(1f)
                    return@withContext urlOf(key, name)      // уже на сервере
                }
                uploadId = res.header("Upload-Id")
            }
            val id = uploadId ?: return@withContext null

            // 2. Досылаем остаток. После обрыва спрашиваем, сколько принято,
            //    и продолжаем ровно с этого места.
            var offset = 0
            var attempt = 0
            while (true) {
                val sent = runCatching { patch(b, id, data, offset, onProgress) }
                val key = sent.getOrNull()
                if (key != null) return@withContext urlOf(key, name)
                if (sent.isSuccess) return@withContext null      // сервис отказал
                if (++attempt > 4) return@withContext null
                kotlinx.coroutines.delay(1500L * attempt)
                offset = headOffset(b, id) ?: return@withContext null
            }
            @Suppress("UNREACHABLE_CODE") null
        }.getOrNull()
    }

    /** Одна попытка дозаписи. Возвращает ключ файла, когда дошли до конца. */
    private fun patch(
        base: String, id: String, data: ByteArray, from: Int,
        onProgress: ((Float) -> Unit)?,
    ): String? {
        val total = data.size
        val body = object : RequestBody() {
            override fun contentType() = "application/offset+octet-stream".toMediaType()
            override fun contentLength() = (total - from).toLong()
            override fun writeTo(sink: BufferedSink) {
                var off = from
                val step = 64 * 1024
                while (off < total) {
                    val len = minOf(step, total - off)
                    sink.write(data, off, len)
                    off += len
                    // Прогресс по байтам: полоса трогается через мгновение
                    // после начала, а не после первой многомегабайтной порции.
                    onProgress?.invoke(off.toFloat() / total)
                }
                sink.flush()
            }
        }
        val req = Request.Builder()
            .url("$base/files/$id")
            .patch(body)
            .header("Authorization", "Bearer ${token()}")
            .header("Upload-Offset", from.toString())
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw java.io.IOException("patch ${res.code}")
            return res.header("File-Key")
        }
    }

    private fun headOffset(base: String, id: String): Int? = runCatching {
        val req = Request.Builder()
            .url("$base/files/$id").head()
            .header("Authorization", "Bearer ${token()}")
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) null else res.header("Upload-Offset")?.toIntOrNull()
        }
    }.getOrNull()

    /**
     * Качает файл по ссылке. [url] может быть записан другим клиентом с
     * другим адресом сервера — берём из него только путь, а основу свою:
     * сервер мог переехать, а ссылки в переписке остались прежними.
     */
    suspend fun download(
        url: String,
        onProgress: ((Float) -> Unit)? = null,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        runCatching {
            val req = Request.Builder().url(resolve(url)).get().build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body ?: return@withContext null
                val total = body.contentLength()
                val out = java.io.ByteArrayOutputStream(
                    if (total > 0) total.toInt() else 64 * 1024
                )
                val buf = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        if (total > 0) onProgress?.invoke(out.size().toFloat() / total)
                    }
                }
                out.toByteArray()
            }
        }.getOrNull()
    }

    /** Путь из чужой ссылки на своей основе. */
    fun resolve(url: String): String {
        val b = base()
        if (b.isEmpty()) return url
        val i = url.indexOf("/files/")
        return if (i >= 0) b + url.substring(i) else url
    }
}
