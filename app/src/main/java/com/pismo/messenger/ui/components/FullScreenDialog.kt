package com.pismo.messenger.ui.components

import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Делает окно диалога ЧЕСТНО полноэкранным. Вызывать первой строкой
 * внутри Dialog { … }.
 *
 * ЗАЧЕМ. Полноэкранный диалог Compose на части телефонов ставится НИЖЕ
 * строки состояния, а высоту при этом получает во весь экран. Его низ
 * физически уезжает за нижний край — на высоту той самой строки. Отсюда и
 * «кнопки за границей экрана» в просмотре и редакторе: сколько ни поднимай
 * их отступами, окно само было больше экрана.
 *
 * Здесь окно растягивается от самого верха экрана до самого низа, а
 * подгонку под системные панели берёт на себя разметка — и отступы
 * строки состояния и жестовой черты начинают приходить настоящими.
 */
@Composable
fun FullScreenDialogWindow() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.setGravity(Gravity.TOP or Gravity.START)
        // Раскладка относительно ВСЕГО экрана, а не области под строкой
        // состояния: только так верх окна совпадает с верхом экрана.
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // И В ОБЛАСТЬ ВЫРЕЗА КАМЕРЫ ТОЖЕ. Без этого в альбомной ориентации
        // окно начиналось правее выреза, а шириной оставалось во весь экран:
        // слева просвечивала переписка, а правый край с кнопками уезжал за
        // экран. От выреза отступает уже сама разметка.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val attrs = window.attributes
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (attrs.layoutInDisplayCutoutMode != mode) {
                attrs.layoutInDisplayCutoutMode = mode
                window.attributes = attrs
            }
        }
        // Системные панели — прозрачные поверх чёрного фона просмотра.
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        window.setDimAmount(0f)
    }
}
