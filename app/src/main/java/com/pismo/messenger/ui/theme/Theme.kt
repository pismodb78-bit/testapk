package com.pismo.messenger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Палитра повторяет ПК-версию (Discord-стиль): те же значения ARGB, что
 * захардкожены в WinForms-формах, чтобы Android и десктоп выглядели одним
 * продуктом.
 */
object PismoColors {
    val Blurple = Color(0xFF5865F2)
    val BlurpleDark = Color(0xFF4752C4)
    val BlurpleLight = Color(0xFF7983F5)

    val BgDarkest = Color(0xFF202225)   // рельс серверов, поля ввода
    val BgSidebar = Color(0xFF2F3136)   // список чатов
    val BgMain = Color(0xFF36393F)      // область сообщений
    val BgHover = Color(0xFF41444B)     // подсветка карточки
    val BgBubbleOther = Color(0xFF40444B)
    val BgElevated = Color(0xFF2B2D31)

    val TextPrimary = Color(0xFFDCDDDE)
    val TextSecondary = Color(0xFFB9BBBE)
    val TextMuted = Color(0xFF72767D)

    val Red = Color(0xFFF04747)
    val Green = Color(0xFF57AB5A)
    val Yellow = Color(0xFFFAA61A)
    val Cyan = Color(0xFF00B0F4)
    val Pink = Color(0xFFEB459E)

    val Online = Color(0xFF3BA55D)
    val Idle = Color(0xFFFAA81A)
    val Offline = Color(0xFF747F8D)

    val Divider = Color(0x1AFFFFFF)
}

private val DarkScheme = darkColorScheme(
    primary = PismoColors.Blurple,
    onPrimary = Color.White,
    primaryContainer = PismoColors.BlurpleDark,
    onPrimaryContainer = Color.White,
    secondary = PismoColors.Cyan,
    onSecondary = Color.White,
    background = PismoColors.BgMain,
    onBackground = PismoColors.TextPrimary,
    surface = PismoColors.BgSidebar,
    onSurface = PismoColors.TextPrimary,
    surfaceVariant = PismoColors.BgElevated,
    onSurfaceVariant = PismoColors.TextSecondary,
    error = PismoColors.Red,
    onError = Color.White,
    outline = PismoColors.TextMuted,
    outlineVariant = PismoColors.Divider,
)

/**
 * Светлой схемы у ПК-версии нет — она целиком тёмная. Держим ту же тёмную
 * палитру в обоих случаях, чтобы приложение не «разъезжалось» по виду в
 * зависимости от системной темы телефона.
 */
private val LightScheme = DarkScheme

private val PismoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val PismoTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun PismoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = PismoTypography,
        shapes = PismoShapes,
        content = content,
    )
}
