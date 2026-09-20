package com.pismo.messenger.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Палитра. Те же цвета, что у пузырей на ПК и на телефоне, — переписка
 * должна выглядеть одинаково, с какого бы клиента человек ни смотрел.
 */
object PismoPalette {
    val Accent = Color(0xFF5865F2)      // мои сообщения
    val Bubble = Color(0xFF30333A)      // чужие сообщения
    val Surface = Color(0xFF2B2D31)     // боковая панель
    val Background = Color(0xFF1E1F22)  // полотно переписки
    val OnMuted = Color(0xFF8C939F)     // время, подписи
    val Divider = Color(0x33FFFFFF)
}

@Composable
fun PismoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PismoPalette.Accent,
            onPrimary = Color.White,
            surface = PismoPalette.Surface,
            onSurface = Color(0xFFDDE1E7),
            surfaceVariant = PismoPalette.Bubble,
            onSurfaceVariant = Color(0xFFDDE1E7),
            background = PismoPalette.Background,
            onBackground = Color(0xFFDDE1E7),
            outline = PismoPalette.OnMuted,
        ),
        content = content,
    )
}
