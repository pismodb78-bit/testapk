package com.pismo.messenger.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал push-уведомлений.
 *
 * ЗАЧЕМ. На сервере каждое решение видно в логе: событие разобрано, адрес
 * найден, Google принял. На телефоне же push приходит в выгруженное
 * приложение, и всё, что происходит дальше, не видно вообще никак —
 * уведомления либо нет, либо нет. Отличить «не дошло» от «дошло и молча
 * отброшено» было нечем, а причин для второго хватает: выключены
 * уведомления, нет разрешения, отправитель заглушён.
 *
 * Поэтому каждое решение приёмника записываем себе и показываем в
 * настройках — так же, как след падения. Наружу ничего не уходит.
 *
 * Текста сообщений здесь НЕТ и быть не должно: push его не несёт, а
 * складывать переписку в незашифрованный файл ради удобства отладки
 * нельзя.
 */
object PushLog {

    private const val FILE = "push_log.txt"
    private const val KEEP = 30

    private var dir: File? = null

    fun init(context: android.content.Context) {
        runCatching { dir = context.filesDir }
    }

    @Synchronized
    fun add(line: String) {
        val target = dir?.let { File(it, FILE) } ?: return
        runCatching {
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val old = if (target.exists()) target.readLines() else emptyList()
            // Держим только хвост: журнал нужен для разбора «почему сейчас
            // не пришло», а не как история за всё время.
            val kept = (old + "$stamp  $line").takeLast(KEEP)
            target.writeText(kept.joinToString("\n"))
        }
    }

    fun last(): String? {
        val target = dir?.let { File(it, FILE) } ?: return null
        return runCatching {
            if (!target.exists()) null else target.readText().ifBlank { null }
        }.getOrNull()
    }

    fun clear() {
        runCatching { dir?.let { File(it, FILE) }?.delete() }
    }
}
