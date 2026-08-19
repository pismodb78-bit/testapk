package com.pismo.messenger.ui.components

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import java.nio.ByteBuffer

/**
 * Анимированная картинка (GIF) — то, что на ПК делает IsGif + ImageAnimator.
 *
 * Coil без отдельного модуля coil-gif показывает только первый кадр, а
 * добавить его в проект нельзя: репозиторий Google отсюда недоступен, и
 * зависимость просто не скачается. Поэтому анимация собирается средствами
 * самой системы.
 *
 * ImageDecoder с AnimatedImageDrawable — это API 28. Ниже остаётся статичный
 * первый кадр, и это осознанный предел: единственная альтернатива для более
 * старых версий — устаревший Movie с ручной отрисовкой в onDraw, а
 * поддерживать целый собственный проигрыватель кадров ради Android 7 не
 * стоит того.
 */
@Composable
fun AnimatedImage(
    bytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        AsyncImage(model = bytes, contentDescription = contentDescription, modifier = modifier)
        return
    }

    var drawable by remember(bytes) { mutableStateOf<AnimatedImageDrawable?>(null) }

    DisposableEffect(bytes) {
        val decoded = runCatching {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ImageDecoder.decodeDrawable(source) as? AnimatedImageDrawable
        }.getOrNull()
        drawable = decoded
        runCatching { decoded?.start() }
        onDispose {
            // Остановить обязательно: брошенная анимация продолжает
            // перерисовываться и держать кадры в памяти, а в ленте таких
            // картинок может быть десяток.
            runCatching { drawable?.stop() }
            drawable = null
        }
    }

    val d = drawable
    if (d == null) {
        // Не GIF или декодер не справился — показываем как обычную картинку.
        AsyncImage(model = bytes, contentDescription = contentDescription, modifier = modifier)
        return
    }

    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                // Фокус ImageView не нужен, а забранный им фокус заставляет
                // ленту чата подтягивать пузырь в видимую область.
                isFocusable = false
                isFocusableInTouchMode = false
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(d)
            }
        },
        update = { it.setImageDrawable(d) },
        modifier = modifier,
    )
}
