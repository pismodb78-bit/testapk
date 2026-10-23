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

/**
 * Готовит снимок к отправке.
 *
 * КАДР УХОДИТ КАК ЕСТЬ. Ни уменьшения, ни пережатия: что сняла камера, то
 * и получит собеседник — полное разрешение и родной файл камеры, байт в
 * байт. Поэтому в обычном случае здесь вообще ничего не происходит, только
 * чтение файла.
 *
 * ЕДИНСТВЕННОЕ ИСКЛЮЧЕНИЕ — ПОВОРОТ. Камера часто пишет кадр так, как его
 * видит матрица, а «верх» отмечает строчкой EXIF. Эту строчку читают
 * галереи, но не Android-декодер в переписке и не ПК-клиент: снятое
 * вертикально пришло бы собеседнику лежащим на боку. Повернуть кадр,
 * не пересобрав его, нельзя — поэтому только в этом случае он
 * раскодируется и сжимается заново, с качеством 100 и БЕЗ изменения
 * разрешения. Если кадр и так стоит правильно (а это обычное дело), файл
 * остаётся нетронутым.
 *
 * Не хватило памяти на поворот — отдаём исходный файл: лучше кадр на боку,
 * чем закрывшееся приложение.
 */
private fun prepare(file: File): Pair<ByteArray, String>? = runCatching {
    val raw = file.readBytes()
    if (raw.isEmpty()) return@runCatching null

    val degrees = exifDegrees(file)
    if (degrees == 0f) {
        // Кадр уходит байт в байт. toPortableImage здесь — страховка, а не
        // обработка: JPEG она возвращает нетронутым и вмешивается только
        // если камера отдала формат, который ПК-клиент не покажет (HEIC с
        // «высокой эффективностью»). Тогда выбор не между качеством и
        // размером, а между картинкой и ничем.
        return@runCatching com.pismo.messenger.core.toPortableImage(raw, file.name)
    }

    (rotated(raw, degrees) ?: raw) to file.name
}.getOrNull()

/** Насколько повёрнут кадр по метке камеры. 0 — стоит правильно. */
private fun exifDegrees(file: File): Float = runCatching {
    @Suppress("DEPRECATION")
    val exif = android.media.ExifInterface(file.absolutePath)
    when (
        exif.getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
}.getOrDefault(0f)

/**
 * Тот же кадр, повёрнутый как надо. Разрешение прежнее, качество 100.
 * null — не вышло (чаще всего не хватило памяти на большой снимок).
 */
private fun rotated(raw: ByteArray, degrees: Float): ByteArray? = try {
    val bmp: Bitmap? = BitmapFactory.decodeByteArray(raw, 0, raw.size)
    if (bmp == null) null else {
        val turned = Bitmap.createBitmap(
            bmp, 0, 0, bmp.width, bmp.height,
            Matrix().apply { postRotate(degrees) },
            true,
        )
        if (turned !== bmp) runCatching { bmp.recycle() }
        val stream = java.io.ByteArrayOutputStream(raw.size)
        turned.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        runCatching { turned.recycle() }
        stream.toByteArray().takeIf { it.isNotEmpty() }
    }
} catch (e: OutOfMemoryError) {
    null
} catch (e: Throwable) {
    null
}
