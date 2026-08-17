package com.pismo.messenger.data

import android.content.Context
import com.pismo.messenger.core.UserSession
import java.io.File

/**
 * Локальный кеш медиа сообщений — порт PISMO/MediaCache.cs.
 *
 * Структура: <cacheDir>/pismo/<userId>/<kind>/<msgId>.<ext>
 * kind: img | audio | video | file
 *
 * Лимит 500 МБ, при превышении удаляются самые старые файлы (до 80% лимита).
 * На Android каталог лежит в cacheDir — система вправе почистить его сама,
 * это безопасно: данные всегда можно перекачать из БД.
 */
object MediaCache {

    private const val MAX_CACHE_BYTES = 500L * 1024 * 1024

    /**
     * Память поверх диска.
     *
     * Диск сам по себе проблему прокрутки не решает: LazyColumn уничтожает
     * пузырь, ушедший за край экрана, вместе с его remember-состоянием, и
     * при возврате картинка читалась с диска заново — с миганием пустого
     * места. ПК держит уже раскодированные изображения в памяти списка и
     * такого не показывает.
     *
     * Здесь кэш байтов, а не Bitmap: раскодированный кадр на 4 МП занимает
     * ~16 МБ, десяток таких — и приложение падает по памяти.
     */
    private const val MEMORY_BYTES = 24 * 1024 * 1024

    private val memory = object : android.util.LruCache<String, ByteArray>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private lateinit var baseDir: File

    fun init(context: Context) {
        baseDir = File(context.cacheDir, "pismo")
        runCatching { baseDir.mkdirs() }
    }

    /** Каталог кеша текущего пользователя (учитывает impersonation). */
    private val root: File
        get() {
            val dir = File(baseDir, UserSession.effectiveId.toString())
            if (!dir.exists()) {
                runCatching {
                    for (kind in listOf("img", "audio", "video", "file")) {
                        File(dir, kind).mkdirs()
                    }
                }
            }
            return dir
        }

    private fun pathFor(msgId: Int, kind: String, fileName: String?): File? = runCatching {
        val ext = when (kind) {
            "img" -> if (fileName?.endsWith(".gif", true) == true) "gif" else "jpg"
            "audio" -> "wav"
            "video" -> "psmvid"
            "file" -> fileName?.substringAfterLast('.', "")?.lowercase()?.ifBlank { "bin" } ?: "bin"
            else -> "bin"
        }
        val dir = File(root, kind)
        if (!dir.exists()) dir.mkdirs()
        File(dir, "$msgId.$ext")
    }.getOrNull()

    private fun memoryKey(msgId: Int, kind: String): String =
        "${UserSession.effectiveId}/$kind/$msgId"

    /**
     * Синхронное чтение ИЗ ПАМЯТИ — без диска и без БД.
     *
     * Ради него всё и затевалось: пузырь при возврате в зону видимости
     * должен получить картинку прямо в момент создания состояния, а не
     * через suspend-загрузку, иначе прокрутка вверх-вниз выглядит как
     * повторная загрузка всего чата.
     */
    fun peek(msgId: Int, kind: String): ByteArray? = memory.get(memoryKey(msgId, kind))

    fun get(msgId: Int, kind: String, fileName: String? = null): ByteArray? {
        memory.get(memoryKey(msgId, kind))?.let { return it }

        val f = pathFor(msgId, kind, fileName) ?: return null
        if (!f.exists()) return null
        return runCatching {
            f.setLastModified(System.currentTimeMillis())   // отметка использования для LRU
            val data = f.readBytes()
            // В память кладём только то, что и правда листают глазами.
            // Файлы и видео-кружки бывают на десятки мегабайт и вытеснили
            // бы из неё всё остальное на первом же вложении.
            if (kind == "img" || kind == "audio") memory.put(memoryKey(msgId, kind), data)
            data
        }.getOrNull()
    }

    fun put(msgId: Int, kind: String, data: ByteArray?, fileName: String? = null) {
        if (data == null || data.isEmpty()) return
        if (kind == "img" || kind == "audio") memory.put(memoryKey(msgId, kind), data)
        val f = pathFor(msgId, kind, fileName) ?: return
        runCatching {
            f.writeBytes(data)
            trimIfNeeded()
        }
    }

    fun has(msgId: Int, kind: String, fileName: String? = null): Boolean =
        pathFor(msgId, kind, fileName)?.exists() == true

    /** Путь к файлу кеша — нужен, когда данные надо отдать плееру потоком. */
    fun fileFor(msgId: Int, kind: String, fileName: String? = null): File? =
        pathFor(msgId, kind, fileName)?.takeIf { it.exists() }

    fun invalidate(msgId: Int, kind: String, fileName: String? = null) {
        memory.remove(memoryKey(msgId, kind))
        runCatching { pathFor(msgId, kind, fileName)?.delete() }
    }

    /** Инвалидация всех типов — вызывать после удаления сообщения. */
    fun invalidateAll(msgId: Int, fileName: String? = null) {
        invalidate(msgId, "img", fileName)
        invalidate(msgId, "audio")
        invalidate(msgId, "video")
        invalidate(msgId, "file", fileName)
    }

    fun clear() {
        memory.evictAll()
        runCatching {
            root.deleteRecursively()
            root.mkdirs()
        }
    }

    fun sizeBytes(): Long = runCatching {
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun trimIfNeeded() {
        runCatching {
            val files = root.walkTopDown().filter { it.isFile }.toMutableList()
            var total = files.sumOf { it.length() }
            if (total <= MAX_CACHE_BYTES) return

            files.sortBy { it.lastModified() }   // самые старые — первыми
            val target = MAX_CACHE_BYTES * 8 / 10
            for (f in files) {
                if (total <= target) break
                val sz = f.length()
                if (f.delete()) total -= sz
            }
        }
    }
}
