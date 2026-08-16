package com.pismo.messenger.core

import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Палитра аватарок — те же 8 цветов, что в GetAvatarColor ПК-версии. */
private val AVATAR_PALETTE = listOf(
    Color(0xFF5865F2),
    Color(0xFF57AB5A),
    Color(0xFFF04747),
    Color(0xFFFAA61A),
    Color(0xFF00B0F4),
    Color(0xFFEB459E),
    Color(0xFF62C8DA),
    Color(0xFF9C59B6),
)

fun avatarColor(uid: Int): Color = AVATAR_PALETTE[abs(uid) % AVATAR_PALETTE.size]

fun avatarLetter(name: String): String =
    name.trim().firstOrNull()?.uppercase() ?: "?"

/** Разбор "#RRGGBB" из group_chats.avatar_color с запасным вариантом blurple. */
fun parseHexColor(hex: String?): Color {
    if (hex.isNullOrBlank()) return Color(0xFF5865F2)
    return try {
        val clean = hex.trim().removePrefix("#")
        val value = clean.toLong(16)
        when (clean.length) {
            6 -> Color(0xFF000000 or value)
            8 -> Color(value)
            else -> Color(0xFF5865F2)
        }
    } catch (_: Exception) {
        Color(0xFF5865F2)
    }
}

private val RU = Locale("ru", "RU")
private val timeFmt = SimpleDateFormat("HH:mm", RU)
private val dateFmt = SimpleDateFormat("d MMMM yyyy", RU)
private val shortDateFmt = SimpleDateFormat("dd.MM.yy", RU)

fun formatTime(millis: Long): String = timeFmt.format(Date(millis))

/** «12 марта 2025» — тот же формат разделителя дат, что в ПК-версии. */
fun formatDateSeparator(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    return when {
        sameDay(cal, today) -> "Сегодня"
        sameDay(cal, yesterday) -> "Вчера"
        else -> dateFmt.format(Date(millis))
    }
}

/** Время в списке диалогов: сегодня — часы, иначе дата. */
fun formatListTime(millis: Long?): String {
    if (millis == null || millis <= 0) return ""
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance()
    val sameDay = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) timeFmt.format(Date(millis)) else shortDateFmt.format(Date(millis))
}

fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    return if (kb > 1024) String.format(RU, "%.1f МБ", kb / 1024.0) else "${kb.toInt()} КБ"
}

fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}

/** Цвет плитки файла по расширению — соответствует BuildFileCard из ПК-версии. */
fun fileColor(fileName: String?): Color = when (fileExt(fileName)) {
    "pdf" -> Color(0xFFDC3535)
    "doc", "docx" -> Color(0xFF2956A3)
    "xls", "xlsx" -> Color(0xFF20783E)
    "ppt", "pptx" -> Color(0xFFC6451E)
    "zip", "rar", "7z", "tar", "gz" -> Color(0xFF8C5A14)
    "txt", "rtf" -> Color(0xFF505050)
    else -> Color(0xFF5865F2)
}

fun fileExt(fileName: String?): String =
    fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()

fun fileBadge(fileName: String?): String {
    val ext = fileExt(fileName)
    return if (ext.isNotEmpty()) ext.uppercase().take(4) else "FILE"
}

/** Проверка magic bytes GIF — как IsGif в ПК-версии. */
fun isGif(data: ByteArray?): Boolean =
    data != null && data.size >= 3 &&
            data[0] == 0x47.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte()

fun isImageName(fileName: String?): Boolean =
    fileExt(fileName) in setOf("jpg", "jpeg", "png", "bmp", "webp")

fun isGifName(fileName: String?): Boolean = fileExt(fileName) == "gif"

/** Собирает отображаемое имя: «Имя Фамилия», иначе логин. */
fun buildName(name: String?, surname: String?, login: String?): String {
    val full = "${name.orEmpty()} ${surname.orEmpty()}".trim()
    return full.ifBlank { login.orEmpty() }
}

fun String.ellipsize(max: Int): String =
    if (length > max) take(max) + "…" else this
