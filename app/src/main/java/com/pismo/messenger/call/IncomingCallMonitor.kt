package com.pismo.messenger.call

import android.content.Context
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.model.CallSessionRow
import com.pismo.messenger.data.repo.CallRepository
import com.pismo.messenger.net.SignalingClient
import com.pismo.messenger.service.CallNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Слежение за входящими звонками — порт CheckIncomingCalls из
 * MainForm_MessageActions.cs.
 *
 * ПОЧЕМУ ЭТО НЕ COMPOSABLE, как было раньше.
 *
 * Прошлая версия жила внутри HomeScreen, и как только пользователь заходил
 * в переписку (другой destination навигации), слежение попросту переставало
 * существовать вместе со своим состоянием. Звонок с ПК в личку в этот
 * момент не показывался вообще ничем. Теперь монитор — синглтон уровня
 * приложения: он жив, пока жив процесс, и одинаково работает и на экране
 * чата, и когда приложение свёрнуто.
 *
 * Фильтр «уже показанных» — множеством id, а не условием `id > последний`.
 * На ПК от «id > last» отказались ровно потому, что часть звонков при нём
 * терялась (комментарий в CheckIncomingCalls: «ненадёжный фильтр»), и
 * здесь была та же ошибка.
 */
object IncomingCallMonitor {

    private val shownCallIds = HashSet<Int>()

    private val _incoming = MutableStateFlow<CallSessionRow?>(null)

    /** Текущий показываемый входящий звонок, либо null. */
    val incoming: StateFlow<CallSessionRow?> = _incoming.asStateFlow()

    private var job: Job? = null
    private var pollSkip = 0

    @Volatile private var pushed = false
    @Volatile private var busy = false

    /** true, пока пользователь внутри CallActivity — второй звонок не показываем. */
    @Volatile var inCall: Boolean = false

    fun start(context: Context) {
        if (job?.isActive == true) return

        val appContext = context.applicationContext
        job = CoroutineScope(Dispatchers.IO).launch {
            // Стартовая отметка: всё, что уже звонит на момент запуска,
            // помечаем показанным. Иначе после входа в приложение всплывёт
            // окно давно брошенного звонка.
            runCatching {
                CallRepository.incomingCalls().forEach { shownCallIds.add(it.id) }
            }

            SignalingClient.addListener { type, _, session, payload ->
                if (type == "incoming_call") pushed = true
                if (type == "call_status") {
                    pushed = true
                    // Гасим показанный звонок СРАЗУ, а не ждём опроса. Опрос при
                    // живом сокете разрежен до ~6 секунд, и всё это время телефон
                    // продолжал звонить после того, как трубку взяли на ПК.
                    // Любой статус, кроме «звонит», означает, что вызов уже не наш:
                    // отменили, отклонили — или ответили с другого своего устройства.
                    val cur = _incoming.value
                    if (cur != null && cur.id == session && payload != "ringing") {
                        _incoming.value = null
                        CallNotifier.cancelIncoming(appContext)
                    }
                }
            }

            while (isActive) {
                delay(1500)
                runCatching { tick(appContext) }
            }
        }
    }

    private suspend fun tick(context: Context) {
        if (busy) return

        // При живом ws входящий приходит push'ем, опрос базы — только
        // страховка, поэтому прореживаем его до ~6 секунд. Без ws — полный
        // темп в 1.5 секунды. Ровно так же считает ПК.
        val wasPushed = pushed
        pushed = false
        if (!wasPushed && SignalingClient.isConnected && (++pollSkip % 4) != 0) return

        busy = true
        try {
            // Показанный звонок мог быть отменён с той стороны — тогда окно
            // надо убрать, а не держать до бесконечности.
            val current = _incoming.value
            if (current != null) {
                val status = runCatching { CallRepository.status(current.id) }.getOrDefault("")
                if (status != "ringing") {
                    _incoming.value = null
                    CallNotifier.cancelIncoming(context)
                }
                return
            }
            if (inCall) return

            val calls = runCatching { CallRepository.incomingCalls() }.getOrDefault(emptyList())
            val fresh = calls.firstOrNull { shownCallIds.add(it.id) } ?: return

            // Игнорируемый звонит — окно не поднимаем и в шторку не пишем.
            // Звонок при этом не отклоняем: пусть у него идут гудки, как
            // будто трубку просто не берут. Порт того же поведения с ПК.
            if (Prefs.isUserIgnored(fresh.callerId)) return

            _incoming.value = fresh
            CallNotifier.showIncoming(context, fresh)
        } finally {
            busy = false
        }
    }

    /** Пользователь принял звонок (из окна или из уведомления). */
    fun accepted(context: Context, call: CallSessionRow) {
        _incoming.value = null
        CallNotifier.cancelIncoming(context)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { CallRepository.accept(call.id) }
            SignalingClient.send("call_status", call.callerId, call.id, "active")
            // И СВОИМ остальным устройствам: там сейчас звонит такой же входящий,
            // и без этого он продолжал бы звонить после ответа здесь.
            SignalingClient.send("call_status", UserSession.effectiveId, call.id, "active")
        }
    }

    /** Пользователь отклонил звонок. */
    fun rejected(context: Context, call: CallSessionRow) {
        _incoming.value = null
        CallNotifier.cancelIncoming(context)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { CallRepository.reject(call.id) }
            SignalingClient.send("call_status", call.callerId, call.id, "rejected")
            SignalingClient.send("call_status", UserSession.effectiveId, call.id, "rejected")
        }
    }

    /** Сброс при выходе из аккаунта: чужие звонки нас больше не касаются. */
    fun reset() {
        shownCallIds.clear()
        _incoming.value = null
    }

    @Suppress("unused")
    private val me: Int get() = UserSession.effectiveId
}
