package com.pismo.messenger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.Prefs

/**
 * Набор цветов одной темы.
 *
 * Фирменные цвета (Blurple, статусы, семафор ошибок) в обеих темах ОДИНАКОВЫ
 * и берутся из ПК-версии: это опознавательные знаки продукта, и менять их от
 * системной темы телефона нельзя — иначе зелёная точка «в сети» на светлом
 * фоне перестанет читаться так же, как на компьютере.
 * Меняются только фоны, текст и разделители.
 */
data class PismoPalette(
    val bgDarkest: Color,
    val bgSidebar: Color,
    val bgMain: Color,
    val bgHover: Color,
    val bgBubbleOther: Color,
    val bgElevated: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val divider: Color,
    val isDark: Boolean,
)

/** Тёмная — та самая палитра ПК-версии, значение в значение. */
private val DarkPalette = PismoPalette(
    bgDarkest = Color(0xFF202225),
    bgSidebar = Color(0xFF2F3136),
    bgMain = Color(0xFF36393F),
    bgHover = Color(0xFF41444B),
    bgBubbleOther = Color(0xFF40444B),
    bgElevated = Color(0xFF2B2D31),
    textPrimary = Color(0xFFDCDDDE),
    textSecondary = Color(0xFFB9BBBE),
    textMuted = Color(0xFF72767D),
    divider = Color(0x1AFFFFFF),
    isDark = true,
)

/**
 * Светлая. У ПК-версии её нет, поэтому это не «порт», а подбор по тем же
 * ролям: самый тёмный слой становится самым светлым, и наоборот. Значения
 * взяты так, чтобы контраст текста к фону оставался читаемым (тёмно-серый
 * на белом, а не серый на сером).
 */
private val LightPalette = PismoPalette(
    bgDarkest = Color(0xFFE3E5E8),   // рельс серверов, поля ввода
    bgSidebar = Color(0xFFF2F3F5),   // списки
    bgMain = Color(0xFFFFFFFF),      // область сообщений
    bgHover = Color(0xFFE7E9EC),
    bgBubbleOther = Color(0xFFEDEFF2),
    bgElevated = Color(0xFFF7F8FA),
    textPrimary = Color(0xFF1E2124),
    textSecondary = Color(0xFF4E5058),
    textMuted = Color(0xFF80848E),
    divider = Color(0x1A000000),
    isDark = false,
)

/**
 * Цвета приложения.
 *
 * Остаётся объектом с теми же именами, что и раньше, — иначе пришлось бы
 * править под четыре сотни обращений в трёх десятках файлов. Тему меняет
 * подстановка палитры: [palette] — состояние Compose, поэтому чтение любого
 * поля во время композиции подписывает экран на смену темы, и она
 * применяется сразу, без перезапуска.
 */
object PismoColors {

    internal var palette by mutableStateOf(DarkPalette)

    /**
     * Цвет текста поверх пузыря сообщения.
     *
     * Свой пузырь всегда синий — на нём белый читается в любой теме. Чужой
     * красится фоном темы, и вот там белый текст на светлом фоне пропадал
     * начисто: имя файла и подпись голосового превращались в пустое место.
     */
    fun onBubble(isMine: Boolean): Color = if (isMine) Color.White else TextPrimary

    /**
     * Ставит палитру ДО первой отрисовки.
     *
     * Без этого светлая тема на холодном старте показывала бы один тёмный
     * кадр: SideEffect в PismoTheme срабатывает уже после композиции.
     * Здесь системную тему берём из конфигурации, а не из
     * isSystemInDarkTheme — до композиции его вызвать неоткуда.
     */
    fun initFrom(context: android.content.Context) {
        val systemDark = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        palette = when (ThemeMode.of(Prefs.themeModeName)) {
            ThemeMode.DARK -> DarkPalette
            ThemeMode.LIGHT -> LightPalette
            ThemeMode.SYSTEM -> if (systemDark) DarkPalette else LightPalette
        }
    }

    /**
     * Красит окно активити и системные панели под выбранную тему.
     *
     * В themes.xml зашит один тёмный цвет — при светлой теме без этого
     * остаётся тёмная подложка окна до первого кадра и тёмная строка
     * состояния поверх светлого экрана.
     */
    fun applyToWindow(activity: android.app.Activity) {
        val p = palette
        runCatching {
            activity.window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(p.bgMain.toArgb())
            )
            activity.window.statusBarColor = p.bgDarkest.toArgb()
            activity.window.navigationBarColor = p.bgDarkest.toArgb()

            // Тёмные значки строки состояния нужны как раз на светлом фоне.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val c = activity.window.insetsController
                val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                c?.setSystemBarsAppearance(if (p.isDark) 0 else mask, mask)
            } else {
                @Suppress("DEPRECATION")
                activity.window.decorView.systemUiVisibility =
                    if (p.isDark) 0
                    else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    /** true — сейчас тёмная тема. Нужно там, где выбор цвета зависит от неё. */
    val isDark: Boolean get() = palette.isDark

    // ── Фирменные, одинаковые в обеих темах ──
    val Blurple = Color(0xFF5865F2)
    val BlurpleDark = Color(0xFF4752C4)
    val BlurpleLight = Color(0xFF7983F5)

    val Red = Color(0xFFF04747)
    val Green = Color(0xFF57AB5A)
    val Yellow = Color(0xFFFAA61A)
    val Cyan = Color(0xFF00B0F4)
    val Pink = Color(0xFFEB459E)

    val Online = Color(0xFF3BA55D)
    val Idle = Color(0xFFFAA81A)
    val Offline = Color(0xFF747F8D)

    // ── Зависят от темы ──
    val BgDarkest: Color get() = palette.bgDarkest
    val BgSidebar: Color get() = palette.bgSidebar
    val BgMain: Color get() = palette.bgMain
    val BgHover: Color get() = palette.bgHover
    val BgBubbleOther: Color get() = palette.bgBubbleOther
    val BgElevated: Color get() = palette.bgElevated

    val TextPrimary: Color get() = palette.textPrimary
    val TextSecondary: Color get() = palette.textSecondary
    val TextMuted: Color get() = palette.textMuted

    val Divider: Color get() = palette.divider
}

/** Как выбирать тему: следовать системе или задать вручную. */
enum class ThemeMode(val stored: String) {
    SYSTEM("system"), DARK("dark"), LIGHT("light");

    companion object {
        fun of(stored: String?): ThemeMode =
            entries.firstOrNull { it.stored == stored } ?: SYSTEM
    }
}

/** Текущий режим из настроек. */
val themeMode: ThemeMode get() = ThemeMode.of(Prefs.themeModeName)

private fun schemeFor(p: PismoPalette) = if (p.isDark) {
    darkColorScheme(
        primary = PismoColors.Blurple,
        onPrimary = Color.White,
        primaryContainer = PismoColors.BlurpleDark,
        onPrimaryContainer = Color.White,
        secondary = PismoColors.Cyan,
        onSecondary = Color.White,
        background = p.bgMain,
        onBackground = p.textPrimary,
        surface = p.bgSidebar,
        onSurface = p.textPrimary,
        surfaceVariant = p.bgElevated,
        onSurfaceVariant = p.textSecondary,
        error = PismoColors.Red,
        onError = Color.White,
        outline = p.textMuted,
        outlineVariant = p.divider,
    )
} else {
    lightColorScheme(
        primary = PismoColors.Blurple,
        onPrimary = Color.White,
        primaryContainer = PismoColors.BlurpleLight,
        onPrimaryContainer = Color.White,
        secondary = PismoColors.Cyan,
        onSecondary = Color.White,
        background = p.bgMain,
        onBackground = p.textPrimary,
        surface = p.bgSidebar,
        onSurface = p.textPrimary,
        surfaceVariant = p.bgElevated,
        onSurfaceVariant = p.textSecondary,
        error = PismoColors.Red,
        onError = Color.White,
        outline = p.textMuted,
        outlineVariant = p.divider,
    )
}

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
fun PismoTheme(content: @Composable () -> Unit) {
    val system = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> system
    }
    val target = if (dark) DarkPalette else LightPalette

    // Подстановку делаем в SideEffect, а не прямо в теле: запись состояния
    // во время композиции — ошибка, Compose честно ругается на неё.
    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect {
        PismoColors.palette = target
        (view.context as? android.app.Activity)?.let { PismoColors.applyToWindow(it) }
    }

    MaterialTheme(
        colorScheme = schemeFor(target),
        typography = PismoTypography,
        shapes = PismoShapes,
        content = content,
    )
}
