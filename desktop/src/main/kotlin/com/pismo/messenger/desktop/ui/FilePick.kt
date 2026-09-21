package com.pismo.messenger.desktop.ui

import java.io.File
import javax.swing.JFileChooser

/**
 * Выбор файла для отправки.
 *
 * JFileChooser, а не AWT FileDialog: под Linux второй выглядит по-разному в
 * зависимости от окружения и местами не умеет фильтры. Вызывается с потока
 * отрисовки, а он же и есть поток событий AWT, так что отдельного перехода
 * не нужно.
 */
object FilePick {
    fun choose(): File? = runCatching {
        val ch = JFileChooser()
        ch.dialogTitle = "Отправить файл"
        ch.isMultiSelectionEnabled = false
        if (ch.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) ch.selectedFile else null
    }.getOrNull()
}
