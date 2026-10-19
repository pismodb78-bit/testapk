package com.pismo.messenger.data

import com.pismo.messenger.core.JwtAuth
import com.pismo.messenger.core.NetInfo
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import java.net.URLEncoder
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    /** Сколько раз пробуем дослать файл, прежде чем сдаться. */
    private const val MAX_ATTEMPTS = 6

    /**
     * Сколько частей держим в полёте одновременно.
     *
     * Так работают мессенджеры: пока одна часть летит, следующие уже в пути,
     * и круг до сервера между ними не стоит простоем. На мобильной связи
     * дорожек меньше — узкий канал быстрее захлёбывается, да и каждая
     * дорожка это ещё одна сессия в NAT оператора.
     */
    private const val WIFI_LANES = 4
    private const val CELL_LANES = 2

    /** Границы размера части. */
    private const val PART_MIN = 256 * 1024
    private const val PART_MAX = 8 * 1024 * 1024
    private const val PART_CELL_MAX = 2 * 1024 * 1024

    /** К какой длительности части стремимся: столько летит одна часть. */
    private const val PART_TARGET_MS = 3_000L

    /** Замеренная скорость отправки, байт в секунду. 0 — ещё не знаем. */
    @Volatile private var speedBps: Long = 0

    private fun rememberSpeed(bytes: Int, ms: Long) {
        if (bytes <= 0 || ms <= 0) return
        val now = bytes * 1000L / ms
        // Скользящее среднее: одна быстрая часть не повод поверить в канал.
        speedBps = if (speedBps == 0L) now else (speedBps * 3 + now) / 4
    }

    /**
     * Размер части под текущую скорость.
     *
     * Постоянный размер плох с обеих сторон: на быстром канале мелкие части
     * заставляют ждать круг до сервера чаще, чем нужно, а на медленном
     * крупная часть идёт минутами и теряется целиком при обрыве. Поэтому
     * целимся в несколько секунд на часть — ровно как это делают загрузчики
     * мессенджеров.
     */
    private fun partSize(): Int {
        val cap = if (NetInfo.isCellular()) PART_CELL_MAX else PART_MAX
        if (speedBps <= 0) return (512 * 1024).coerceAtMost(cap)
        val want = (speedBps * PART_TARGET_MS / 1000L)
        return want.coerceIn(PART_MIN.toLong(), cap.toLong()).toInt()
    }

    /** Сервер назвал своё смещение (409) — продолжаем с него. */
    private class OffsetMismatch(val offset: Int) : IOException("offset mismatch")

    /**
     * Запрос, который слушается отмены.
     *
     * `execute()` — вызов блокирующий: отмена корутины его не трогает, поток
     * сидит в нём до конца передачи. Для отправки это выходило боком. Отмена
     * снимала задачу со списка, но корутина оставалась внутри заливки и
     * держала очередь: следующий файл стоял «В очереди» и не двигался, пока
     * отменённый не догрузится до конца — то есть отмена не отменяла ничего,
     * кроме надписи на экране.
     *
     * Здесь запрос ставится в очередь OkHttp, а отмена корутины рвёт его
     * по-настоящему: поток освобождается сразу, очередь идёт дальше.
     */
    private suspend fun call(req: Request, client: OkHttpClient = http): Response =
        suspendCancellableCoroutine { cont ->
            val c = client.newCall(req)
            cont.invokeOnCancellation { runCatching { c.cancel() } }
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else runCatching { response.close() }
                }
            })
        }

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
            call(req, http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build())
                .use { it.isSuccessful }
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
        val total = data.size

        try {
            val sha = sha256(data)
            val nameB64 = android.util.Base64.encodeToString(
                name.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )

            // 1. Создаём загрузку. Такой файл уже лежит — ответ придёт сразу с
            //    ключом, и по сети не уйдёт ни байта. А если эту отправку уже
            //    начинали и она оборвалась, сервер вернёт ту же загрузку и
            //    скажет, сколько у него есть.
            val create = Request.Builder()
                .url("$b/files")
                .post(RequestBody.create(null, ByteArray(0)))
                .header("Authorization", "Bearer ${token()}")
                .header("Upload-Length", total.toString())
                .header("Upload-Sha", sha)
                .header("Upload-Name", nameB64)
                .build()

            var uploadId: String? = null
            var from = 0
            call(create).use { res ->
                if (!res.isSuccessful) return@withContext null
                res.header("File-Key")?.let { key ->
                    onProgress?.invoke(1f)
                    return@withContext urlOf(key, name)      // уже на сервере
                }
                uploadId = res.header("Upload-Id")
                from = res.header("Upload-Offset")?.toIntOrNull()?.coerceIn(0, total) ?: 0
            }
            val id = uploadId ?: return@withContext null

            // 2. Остаток шлём ЧАСТЯМИ И РАЗОМ.
            //
            // Раньше части шли строго друг за другом, и после каждой мы ждали
            // ответа сервера. На мобильной связи этот круг — сотни
            // миллисекунд, и на файле в полсотни частей он складывается в
            // минуты простоя при живом канале. Мессенджеры поэтому и держат
            // несколько частей в полёте одновременно; сервер научен принимать
            // их вразнобой и сам следит, что дыр не осталось.
            val sent = java.util.concurrent.atomic.AtomicLong(from.toLong())
            onProgress?.invoke(from.toFloat() / total)

            val lanes = if (NetInfo.isCellular()) CELL_LANES else WIFI_LANES
            val part = partSize()
            val parts = ArrayList<Pair<Int, Int>>()
            var at = from
            while (at < total) {
                val till = minOf(at + part, total)
                parts.add(at to till)
                at = till
            }

            var key: String? = null
            val gate = Semaphore(lanes)
            kotlinx.coroutines.coroutineScope {
                parts.map { (pFrom, pTill) ->
                    async {
                        gate.withPermit {
                            val got = sendPart(b, id, data, pFrom, pTill, total, sent, onProgress)
                            if (got != null) synchronized(this@FileService) { key = got }
                        }
                    }
                }.awaitAll()
            }
            key?.let { return@withContext urlOf(it, name) }

            // Все части ушли, а ключа нет — значит ответ на последнюю
            // потерялся или часть не доехала. Спрашиваем сервер.
            finishUpload(b, id)?.let { return@withContext urlOf(it, name) }
            giveUp(sent.get().toInt())
            @Suppress("UNREACHABLE_CODE") null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Broken) {
            throw e              // сорвалось на полпути — наверх, к «Повторить»
        } catch (e: Throwable) {
            null                 // сервис не отозвался — поедем через базу
        }
    }

    /**
     * Одна часть со всеми повторами. Возвращает ключ, если именно она
     * оказалась последней недостающей.
     */
    private suspend fun sendPart(
        base: String, id: String, data: ByteArray,
        from: Int, till: Int, total: Int,
        sent: java.util.concurrent.atomic.AtomicLong,
        onProgress: ((Float) -> Unit)?,
    ): String? {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val before = sent.get()
            try {
                val started = System.currentTimeMillis()
                val key = patch(base, id, data, from, till, total, sent, onProgress)
                rememberSpeed(till - from, System.currentTimeMillis() - started)
                return key
            } catch (e: CancellationException) {
                throw e
            } catch (e: OffsetMismatch) {
                // Сервер не принял это место — спорить незачем, часть уже
                // где-то лежит. Отдаём ход остальным.
                return null
            } catch (e: Throwable) {
                // Обрыв на полпути: сервер засчитал принятое, и повтор везёт
                // только остаток. Пока что-то доезжает, попытки не тратим.
                sent.set(maxOf(before, sent.get()))
                if (++attempt > MAX_ATTEMPTS) giveUp(sent.get().toInt())
                delay(1000L * attempt)
            }
        }
    }

    /**
     * Отправка сорвалась. Если сервис уже принял часть файла, уводить его в
     * базу нельзя: там он пойдёт с нуля, медленно и, скорее всего, тем же
     * обрывом и кончится — а на сервисе половина уже лежит, и повтор
     * продолжит с места обрыва. Поэтому наверх уходит ошибка с «Повторить»,
     * а не тихий откат.
     */
    private fun giveUp(sent: Int): Nothing =
        if (sent > 0) throw Broken()
        else throw Silent()

    /** Сорвалось после того, как часть файла уже уехала. */
    class Broken : IOException("Связь оборвалась. «Повторить» продолжит с места обрыва.")

    /** Сервис не отозвался — отправка пойдёт через базу, как раньше. */
    private class Silent : IOException("file service unavailable")

    /**
     * Одна порция дозаписи: байты [from, till). Возвращает ключ файла, если
     * этой порцией файл закончился.
     */
    private suspend fun patch(
        base: String, id: String, data: ByteArray, from: Int, till: Int, total: Int,
        sent: java.util.concurrent.atomic.AtomicLong,
        onProgress: ((Float) -> Unit)?,
    ): String? {
        val body = object : RequestBody() {
            override fun contentType() = "application/offset+octet-stream".toMediaType()
            override fun contentLength() = (till - from).toLong()
            override fun writeTo(sink: BufferedSink) {
                var off = from
                val step = 64 * 1024
                while (off < till) {
                    val len = minOf(step, till - off)
                    sink.write(data, off, len)
                    off += len
                    // Прогресс общий на все дорожки: по отдельной части он
                    // ничего не значит, а человек смотрит на один файл.
                    val done = sent.addAndGet(len.toLong())
                    onProgress?.invoke((done.toFloat() / total).coerceIn(0f, 1f))
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
        call(req).use { res ->
            // 409 — сервер не берёт это место. С параллельными частями такое
            // возможно только при расхождении в размере файла; спорить
            // бессмысленно.
            if (res.code == 409) {
                throw OffsetMismatch(res.header("Upload-Offset")?.toIntOrNull() ?: 0)
            }
            if (!res.isSuccessful) throw IOException("patch ${res.code}")
            return res.header("File-Key")
        }
    }

    /**
     * Ключ готового файла, когда последняя порция дошла, а ответ на неё — нет.
     * Пустая дозапись в конец заставляет сервер досчитать отпечаток и отдать
     * ключ; файла при этом уже нет в незавершённых — тогда ответом будет 404,
     * и это честное «не вышло».
     */
    private suspend fun finishUpload(base: String, id: String): String? = runCatching {
        val have = headOffset(base, id) ?: return null
        // Пустая дозапись в конец: если у сервера уже весь файл, он досчитает
        // отпечаток и отдаст ключ; если нет — ответит без ключа.
        patch(base, id, ByteArray(0), have, have, maxOf(have, 1),
              java.util.concurrent.atomic.AtomicLong(0), null)
    }.getOrNull()

    private suspend fun headOffset(base: String, id: String): Int? = runCatching {
        val req = Request.Builder()
            .url("$base/files/$id").head()
            .header("Authorization", "Bearer ${token()}")
            .build()
        call(req).use { res ->
            if (!res.isSuccessful) null else res.header("Upload-Offset")?.toIntOrNull()
        }
    }.getOrNull()

    /**
     * Качает файл по ссылке. [url] может быть записан другим клиентом с
     * другим адресом сервера — берём из него только путь, а основу свою:
     * сервер мог переехать, а ссылки в переписке остались прежними.
     */
    /**
     * Почему не вышло в последний раз. Нужно не для порядка: «не удалось
     * загрузить» без причины не говорит человеку ничего и не даёт понять,
     * чинить ли сеть, адрес сервиса или сам файл.
     */
    @Volatile
    var lastError: String? = null
        private set

    suspend fun download(
        url: String,
        onProgress: ((Float) -> Unit)? = null,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null

        // Сначала по своему адресу сервиса, потом по тому, что в ссылке.
        //
        // Ссылку писал отправитель со своим адресом, и обычно он тот же.
        // Но если у нас сервис настроен иначе (или не настроен вовсе),
        // подменять рабочий адрес на нерабочий — значит не скачать ничего:
        // поэтому вторым заходом пробуем ссылку как есть.
        val mine = resolve(url)
        val tries = if (mine == url) listOf(url) else listOf(mine, url)

        for ((i, target) in tries.withIndex()) {
            repeat(2) { attempt ->                      // одна осечка — не приговор
                currentCoroutineContext().ensureActive()
                val got = fetch(target, onProgress)
                if (got != null) return@withContext got
                if (attempt == 0) delay(800)
            }
            if (i == 0 && tries.size > 1) lastError = null   // пробуем следующий адрес
        }
        null
    }

    /** Одна попытка скачивания. null — не вышло, причина в [lastError]. */
    private suspend fun fetch(target: String, onProgress: ((Float) -> Unit)?): ByteArray? {
        return try {
            val req = Request.Builder().url(target).get().build()
            call(req).use { res ->
                if (!res.isSuccessful) {
                    lastError = when (res.code) {
                        404 -> "файла нет на сервере"
                        else -> "сервис ответил ${res.code}"
                    }
                    return null
                }
                val body = res.body ?: run {
                    lastError = "пустой ответ сервиса"
                    return null
                }
                val total = body.contentLength()
                val out = java.io.ByteArrayOutputStream(
                    if (total > 0) total.toInt() else 64 * 1024
                )
                val buf = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        // Чтение блокирующее, поэтому про отмену спрашиваем
                        // сами: иначе отменённое скачивание держало бы свою
                        // очередь до последнего байта.
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        if (total > 0) onProgress?.invoke(out.size().toFloat() / total)
                    }
                }
                val bytes = out.toByteArray()
                if (bytes.isEmpty()) {
                    lastError = "сервис отдал пусто"
                    return null
                }
                lastError = null
                bytes
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastError = e.message?.takeIf { it.isNotBlank() } ?: "нет связи с сервисом"
            null
        }
    }

    /** Путь из чужой ссылки на своей основе. */
    fun resolve(url: String): String {
        val b = base()
        if (b.isEmpty()) return url
        val i = url.indexOf("/files/")
        return if (i >= 0) b + url.substring(i) else url
    }
}
