package com.pismo.messenger.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.pismo.messenger.core.LinkOpener
import org.jetbrains.skia.Image
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

/**
 * Картинки, голосовые и файлы на настольной стороне.
 *
 * Сами байты приезжают общим кодом (ChatRepository.loadImage/loadAudio/
 * loadFile) — здесь только то, чего на телефоне нет: раскодировать картинку
 * средствами Skia, проиграть WAV через звуковую подсистему JVM и отдать файл
 * системе, чтобы открыла чем положено.
 */
object Media {

    // ── Картинки ─────────────────────────────────────────────────────
    //
    // Раскодированные держим в памяти: одно и то же изображение попадает в
    // отрисовку много раз подряд, а раскодирование стоит заметно дороже
    // самого показа. Ограничение по числу, а не по байтам, нарочно грубое —
    // лента всё равно показывает десятки картинок, а не тысячи.
    private const val IMAGE_CACHE = 120
    private val images = object : LinkedHashMap<Int, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ImageBitmap>) =
            size > IMAGE_CACHE
    }

    @Synchronized
    fun decode(msgId: Int, bytes: ByteArray?): ImageBitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        images[msgId]?.let { return it }
        val img = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
            ?: return null
        images[msgId] = img
        return img
    }

    // ── Голосовые ────────────────────────────────────────────────────
    //
    // Играет ровно одно за раз: два голосовых одновременно — это не выбор
    // человека, а недосмотр. Прежнее останавливаем, как на телефоне.
    private var clip: Clip? = null
    private var playingId: Int = 0

    val nowPlaying: Int get() = playingId

    @Synchronized
    fun stopVoice() {
        runCatching { clip?.stop(); clip?.close() }
        clip = null
        playingId = 0
    }

    /**
     * Проиграть голосовое. Возвращает false, если формат не поддержан —
     * записи с телефона это обычный WAV, и он поддержан везде.
     */
    @Synchronized
    fun playVoice(msgId: Int, bytes: ByteArray?, onFinished: () -> Unit): Boolean {
        stopVoice()
        if (bytes == null || bytes.isEmpty()) return false
        return runCatching {
            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
            val c = AudioSystem.getClip()
            c.open(stream)
            c.addLineListener { ev ->
                if (ev.type == javax.sound.sampled.LineEvent.Type.STOP) {
                    stopVoice()
                    onFinished()
                }
            }
            c.start()
            clip = c
            playingId = msgId
            true
        }.getOrDefault(false)
    }

    // ── Файлы ────────────────────────────────────────────────────────

    /** Куда складываем полученные вложения: как принято в Linux — в «Загрузки». */
    val downloadsDir: File by lazy {
        val home = System.getProperty("user.home") ?: "."
        // XDG-имя каталога может быть переведено; если его нет, не выдумываем
        // своё дерево, а кладём прямо в домашний каталог — там точно видно.
        val candidates = listOf(File(home, "Downloads"), File(home, "Загрузки"))
        candidates.firstOrNull { it.isDirectory } ?: File(home)
    }

    /**
     * Сохраняет вложение и отдаёт его системе. Имя не перезаписывает чужое:
     * при совпадении дописывается номер, как это делают браузеры.
     */
    fun saveAndOpen(fileName: String?, bytes: ByteArray?): File? {
        if (bytes == null || bytes.isEmpty()) return null
        val safe = (fileName ?: "file").substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "file" }
        return runCatching {
            var out = File(downloadsDir, safe)
            if (out.exists()) {
                val stem = safe.substringBeforeLast('.', safe)
                val ext = safe.substringAfterLast('.', "")
                var n = 1
                while (out.exists()) {
                    out = File(downloadsDir, stem + " (" + n++ + ")" + if (ext.isEmpty()) "" else ".$ext")
                }
            }
            out.writeBytes(bytes)
            LinkOpener.openFile(out)
            out
        }.getOrNull()
    }

    /** Человеческий размер: «1,4 МБ» вместо 1468006. */
    fun humanSize(bytes: Long): String = when {
        bytes < 0 -> ""
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> String.format("%.0f КБ", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f МБ", bytes / (1024.0 * 1024))
        else -> String.format("%.1f ГБ", bytes / (1024.0 * 1024 * 1024))
    }
}
