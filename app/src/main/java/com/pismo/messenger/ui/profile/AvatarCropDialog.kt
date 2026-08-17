package com.pismo.messenger.ui.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Итоговая сторона аватара — та же, что OUT в AvatarCropForm.cs. */
private const val OUT_SIZE = 256

/** Верхняя граница стороны исходника в памяти: 12 Мп фото иначе кладут процесс. */
private const val MAX_SOURCE = 1600

/**
 * Обрезка аватара кружком — Android-аналог AvatarCropForm.
 *
 * Жест вместо мыши и колеса: одним пальцем двигаем, щипком масштабируем,
 * ползунок дублирует зум. На выходе, как и на ПК, квадратный PNG 256×256 —
 * важно, чтобы аватарки с телефона и с компьютера весили одинаково: они
 * тянутся из БД при каждой отрисовке списка, у всех участников сразу.
 */
@Composable
fun AvatarCropDialog(
    uri: Uri,
    onCancel: () -> Unit,
    onDone: (ByteArray) -> Unit,
) {
    val context = LocalContext.current

    var source by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // Геометрия в пикселях области предпросмотра: scale — во сколько раз
    // пиксель картинки крупнее пикселя экрана, offset — где лежит её левый
    // верхний угол. Так же считает ПК-версия.
    var viewSize by remember { mutableStateOf(0) }
    var scale by remember(uri) { mutableStateOf(1f) }
    var minScale by remember(uri) { mutableStateOf(1f) }
    var offX by remember(uri) { mutableStateOf(0f) }
    var offY by remember(uri) { mutableStateOf(0f) }

    LaunchedEffect(uri) {
        source = withContext(Dispatchers.IO) { decodeScaled(context, uri) }
        failed = source == null
    }

    fun clamp() {
        val bmp = source ?: return
        if (viewSize <= 0) return
        val w = bmp.width * scale
        val h = bmp.height * scale
        // Картинка обязана перекрывать всю область: иначе в кружок попадут
        // прозрачные поля, и аватар получится с «дырой» по краю.
        offX = offX.coerceIn(min(viewSize - w, 0f), max(viewSize - w, 0f).coerceAtLeast(0f))
        offY = offY.coerceIn(min(viewSize - h, 0f), max(viewSize - h, 0f).coerceAtLeast(0f))
    }

    fun fit(bmp: Bitmap, size: Int) {
        minScale = max(size.toFloat() / bmp.width, size.toFloat() / bmp.height)
        scale = minScale
        offX = (size - bmp.width * scale) / 2f
        offY = (size - bmp.height * scale) / 2f
    }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = PismoColors.BgSidebar,
        title = { Text("Обрезка аватара", color = Color.White) },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(PismoColors.BgDarkest)
                        .onSizeChanged { s ->
                            if (s.width != viewSize) {
                                viewSize = s.width
                                source?.let { fit(it, viewSize) }
                            }
                        }
                        .pointerInput(source) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val bmp = source ?: return@detectTransformGestures
                                val old = scale
                                scale = (scale * zoom).coerceIn(minScale, minScale * 5f)
                                // Зум к центру области — иначе картинка
                                // «убегает» из-под пальцев при масштабировании.
                                val c = viewSize / 2f
                                offX = c - (c - offX) * (scale / old) + pan.x
                                offY = c - (c - offY) * (scale / old) + pan.y
                                if (bmp.width > 0) clamp()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = source
                    when {
                        failed -> Text(
                            "Не удалось открыть изображение",
                            color = PismoColors.TextMuted, fontSize = 13.sp,
                        )
                        bmp == null -> CircularProgressIndicator(color = PismoColors.Blurple)
                        else -> Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.None,
                            modifier = Modifier.graphicsLayer {
                                // transformOrigin по умолчанию — центр, а нам
                                // нужен угол: вся математика выше в координатах
                                // «левый верхний угол картинки».
                                transformOrigin = androidx.compose.ui.graphics
                                    .TransformOrigin(0f, 0f)
                                scaleX = scale
                                scaleY = scale
                                translationX = offX
                                translationY = offY
                            },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("Масштаб", color = PismoColors.TextMuted, fontSize = 12.sp)
                Slider(
                    value = if (minScale > 0f) scale / minScale else 1f,
                    onValueChange = { v ->
                        val old = scale
                        scale = (minScale * v).coerceIn(minScale, minScale * 5f)
                        val c = viewSize / 2f
                        offX = c - (c - offX) * (scale / old)
                        offY = c - (c - offY) * (scale / old)
                        clamp()
                    },
                    valueRange = 1f..5f,
                    enabled = source != null,
                )
                Text(
                    "Двигайте пальцем, щипком меняйте масштаб.",
                    color = PismoColors.TextMuted, fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = source != null && !busy,
                onClick = {
                    val bmp = source ?: return@TextButton
                    busy = true
                    val png = renderCrop(bmp, viewSize, scale, offX, offY)
                    busy = false
                    if (png != null) onDone(png) else onCancel()
                },
            ) { Text("Готово", color = PismoColors.Cyan) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Отмена", color = PismoColors.TextMuted) }
        },
    )
}

/**
 * Читает картинку с уменьшением на лету. Без inSampleSize снимок с камеры
 * на 50 Мп разворачивается в ~200 МБ и валит приложение ещё до обрезки.
 */
private fun decodeScaled(context: Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    var sample = 1
    while (bounds.outWidth / sample > MAX_SOURCE || bounds.outHeight / sample > MAX_SOURCE) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    }
}.getOrNull()

/**
 * Переводит область предпросмотра обратно в координаты исходника и рисует
 * квадрат 256×256. Кружком картинка становится при ОТРИСОВКЕ — в БД, как и
 * на ПК, лежит квадрат: так аватар можно показать и квадратным.
 */
private fun renderCrop(
    source: Bitmap,
    viewSize: Int,
    scale: Float,
    offX: Float,
    offY: Float,
): ByteArray? = runCatching {
    if (viewSize <= 0 || scale <= 0f) return@runCatching null

    val srcX = (-offX / scale)
    val srcY = (-offY / scale)
    val srcSide = viewSize / scale

    val src = Rect(
        srcX.toInt().coerceIn(0, source.width),
        srcY.toInt().coerceIn(0, source.height),
        (srcX + srcSide).toInt().coerceIn(0, source.width),
        (srcY + srcSide).toInt().coerceIn(0, source.height),
    )
    if (src.width() <= 0 || src.height() <= 0) return@runCatching null

    val out = Bitmap.createBitmap(OUT_SIZE, OUT_SIZE, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(
        source, src, RectF(0f, 0f, OUT_SIZE.toFloat(), OUT_SIZE.toFloat()),
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
    )

    ByteArrayOutputStream().use { bos ->
        out.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bos.toByteArray()
    }
}.getOrNull()
