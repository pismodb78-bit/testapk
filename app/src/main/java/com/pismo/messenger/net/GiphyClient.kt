package com.pismo.messenger.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Одна гифка из выдачи: маленькая для сетки, полная для отправки. */
data class GifItem(val previewUrl: String, val fullUrl: String)

/**
 * Поиск гифок в Giphy — порт GiphyClient.cs.
 *
 * Ключ тот же, что в ПК-версии: он бета-уровня (лимит 100 запросов в час)
 * и лежит в её исходниках открытым текстом, то есть давно не секрет.
 * Заводить второй смысла нет — пусть у обоих клиентов будет один лимит.
 */
object GiphyClient {

    private const val API_KEY = "yNJ3u3R2019HeM5VjpFqCz2wSpfrIYf9"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun trending(limit: Int = 24): List<GifItem> = request(
        "https://api.giphy.com/v1/gifs/trending?api_key=$API_KEY&limit=$limit&rating=pg-13"
    )

    suspend fun search(query: String, limit: Int = 24): List<GifItem> {
        val q = URLEncoder.encode(query, "UTF-8")
        return request(
            "https://api.giphy.com/v1/gifs/search?api_key=$API_KEY&q=$q&limit=$limit" +
                "&rating=pg-13&bundle=messaging_non_clips"
        )
    }

    private suspend fun request(url: String): List<GifItem> = withContext(Dispatchers.IO) {
        runCatching {
            val body = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext emptyList<GifItem>()
                r.body?.string().orEmpty()
            }
            val data = JSONObject(body).optJSONArray("data")
                ?: return@withContext emptyList<GifItem>()

            val out = ArrayList<GifItem>(data.length())
            for (i in 0 until data.length()) {
                val images = data.optJSONObject(i)?.optJSONObject("images") ?: continue
                // Порядок предпочтений тот же, что на ПК: для сетки самая
                // лёгкая версия, для отправки — сжатая, но не огрызок.
                val preview = urlOf(images, "fixed_width_small")
                    ?: urlOf(images, "fixed_width")
                    ?: urlOf(images, "downsized")
                val full = urlOf(images, "downsized_medium")
                    ?: urlOf(images, "downsized")
                    ?: urlOf(images, "original")
                if (preview != null && full != null) out.add(GifItem(preview, full))
            }
            out as List<GifItem>
        }.getOrDefault(emptyList<GifItem>())
    }

    private fun urlOf(images: JSONObject, rendition: String): String? =
        images.optJSONObject(rendition)?.optString("url")?.takeIf { it.isNotBlank() }

    /** Скачивает байты гифки — и превью для сетки, и полную для отправки. */
    suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        }.getOrNull()
    }
}
