package com.pismo.messenger.core

/**
 * Какие вложения проигрываются прямо в приложении — порт списков из
 * MediaPlayerForm.cs (IsAudio / IsVideo / IsMedia).
 *
 * Списки намеренно те же, что на ПК: если файл там открывается встроенным
 * плеером, он должен открываться и здесь, иначе одно и то же сообщение
 * выглядит в двух клиентах по-разному. Набор — то, что умеет и Chromium
 * (ПК), и системный MediaPlayer Android.
 */
object MediaKinds {

    private val AUDIO = setOf(
        "mp3", "wav", "ogg", "oga", "m4a", "aac", "flac", "opus", "weba"
    )

    private val VIDEO = setOf(
        "mp4", "webm", "m4v", "ogv", "mov", "3gp", "mkv"
    )

    fun extOf(fileName: String?): String =
        fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()

    fun isAudio(fileName: String?): Boolean = extOf(fileName) in AUDIO
    fun isVideo(fileName: String?): Boolean = extOf(fileName) in VIDEO
    fun isMedia(fileName: String?): Boolean = isAudio(fileName) || isVideo(fileName)

    /**
     * GIF определяется ПО СОДЕРЖИМОМУ, а не по имени файла — так же, как
     * IsGif на ПК.
     *
     * Имя для картинок теперь вообще не сохраняется (иначе у собеседника
     * рядом с изображением появлялась вторая строка — карточка файла),
     * поэтому судить по расширению стало нечем. Да и раньше это было
     * ненадёжно: картинка из буфера обмена приходит без имени.
     */
    fun isGif(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 6) return false
        return bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()
    }
}
