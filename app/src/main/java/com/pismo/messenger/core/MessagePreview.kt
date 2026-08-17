package com.pismo.messenger.core

/**
 * Короткое описание сообщения для уведомления и карточки чата — порт
 * PreviewOf и DescribeLast из MainForm.cs.
 *
 * Формулировки ПК сохранены («🎤 Голосовое», «⭕ Кружок», «🖼 Фото»,
 * «🎞 GIF»), а общий «📎 Файл» дополнительно разделён по расширению на
 * документ, архив, видео и прочее: на телефоне уведомление — это часто
 * всё, что человек увидит, и «файл» без уточнения ничего не говорит.
 *
 * GIF узнаётся по префиксу «gif:» в тексте — так его помечает ПК при
 * отправке, и менять это нельзя: расшифрованный текст общий для обоих
 * клиентов.
 */
object MessagePreview {

    private val ARCHIVE = setOf(
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "cab", "iso"
    )
    private val DOCUMENT = setOf(
        "pdf", "doc", "docx", "odt", "rtf", "txt", "xls", "xlsx", "ods",
        "csv", "ppt", "pptx", "odp", "djvu", "epub", "fb2"
    )
    private val VIDEO = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "webm", "flv", "m4v", "3gp", "mpg", "mpeg"
    )
    private val AUDIO = setOf(
        "mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "wma"
    )
    private val IMAGE = setOf(
        "jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "tiff"
    )

    /**
     * Описание по флагам сообщения. Порядок проверок повторяет ПК:
     * голосовое → кружок → картинка → файл → текст.
     */
    fun describe(
        text: String,
        hasImage: Boolean,
        hasAudio: Boolean,
        hasVideo: Boolean,
        hasFile: Boolean,
        fileName: String?,
    ): String {
        val isGif = text.startsWith("gif:", ignoreCase = true)
        return when {
            hasAudio -> "🎤 Голосовое"
            hasVideo -> "⭕ Кружок"
            hasImage -> if (isGif) "🎞 GIF" else "🖼 Фото"
            hasFile -> describeFile(fileName)
            isGif -> "🎞 GIF"
            text.isNotBlank() -> text
            else -> "💬 Сообщение"
        }
    }

    /** Тип вложения по расширению — то деление, которого на ПК нет. */
    fun describeFile(fileName: String?): String {
        val name = fileName?.trim().orEmpty()
        if (name.isEmpty()) return "📎 Файл"
        val ext = name.substringAfterLast('.', "").lowercase()
        val kind = when (ext) {
            in ARCHIVE -> "🗜 Архив"
            in DOCUMENT -> "📄 Документ"
            in VIDEO -> "🎬 Видео"
            in AUDIO -> "🎵 Аудио"
            in IMAGE -> "🖼 Изображение"
            else -> "📎 Файл"
        }
        return "$kind · $name"
    }

    /**
     * То же, но с именем отправителя впереди — для уведомлений о группах
     * и каналах, где одного текста мало, чтобы понять, кто написал.
     */
    fun withSender(sender: String, preview: String): String =
        if (sender.isBlank()) preview else "$sender: $preview"
}
