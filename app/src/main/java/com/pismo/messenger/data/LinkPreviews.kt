package com.pismo.messenger.data

import android.content.Context
import com.pismo.messenger.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Карточка ссылки: название сайта, заголовок, описание, картинка.
 *
 * ЧЕМ ЭТО ОПЛАЧЕНО. Чтобы собрать карточку, приложение само идёт на сайт по
 * ссылке и читает его страницу. Значит, сайт узнаёт, что сообщение открыли,
 * и видит адрес читателя — ещё до того, как тот нажал на ссылку. В Telegram
 * карточку собирает сервер и раздаёт готовой, поэтому там этого нет; своего
 * сервера у нас нет. Поэтому показ выключается в настройках, а результат
 * кладётся на диск: один поход на сайт, а не по одному на каждое открытие
 * переписки.
 *
 * Неудачи тоже запоминаем — иначе страница без разметки перечитывалась бы
 * при каждой отрисовке.
 */
object LinkPreviews {

    data class Preview(
        val url: String,
        val site: String,
        val title: String,
        val description: String,
        val imageFile: String?,
    ) {
        val empty: Boolean get() = title.isBlank() && description.isBlank() && imageFile == null
    }

    private const val MAX_HTML = 512 * 1024
    private const val MAX_IMAGE = 3 * 1024 * 1024

    private var dir: File? = null
    private val memory = HashMap<String, Preview>()
    private val failed = HashSet<String>()
    private val inFlight = HashSet<String>()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun init(context: Context) {
        dir = File(context.cacheDir, "pismo/linkpreview").apply { mkdirs() }
    }

    private fun keyOf(url: String): String =
        url.hashCode().toUInt().toString(16) + "_" + url.length

    /** Готовая карточка из памяти или с диска. null — ещё не собирали. */
    fun cached(url: String): Preview? {
        memory[url]?.let { return it }
        val d = dir ?: return null
        val f = File(d, keyOf(url) + ".txt")
        if (!f.exists()) return null
        return runCatching {
            val parts = f.readText().split("\n\n")
            if (parts.size < 4) return@runCatching null
            Preview(
                url = url,
                site = parts[0],
                title = parts[1],
                description = parts[2],
                imageFile = parts[3].takeIf { it.isNotBlank() },
            ).also { memory[url] = it }
        }.getOrNull()
    }

    /**
     * Собирает карточку, если её ещё нет. Возвращает её же или null, когда
     * собрать нечего — страница без разметки, ошибка сети, показ выключен.
     */
    suspend fun fetch(url: String): Preview? = withContext(Dispatchers.IO) {
        if (!Prefs.linkPreviews) return@withContext null
        if (dir == null) return@withContext null
        cached(url)?.let { return@withContext if (it.empty) null else it }
        synchronized(this@LinkPreviews) {
            if (url in failed || url in inFlight) return@withContext null
            inFlight.add(url)
        }
        try {
            val html = readHtml(url)
            if (html == null) {
                remember(url, null)
                return@withContext null
            }
            val site = meta(html, "og:site_name")
                ?: com.pismo.messenger.core.LinkSources.of(url).title
            val title = meta(html, "og:title") ?: titleTag(html) ?: ""
            val desc = meta(html, "og:description") ?: meta(html, "description") ?: ""
            val imgUrl = meta(html, "og:image")
            val imgFile = imgUrl?.let { downloadImage(absolute(url, it), keyOf(url)) }

            val p = Preview(url, site, title.trim(), desc.trim(), imgFile)
            if (p.empty) {
                remember(url, null)
                return@withContext null
            }
            remember(url, p)
            p
        } catch (_: Throwable) {
            remember(url, null)
            null
        } finally {
            synchronized(this@LinkPreviews) { inFlight.remove(url) }
        }
    }

    private fun remember(url: String, p: Preview?) {
        if (p == null) {
            synchronized(this) { failed.add(url) }
            return
        }
        synchronized(this) { memory[url] = p }
        val d = dir ?: return
        runCatching {
            File(d, keyOf(url) + ".txt").writeText(
                listOf(p.site, p.title, p.description, p.imageFile ?: "").joinToString("\n\n")
            )
        }
    }

    private fun readHtml(url: String): String? {
        val req = Request.Builder()
            .url(url)
            // Часть сайтов без него отдаёт заглушку.
            .header("User-Agent", "Mozilla/5.0 (compatible; PISMO link preview)")
            .header("Accept-Language", "ru,en;q=0.8")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val type = resp.header("Content-Type").orEmpty()
            if (!type.contains("html", ignoreCase = true)) return null
            val body = resp.body ?: return null
            // Разметка сидит в начале документа: читать целиком незачем, а
            // страница может весить мегабайты.
            val bytes = body.byteStream().readAtMost(MAX_HTML)
            return String(bytes, Charsets.UTF_8)
        }
    }

    private fun downloadImage(url: String, key: String): String? = runCatching {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; PISMO link preview)")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val body = resp.body ?: return@runCatching null
            if (body.contentLength() > MAX_IMAGE) return@runCatching null
            val bytes = body.byteStream().readAtMost(MAX_IMAGE)
            if (bytes.size < 100) return@runCatching null
            val d = dir ?: return@runCatching null
            val f = File(d, key + ".img")
            f.writeBytes(bytes)
            f.absolutePath
        }
    }.getOrNull()

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ── разбор разметки ───────────────────────────────────────────────
    private fun meta(html: String, name: String): String? {
        val rx = Regex(
            "<meta[^>]+(?:property|name)\\s*=\\s*[\"']" + Regex.escape(name) + "[\"'][^>]*>",
            RegexOption.IGNORE_CASE,
        )
        val tag = rx.find(html)?.value ?: return null
        val content = Regex("content\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1) ?: return null
        return unescape(content).takeIf { it.isNotBlank() }
    }

    private fun titleTag(html: String): String? =
        Regex("<title[^>]*>([\\s\\S]{0,300}?)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?.let { unescape(it).trim() }
            ?.takeIf { it.isNotBlank() }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")

    /** og:image часто относительный — достраиваем по адресу страницы. */
    private fun absolute(pageUrl: String, link: String): String = when {
        link.startsWith("http://") || link.startsWith("https://") -> link
        link.startsWith("//") -> "https:" + link
        link.startsWith("/") -> {
            val scheme = pageUrl.substringBefore("://", "https")
            val host = pageUrl.substringAfter("://").substringBefore('/')
            scheme + "://" + host + link
        }
        else -> pageUrl.substringBeforeLast('/') + "/" + link
    }
}
