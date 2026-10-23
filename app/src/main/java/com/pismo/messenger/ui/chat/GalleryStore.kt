package com.pismo.messenger.ui.chat

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Один снимок или ролик из галереи телефона. */
data class GalleryItem(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val durationMs: Long,
    val sizeBytes: Long,
)

/**
 * Галерея телефона для своего листа вложений.
 *
 * Почему не системный выбор файлов, который был раньше: он отдаёт список
 * строк с именами, и из него не видно ни что выбираешь, ни в каком порядке.
 * В Telegram плитка видна сразу, кружок справа помнит порядок выбора, а
 * нажатие в середину открывает просмотр. Всё это требует СВОЕЙ сетки, а
 * значит — своего чтения галереи.
 *
 * Фото и ролики берём одним запросом к общей таблице файлов: в ленте
 * галереи они и должны идти вперемешку, по времени съёмки.
 */
object GalleryStore {

    /** Разрешения, без которых галерея не читается. */
    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun granted(context: Context): Boolean =
        permissions().any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    suspend fun load(context: Context, limit: Int = 600): List<GalleryItem> =
        withContext(Dispatchers.IO) {
            if (!granted(context)) return@withContext emptyList()
            val out = ArrayList<GalleryItem>(limit)
            runCatching {
                val collection = MediaStore.Files.getContentUri("external")
                val cols = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DURATION,
                )
                val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
                val args = arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                )
                val order = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
                context.contentResolver.query(collection, cols, sel, args, order)?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val iDur = c.getColumnIndex(MediaStore.Files.FileColumns.DURATION)
                    while (c.moveToNext() && out.size < limit) {
                        val id = c.getLong(iId)
                        val isVideo =
                            c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        out += GalleryItem(
                            uri = ContentUris.withAppendedId(collection, id),
                            name = c.getString(iName) ?: "media_$id",
                            isVideo = isVideo,
                            durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                            sizeBytes = c.getLong(iSize),
                        )
                    }
                }
            }
            out
        }
}
