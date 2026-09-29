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

/**
 * Картинка в формате, который прочтут ВСЕ клиенты.
 *
 * Определяем по сигнатуре файла, а не по расширению: расширение врёт легко —
 * галерея отдаёт «.jpg», внутри которого лежит WebP.
 */
fun isPortableImage(data: ByteArray?): Boolean {
    if (data == null || data.size < 4) return false
    fun at(i: Int) = data[i].toInt() and 0xFF
    return when {
        at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> true  // PNG
        at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> true                   // JPEG
        at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> true                   // GIF
        at(0) == 0x42 && at(1) == 0x4D -> true                                    // BMP
        else -> false
    }
}

/**
 * Приводит картинку к формату, который прочтут все клиенты.
 *
 * ЗАЧЕМ. Телефон декодирует WebP, HEIC и AVIF штатно, а ПК рисует картинки
 * через GDI+ — тот знает только BMP, GIF, JPEG, PNG и TIFF. Отправленный с
 * телефона WebP превращался на компьютере в «Не удалось загрузить
 * изображение», хотя на самом телефоне открывался как ни в чём не бывало.
 *
 * Уже универсальное не трогаем: перекодирование стоит качества и времени, а
 * не даёт ничего. GIF сюда тоже не попадает — и хорошо, иначе от анимации
 * остался бы один кадр.
 *
 * Остальное пересобираем: в PNG, если есть прозрачность — JPEG её потеряет
 * и зальёт чёрным, — иначе в JPEG, который для фотографии в разы меньше.
 *
 * Не смогли разобрать — отдаём как было. Пусть лучше не покажется на ПК,
 * чем потеряется совсем.
 */
fun toPortableImage(data: ByteArray, fileName: String?): Pair<ByteArray, String> {
    val name = fileName.orEmpty().ifBlank { "image" }
    if (isPortableImage(data)) return data to name

    val bmp = runCatching {
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
    }.getOrNull() ?: return data to name

    return runCatching {
        val out = java.io.ByteArrayOutputStream(data.size)
        val png = bmp.hasAlpha()
        if (png) bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        else bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
        val converted = out.toByteArray()
        if (converted.isEmpty()) data to name
        else converted to withExt(name, if (png) "png" else "jpg")
    }.getOrDefault(data to name).also { runCatching { bmp.recycle() } }
}

/** Меняет расширение имени файла, сохраняя основу. */
private fun withExt(fileName: String, ext: String): String {
    val dot = fileName.lastIndexOf('.')
    val base = if (dot > 0) fileName.substring(0, dot) else fileName
    return "$base.$ext"
}

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
