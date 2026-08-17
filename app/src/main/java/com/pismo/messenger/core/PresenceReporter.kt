package com.pismo.messenger.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.pismo.messenger.data.repo.PresenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Heartbeat присутствия — порт PresenceTick из MainForm_Presence.cs.
 *
 * ЧТО БЫЛО НЕ ТАК. Раньше `heartbeat(active = true)` вызывался ровно из
 * одного места — списка чатов. Стоило открыть переписку, перейти на
 * «Друзья», «Серверы» или зайти в звонок, и last_active переставал
 * обновляться: через 90 секунд ПК честно показывал «бездействует», хотя
 * человек в этот момент разговаривал.
 *
 * ЧТО ТАКОЕ «АКТИВЕН» НА ТЕЛЕФОНЕ. На ПК активность меряется системным
 * простоем ввода (GetLastInputInfo): не двигал мышь минуту — бездействует.
 * Такого API у приложения на Android нет и быть не может — оно не видит
 * ввод в чужих окнах. Ближайший честный аналог: приложение открыто на
 * экране ЛИБО идёт звонок. Свёрнутое приложение шлёт только last_seen —
 * это ровно то же, что делает ПК при простое: «в сети, но бездействует».
 */
object PresenceReporter {

    /** Тот же период, что у _presenceTimer на ПК. */
    private const val TICK_MS = 6000L

    private var job: Job? = null

    /** Сколько активити сейчас на экране. Больше нуля — приложение видно. */
    @Volatile private var startedActivities = 0

    /** Взводится звонком: разговор — это активность, даже со свёрнутым окном. */
    @Volatile var inCall: Boolean = false

    val isForeground: Boolean get() = startedActivities > 0

    fun start(app: Application) {
        if (job?.isActive == true) return

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                if (UserSession.effectiveId > 0) {
                    runCatching { PresenceRepository.heartbeat(active = isForeground || inCall) }
                }
                delay(TICK_MS)
            }
        }
    }

    /** Выход из аккаунта: перестаём отмечаться, чтобы не «висеть в сети». */
    fun stop() {
        job?.cancel()
        job = null
        inCall = false
    }
}
