package com.pismo.messenger.core

import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * Открыть ссылку в браузере — настольная замена одноимённого объекта,
 * который на Android бросает Intent.
 *
 * Имя файла нарочно не совпадает с именем объекта: настольный модуль
 * исключает `core/LinkOpener.kt` из общих исходников, и файл с таким же
 * именем здесь тоже попал бы под исключение.
 *
 * `Desktop.browse` на Linux работает не везде: под Wayland и в урезанных
 * окружениях AWT часто говорит, что не поддерживается. Поэтому следом идёт
 * `xdg-open` — он есть в любом рабочем столе и делает ровно то же самое.
 */
object LinkOpener {

    fun open(url: String): Boolean {
        val u = url.trim().ifEmpty { return false }
        val full = if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"

        runCatching {
            val d = Desktop.getDesktop()
            if (Desktop.isDesktopSupported() && d.isSupported(Desktop.Action.BROWSE)) {
                d.browse(URI(full))
                return true
            }
        }
        return runCatching {
            ProcessBuilder("xdg-open", full)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        }.getOrDefault(false)
    }

    /** Показать файл в проводнике — для «сохранить и открыть папку». */
    fun reveal(file: File): Boolean = runCatching {
        ProcessBuilder("xdg-open", file.parentFile?.absolutePath ?: file.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        true
    }.getOrDefault(false)

    /** Открыть сам файл тем, чем его открывает система. */
    fun openFile(file: File): Boolean = runCatching {
        ProcessBuilder("xdg-open", file.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        true
    }.getOrDefault(false)
}
