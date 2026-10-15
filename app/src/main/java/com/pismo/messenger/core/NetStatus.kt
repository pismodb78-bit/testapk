package com.pismo.messenger.core

import com.pismo.messenger.data.db.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.atomic.AtomicInteger

/**
 * Состояние связи ОДНОЙ строкой в шапке — вместо заставки во весь экран.
 *
 * Раньше пропажа связи поднимала панель «Нет связи с сервером», которая
 * перекрывала приложение целиком и глотала касания. На телефоне связь
 * пропадает постоянно — лифт, метро, смена вышки, выход из фона с уснувшим
 * сокетом, — и каждый такой пустяк превращался в запертый экран: нельзя ни
 * перечитать переписку, которая и так уже загружена, ни уйти в другой чат.
 *
 * Мессенджеры делают иначе: заголовок меняется на «Соединение…», а всё
 * остальное работает. Связь вернулась — заголовок снова свой. Здесь так же:
 *
 *     Соединение…   связи с базой нет, идут попытки
 *     Обновление…   связь есть, данные перечитываются
 *     PISMO         всё в порядке
 *
 * ЗАДЕРЖКА ПЕРЕД «СОЕДИНЕНИЕМ». Мелькнувший обрыв — не новость, о которой
 * стоит сообщать: после возврата из фона первый запрос часто падает на
 * уснувшем сокете и тут же проходит на новом. Поэтому «Соединение…»
 * показывается, только если связи нет дольше [GRACE_MS]; иначе заголовок
 * не дёргается вовсе.
 */
object NetStatus {

    enum class State { READY, CONNECTING, UPDATING }

    /** Сколько терпим обрыв, прежде чем сказать о нём человеку. */
    private const val GRACE_MS = 1200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val running = AtomicInteger(0)
    private val _syncing = MutableStateFlow(0)

    /**
     * Экран перечитывает данные. Считаем именно СЧЁТЧИКОМ, а не флагом:
     * загрузок бывает несколько разом, и та, что закончилась первой, не
     * должна гасить надпись, пока идут остальные.
     */
    fun beginSync() {
        _syncing.value = running.incrementAndGet()
    }

    fun endSync() {
        _syncing.value = running.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /** Обёртка на время загрузки — чтобы не забыть про [endSync] на ошибке. */
    suspend fun <T> sync(block: suspend () -> T): T {
        beginSync()
        try {
            return block()
        } finally {
            endSync()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<State> =
        combine(Db.online, _syncing) { online, busy -> online to busy }
            .flatMapLatest { (online, busy) ->
                flow {
                    if (!online) {
                        // Пауза без emit: пока она идёт, в шапке остаётся
                        // прежняя надпись. Связь вернулась раньше — сюда
                        // просто не дойдём, и человек ничего не заметит.
                        delay(GRACE_MS)
                        emit(State.CONNECTING)
                    } else {
                        emit(if (busy > 0) State.UPDATING else State.READY)
                    }
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, State.READY)

    /** Надпись для шапки или null, если всё в порядке. */
    fun label(s: State): String? = when (s) {
        State.CONNECTING -> "Соединение…"
        State.UPDATING -> "Обновление…"
        State.READY -> null
    }
}
