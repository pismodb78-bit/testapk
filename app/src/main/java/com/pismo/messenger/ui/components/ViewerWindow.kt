package com.pismo.messenger.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Activity за контекстом: у диалога он обёрнут в тему, и не один раз. */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Строка состояния и жестовая черта у окна просмотра — прячутся вместе с
 * его интерфейсом. Убрали кнопки ради кадра — пусть и часы со значками не
 * висят поверх него. Вернуть их можно свайпом от края, как в любом плеере.
 * Звать ВНУТРИ содержимого Dialog: окно берётся у него.
 */
@Composable
fun DialogSystemBars(hidden: Boolean) {
    val view = LocalView.current
    LaunchedEffect(hidden) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        runCatching {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hidden) c.hide(WindowInsetsCompat.Type.systemBars())
            else c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * Альбомный режим «для кино»: поворот по кнопке, пока открыт просмотр.
 * Закрыли просмотр — ориентация возвращается той, что была.
 */
class LandscapeToggle internal constructor(private val activity: Activity?) {
    private val original = activity?.requestedOrientation
        ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    var landscape by mutableStateOf(false)
        private set

    fun toggle() {
        landscape = !landscape
        runCatching {
            activity?.requestedOrientation =
                if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else original
        }
    }

    internal fun restore() {
        if (!landscape) return
        landscape = false
        runCatching { activity?.requestedOrientation = original }
    }
}

@Composable
fun rememberLandscapeToggle(): LandscapeToggle {
    val activity = LocalContext.current.findActivity()
    val toggle = remember(activity) { LandscapeToggle(activity) }
    DisposableEffect(toggle) { onDispose { toggle.restore() } }
    return toggle
}
