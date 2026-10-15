package com.pismo.messenger.data

import com.pismo.messenger.core.JwtAuth
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
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
     * Порция дозаписи — величина не постоянная, а подстраивающаяся.
     *
     * Четыре мегабайта хороши на нормальной связи: мало кругов до сервера,
     * запрос на несколько секунд. Но бывает связь, которая рвёт соединение
     * каждые полтора-два мегабайта (общежитие, переполненная вышка), и там
     * крупная порция не доезжает никогда — каждый обрыв стоит всего, что
     * ушло сверх принятого сервером.
     *
     * Поэтому порция ужимается вдвое на каждом обрыве и растёт вдвое на
     * каждой дошедшей: связь сама выбирает размер, при котором её хватает.
     */
    private const val CHUNK_MAX = 4 * 1024 * 1024

    /**
     * Нижняя граница — маленькая намеренно. На связи, которая рвёт
     * соединение после сотни килобайт, порция обязана стать меньше этой
     * сотни, иначе не доедет ни одна и отправка встанет совсем. Лишние
     * круги до сервера тут цена вопроса, а не беда: как только связь
     * перестаёт рваться, порция удваивается обратно до четырёх мегабайт.
     */
    private const val CHUNK_MIN = 64 * 1024

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

            // 1. Создаём загрузку. Если такой файл уже лежит — ответ придёт
            //    сразу с ключом, и по сети не уйдёт ни байта. А если эту же
            //    отправку уже начинали и она оборвалась, сервер вернёт ту же
            //    загрузку и скажет, сколько у него есть: повтор продолжает
            //    с места обрыва, а не гонит мегабайты заново.
            val create = Request.Builder()
                .url("$b/files")
                .post(RequestBody.create(null, ByteArray(0)))
                .header("Authorization", "Bearer ${token()}")
                .header("Upload-Length", total.toString())
                .header("Upload-Sha", sha)
                .header("Upload-Name", nameB64)
                .build()

            var uploadId: String? = null
            var offset = 0
            call(create).use { res ->
                if (!res.isSuccessful) return@withContext null
                res.header("File-Key")?.let { key ->
                    onProgress?.invoke(1f)
                    return@withContext urlOf(key, name)      // уже на сервере
                }
                uploadId = res.header("Upload-Id")
                offset = res.header("Upload-Offset")?.toIntOrNull()?.coerceIn(0, total) ?: 0
            }
            val id = uploadId ?: return@withContext null
            if (offset > 0) onProgress?.invoke(offset.toFloat() / total)

            // Докуда дошли в лучшем случае. Нужно, чтобы различать две
            // разные беды: «сервис не работает» (тогда тихо откатываемся на
            // базу) и «связь оборвалась на середине». Во втором случае
            // уводить файл в базу вредно: там он поедет с нуля и медленно,
            // хотя на сервисе уже лежит половина и повтор её продолжит.
            var best = offset

            // 2. Досылаем остаток ПОРЦИЯМИ.
            //
            // Одним запросом на весь файл это уже не делается. Длинная
            // отправка на мобильной связи почти наверняка где-то оборвётся —
            // оператором, NAT-ом, сменой вышки, — и чем дольше запрос, тем
            // вернее. Пока порция была одна на весь файл, каждый обрыв стоил
            // всего, что успело уйти сверх подтверждённого: отправка
            // возвращалась к последнему подтверждённому байту и шла по
            // кругу, не доезжая до конца.
            //
            // Порция в несколько мегабайт — это запрос на секунды. Обрыв
            // стоит одной порции, а подтверждённое сервером не теряется.
            var attempt = 0
            var chunk = CHUNK_MAX
            while (offset < total) {
                currentCoroutineContext().ensureActive()
                val before = offset
                val till = minOf(offset + chunk, total)
                try {
                    val key = patch(b, id, data, offset, till, total, onProgress)
                    if (key != null) return@withContext urlOf(key, name)
                    offset = till
                    best = maxOf(best, offset)
                    onProgress?.invoke(offset.toFloat() / total)
                    // Порция дошла целиком — можно осмелеть.
                    chunk = minOf(CHUNK_MAX, chunk * 2)
                    attempt = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Broken) {
                    throw e
                } catch (e: Silent) {
                    throw e
                } catch (e: OffsetMismatch) {
                    // Сервер назвал своё смещение. Это сверка, а не сбой:
                    // попытку не тратим, но и крутиться на месте не даём.
                    if (e.offset == offset && ++attempt > MAX_ATTEMPTS) giveUp(best)
                    offset = e.offset.coerceIn(0, total)
                    best = maxOf(best, offset)
                    onProgress?.invoke(offset.toFloat() / total)
                } catch (e: Throwable) {
                    // ОБРЫВ ПОСРЕДИ ПОРЦИИ — ещё не беда.
                    //
                    // Сервер оставляет у себя всё, что успел принять, и
                    // спрашиваем мы именно его. Если принятое выросло —
                    // отправка ИДЁТ, просто связь рваная: такой обрыв не
                    // тратит попытку, иначе на сети, рвущейся каждые два
                    // мегабайта, большой файл сдавался бы на шестом обрыве,
                    // проехав едва десятую часть.
                    val have = resolveOffset(b, id) ?: giveUp(best)
                    if (have > before) {
                        offset = have.coerceIn(0, total)
                        best = maxOf(best, offset)
                        attempt = 0
                    } else {
                        if (++attempt > MAX_ATTEMPTS) giveUp(best)
                        // Стоим на месте — ждём дольше с каждым разом, а не
                        // долбимся в один и тот же обрыв.
                        delay(2000L * attempt)
                        offset = have.coerceIn(0, total)
                    }
                    // На рвущейся связи короткий запрос доезжает вернее.
                    chunk = maxOf(CHUNK_MIN, chunk / 2)
                    onProgress?.invoke(offset.toFloat() / total)
                }
            }

            // Всё ушло, а ключа не было: последняя порция могла дойти уже
            // после обрыва ответа. Спрашиваем сервер — файл у него целый,
            // и он отдаст ключ.
            finishUpload(b, id) ?.let { return@withContext urlOf(it, name) }
            giveUp(best)
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
        call(req).use { res ->
            // 409 — разошлись в том, с какого места продолжать. Сервер
            // присылает своё смещение: с него и пойдём.
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
        patch(base, id, ByteArray(0), have, have, maxOf(have, 1), null)
    }.getOrNull()

    /**
     * Сколько байт у сервера. Спрашиваем с повторами: один неудачный вопрос
     * — не повод бросать отправку, на которую уже ушли минуты. Раньше именно
     * так и было: связь моргнула на этом запросе, и файл уезжал заново через
     * базу, с нуля.
     */
    private suspend fun resolveOffset(base: String, id: String): Int? {
        repeat(3) { i ->
            currentCoroutineContext().ensureActive()
            headOffset(base, id)?.let { return it }
            delay(1500L * (i + 1))
        }
        return headOffset(base, id)
    }

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
    suspend fun download(
        url: String,
        onProgress: ((Float) -> Unit)? = null,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        runCatching {
            val req = Request.Builder().url(resolve(url)).get().build()
            call(req).use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body ?: return@withContext null
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
