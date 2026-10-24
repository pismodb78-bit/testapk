package com.pismo.messenger.ui.chat

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Прыжок к элементу ленты так, чтобы он оказался ПОСЕРЕДИНЕ экрана.
 *
 * scrollToItem ставит элемент к КРАЮ окна (в перевёрнутой ленте — к нижнему),
 * поэтому «показать в чате» и переходы по цитате промахивались: высокий пузырь
 * с фото уезжал за границу экрана.
 *
 * Одного прохода мало: высота пузыря меняется уже после прокрутки — картинка
 * доезжает из сети или из кэша, у видео появляется превью. Поэтому доводим
 * позицию несколько раз, пока лента не устаканится.
 */
suspend fun LazyListState.jumpToCenter(index: Int) {
    if (index < 0) return
    // Элемент рядом — доедем плавно, издалека прыгаем сразу: анимация через
    // сотни пузырей всё равно превращается в мельтешение.
    val near = layoutInfo.visibleItemsInfo.any { it.index == index }
    if (!near) scrollToItem(index)
    repeat(6) { step ->
        val info = layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null) {
            scrollToItem(index)
        } else {
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            val want = info.viewportStartOffset + (viewport - item.size) / 2
            val delta = (item.offset - want).toFloat()
            if (abs(delta) > 2f) {
                if (step == 0 && near) animateScrollBy(delta) else scrollBy(delta)
            }
        }
        delay(if (step < 2) 32L else 140L)
    }
}
