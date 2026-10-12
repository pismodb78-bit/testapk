package com.pismo.messenger.ui.components

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity

/**
 * Удерживает ленту на месте, когда появляется и исчезает клавиатура.
 *
 * Строка ввода поднимается над клавиатурой (imePadding), и область ленты на
 * столько же укорачивается снизу. Но лента держится за ВЕРХ: её первый
 * видимый элемент остаётся на месте, а всё, что было внизу, уезжает под
 * клавиатуру. Получалось дико: отвечаешь человеку — и перестаёшь видеть то,
 * что он написал.
 *
 * Прокручиваем ленту ровно на столько, на сколько укоротилась область. Низ
 * остаётся там же, где был, и при закрытии клавиатуры возвращается обратно.
 * Значение меняется кадрами во время анимации клавиатуры, поэтому лента едет
 * вместе с ней, а не прыгает в конце.
 */
@Composable
fun KeepListAboveKeyboard(state: LazyListState) {
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    var last by remember { mutableIntStateOf(imeBottom) }

    LaunchedEffect(imeBottom) {
        val delta = imeBottom - last
        last = imeBottom
        if (delta != 0) runCatching { state.scrollBy(delta.toFloat()) }
    }
}
