package com.pismo.messenger.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал падений.
 *
 * PISMO ставится APK-файлом, а не из Play, поэтому отчётов о падениях не
 * приходит ниоткуда: со стороны видно только системное «Ошибка приложения»,
 * без единой подсказки, где именно сломалось. Особенно это мешает, когда
 * падает фоновая служба — приложение при этом даже не открыто.
 *
 * Поэтому последнее падение записываем себе, в личную папку приложения, и
 * показываем в настройках с кнопкой «Поделиться». Наружу само ничего не
 * уходит: отправить след человек решает сам.
 */
object CrashLog {

    private const val FILE = "last_crash.txt"
    private var dir: File? = null

    /**
     * Ставится ПЕРВОЙ строкой при запуске процесса — до всего остального,
     * иначе падение самой подготовки приложения записать будет некому.
     */
    fun install(context: Context) {
        runCatching { dir = context.filesDir }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(thread.name, error) }
            // Системный обработчик оставляем на месте: пусть Android
            // по-прежнему закрывает процесс и показывает своё сообщение.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(threadName: String, error: Throwable) {
        val target = dir?.let { File(it, FILE) } ?: return
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        target.writeText(
            buildString {
                appendLine("Время: $stamp")
                appendLine("Поток: $threadName")
                appendLine("Версия: ${com.pismo.messenger.BuildConfig.VERSION_NAME}")
                appendLine("Телефон: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
                appendLine()
                append(stack.toString())
            }
        )
    }

    /** След последнего падения или null, если приложение ещё не падало. */
    fun last(): String? = runCatching {
        val f = dir?.let { File(it, FILE) } ?: return@runCatching null
        if (f.exists() && f.length() > 0) f.readText() else null
    }.getOrNull()

    fun clear() { runCatching { dir?.let { File(it, FILE).delete() } } }
}
