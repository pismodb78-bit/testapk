package com.pismo.messenger.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Сохранение вложений в галерею телефона.
 *
 * На ПК «скачать» — это просто SaveFileDialog в любую папку; на Android
 * такого понятия нет, и файл, положенный в приватный каталог приложения,
 * пользователь не увидит нигде. Поэтому пишем в общие коллекции MediaStore
 * (папка PISMO внутри «Изображения» / «Видео» / «Загрузки») — оттуда
 * картинка сразу видна в галерее, а видео в плеере.
 *
 * До Android 10 общих коллекций с песочницей не было: там пишем файл прямо
 * в публичный каталог и дёргаем сканер, иначе галерея о нём не узнает.
 * Разрешение WRITE_EXTERNAL_STORAGE в манифесте объявлено ровно до API 28
 * по этой причине.
 */
object MediaSaver {

    private const val ALBUM = "PISMO"

    suspend fun saveImage(context: Context, fileName: String, bytes: ByteArray): Boolean =
        save(context, fileName, bytes, Kind.IMAGE)

    suspend fun saveVideo(context: Context, fileName: String, bytes: ByteArray): Boolean =
        save(context, fileName, bytes, Kind.VIDEO)

    suspend fun saveFile(context: Context, fileName: String, bytes: ByteArray): Boolean =
        save(context, fileName, bytes, Kind.FILE)

    private enum class Kind { IMAGE, VIDEO, FILE }

    private suspend fun save(
        context: Context,
        fileName: String,
        bytes: ByteArray,
        kind: Kind,
    ): Boolean = withContext(Dispatchers.IO) {
        val safe = sanitize(fileName)
        val mime = mimeOf(safe, kind)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, safe, bytes, kind, mime)
            } else {
                saveLegacy(context, safe, bytes, kind)
            }
        }.getOrDefault(false)
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        context: Context,
        name: String,
        bytes: ByteArray,
        kind: Kind,
        mime: String,
    ): Boolean {
        val collection = when (kind) {
            Kind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Kind.FILE -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relative = when (kind) {
            Kind.IMAGE -> Environment.DIRECTORY_PICTURES + File.separator + ALBUM
            Kind.VIDEO -> Environment.DIRECTORY_MOVIES + File.separator + ALBUM
            Kind.FILE -> Environment.DIRECTORY_DOWNLOADS + File.separator + ALBUM
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            // Пока файл не дописан, он помечен как «в процессе»: иначе
            // галерея успевает показать половину картинки.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, name: String, bytes: ByteArray, kind: Kind): Boolean {
        val dirType = when (kind) {
            Kind.IMAGE -> Environment.DIRECTORY_PICTURES
            Kind.VIDEO -> Environment.DIRECTORY_MOVIES
            Kind.FILE -> Environment.DIRECTORY_DOWNLOADS
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(dirType), ALBUM)
        if (!dir.exists() && !dir.mkdirs()) return false
        val file = uniqueIn(dir, name)
        file.writeBytes(bytes)
        // Без сканера файл лежит на диске, но галерея его не видит.
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        }
        return true
    }

    private fun uniqueIn(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists() && i < 1000) {
            f = File(dir, if (ext.isBlank()) "$stem ($i)" else "$stem ($i).$ext")
            i++
        }
        return f
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "pismo" }

    private fun mimeOf(name: String, kind: Kind): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when (kind) {
            Kind.IMAGE -> "image/jpeg"
            Kind.VIDEO -> "video/mp4"
            Kind.FILE -> "application/octet-stream"
        }
    }
}
