package com.pismo.messenger.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Снимок с камеры — одним нажатием из переписки.
 *
 * ЗАЧЕМ. Отправить только что снятое фото можно было лишь кружным путём:
 * выйти в камеру, снять, вернуться, нажать «прикрепить», найти снимок среди
 * файлов. Ровно за этим в мессенджерах и держат кнопку камеры рядом с полем
 * ввода.
 *
 * Снимаем СИСТЕМНОЙ камерой, а не своей. У телефона она своя, привычная, со
 * вспышкой, сеткой и всеми режимами; писать её заново значило бы сделать
 * хуже. Нам остаётся отдать ей временный файл и забрать снимок.
 *
 * Снимок НЕ уходит сразу: он прикрепляется к сообщению, как файл из
 * проводника. Так к нему можно дописать подпись и уйдёт всё одним
 * сообщением — иначе выходит два, как было на ПК до общей правки.
 */
@Composable
fun rememberCameraShot(onPhoto: (bytes: ByteArray, name: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shot = remember { mutableStateOf<File?>(null) }

    val capture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val file = shot.value
        shot.value = null
        if (file == null) return@rememberLauncherForActivityResult
        if (!ok) { runCatching { file.delete() }; return@rememberLauncherForActivityResult }
        scope.launch {
            val photo = withContext(Dispatchers.IO) { prepare(file) }
            runCatching { file.delete() }
            if (photo != null) onPhoto(photo.first, photo.second)
        }
    }

    fun shoot() {
        val file = newShotFile(context) ?: return
        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }.getOrNull() ?: return
        shot.value = file
        runCatching { capture.launch(uri) }
    }

    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) shoot() }

    return {
        // Разрешение спрашиваем в момент нажатия, а не на старте.
        //
        // Оно у приложения объявлено ради звонков, и Android в таком случае
        // ТРЕБУЕТ его и для съёмки через системную камеру: без выданного
        // разрешения снимок просто не вернётся, молча.
        val has = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (has) shoot() else runCatching { ask.launch(Manifest.permission.CAMERA) }
    }
}

private fun newShotFile(context: Context): File? = runCatching {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    File(dir, "IMG_" + System.currentTimeMillis() + ".jpg")
}.getOrNull()

/** Длинная сторона снимка после уменьшения. */
private const val MAX_SIDE = 2048

/**
 * Готовит снимок к отправке: поворачивает и уменьшает.
 *
 * ПОВОРОТ. Камера почти всегда пишет кадр как есть, а «верх» отмечает
 * строчкой EXIF. Эту строчку читают галереи — но не BitmapFactory и не
 * ПК-клиент, поэтому снятое вертикально приходило к собеседнику лежащим на
 * боку. Поворачиваем сами и отдаём уже правильный кадр.
 *
 * РАЗМЕР. Двенадцать мегапикселей — это шесть-восемь мегабайт, которые по
 * мобильной связи идут минуту, а в переписке всё равно показываются
 * размером с ладонь. Уменьшаем до 2048 по длинной стороне: на экране
 * разницы не видно, а отправка становится мгновенной. Снимок целиком, без
 * потерь, по-прежнему можно отправить через «прикрепить» — там файл идёт
 * как есть.
 */
private fun prepare(file: File): Pair<ByteArray, String>? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return@runCatching null

    // Грубое уменьшение при чтении: полный кадр в память не поднимаем вовсе.
    var sample = 1
    while (longest / sample > MAX_SIDE * 2) sample *= 2

    var bmp: Bitmap = BitmapFactory.decodeFile(
        file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return@runCatching null

    val degrees = when (
        runCatching {
            @Suppress("DEPRECATION")
            android.media.ExifInterface(file.absolutePath).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
    ) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }

    val side = maxOf(bmp.width, bmp.height)
    val scale = if (side > MAX_SIDE) MAX_SIDE.toFloat() / side else 1f
    if (degrees != 0f || scale < 1f) {
        val m = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (degrees != 0f) postRotate(degrees)
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) { runCatching { bmp.recycle() }; bmp = out }
    }

    val stream = java.io.ByteArrayOutputStream(512 * 1024)
    bmp.compress(Bitmap.CompressFormat.JPEG, 90, stream)
    runCatching { bmp.recycle() }
    val bytes = stream.toByteArray()
    if (bytes.isEmpty()) null else bytes to file.name
}.getOrNull()
