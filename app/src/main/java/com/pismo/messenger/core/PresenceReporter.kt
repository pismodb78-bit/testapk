package com.pismo.messenger.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.pismo.messenger.data.model.Presence
import com.pismo.messenger.data.repo.PresenceRepository
import com.pismo.messenger.net.SignalingClient
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
 *
 * КАК СТАТУС ДОХОДИТ ДО ОСТАЛЬНЫХ. Двумя путями сразу. Через базу — как
 * было: heartbeat раз в шесть секунд, собеседник читает своей сверкой.
 * И по сокету — сразу же, но только когда статус ИЗМЕНИЛСЯ. Через базу
 * изменение шло двумя шагами по сети, и на каждом могло задержаться или
 * не дойти; по сокету оно приходит мгновенно, а база остаётся
 * подстраховкой для тех, кто сейчас не на связи.
 */
object PresenceReporter {

    /** Тот же период, что у _presenceTimer на ПК. */
    private const val TICK_MS = 6000L

    /**
     * Сколько после сворачивания приложение ещё считается «на связи».
     *
     * Отметка «в сети» не может опираться на то, что ПРОЦЕСС жив: на Android
     * процесс существует сам по себе. Его будит система, поднимает push,
     * оживляет окно пробуждения в глубоком сне — и каждый такой вздох писал
     * «я в сети», пока хозяин спал. В базе это видно прямо: last_seen
     * подскакивает раз в несколько минут, а last_active шестнадцатичасовой
     * давности.
     *
     * Поэтому отмечаемся, пока окно на экране или идёт разговор, плюс эта
     * отсрочка. Десять минут — чтобы переключение в браузер и обратно не
     * выбрасывало человека из сети; дальше присутствие становится догадкой,
     * а догадка, выданная за факт, и есть то, что вводит в заблуждение.
     */
    private const val BACKGROUND_GRACE_SEC = 10 * 60

    private var job: Job? = null

    /** Сколько активити сейчас на экране. Больше нуля — приложение видно. */
    @Volatile private var startedActivities = 0

    /**
     * Показывалось ли окно приложения хоть раз за жизнь ЭТОГО процесса.
     *
     * Процесс поднимает не только человек — его будит push, чтобы показать
     * уведомление. Там всё выглядит как обычный запуск: подготовка приложения
     * отрабатывает, вход в аккаунт восстанавливается, и heartbeat начинал
     * писать last_seen. В итоге спящий человек показывался остальным как
     * заходивший четыре минуты назад: last_seen свежий, а last_active —
     * шестнадцатичасовой давности.
     *
     * Присутствие должно означать «человек здесь», а не «процесс существует».
     * Свёрнутое приложение по-прежнему шлёт last_seen — но только если его
     * открывал человек.
     */
    @Volatile private var everForeground = false

    /** Взводится звонком: разговор — это активность, даже со свёрнутым окном. */
    @Volatile var inCall: Boolean = false

    val isForeground: Boolean get() = startedActivities > 0

    /** Когда в последний раз были активны. Обновляется, пока экран открыт. */
    @Volatile private var lastActiveAt = System.currentTimeMillis()

    /** Свой статус, разосланный по сокету последним, и когда это было. */
    @Volatile private var broadcastStatus = -1
    @Volatile private var broadcastAt = 0L

    /** Простой в секундах — ровно то же, что GetLastInputInfo даёт на ПК. */
    private fun idleSeconds(): Int {
        if (isForeground || inCall) {
            lastActiveAt = System.currentTimeMillis()
            return 0
        }
        return ((System.currentTimeMillis() - lastActiveAt) / 1000).toInt()
    }

    /**
     * Рассылает СВОЙ статус по сокету, когда он изменился.
     *
     * Зачем, если есть heartbeat в базе. Оттуда статус доходит двумя шагами:
     * сначала я должен записать (до 6 секунд), потом собеседник должен
     * прочитать (ещё до 6 секунд), и каждый шаг — запрос к базе на другом
     * конце сети. Любой из них может не успеть; тогда новый статус появлялся
     * только со следующей сверкой. По сокету то же изменение приходит сразу
     * и всем. База остаётся источником правды для тех, кто подключился
     * позже или до кого сообщение не дошло.
     *
     * Шлём по изменению плюс раз в 30 секунд — иначе каждый клиент каждые
     * шесть секунд слал бы всем остальным одно и то же.
     */
    private fun announce(idleSec: Int) {
        if (!SignalingClient.isConnected) return
        val status = if (idleSec > Presence.ACTIVE_IDLE_SEC) 1 else 2
        val now = System.currentTimeMillis()
        if (status == broadcastStatus && now - broadcastAt < 30_000L) return
        broadcastStatus = status
        broadcastAt = now
        // sessionId — статус, payload — реальный простой: из него получатель
        // сразу строит «бездействует 5 мин», не дожидаясь ответа базы.
        SignalingClient.send("presence", 0, status, idleSec.toString())
    }

    /** Выход из аккаунта — сразу сообщаем «не в сети», не дожидаясь таймаута. */
    fun announceOffline() {
        runCatching { SignalingClient.send("presence", 0, 0, "0") }
        broadcastStatus = -1
        broadcastAt = 0L
    }

    fun start(app: Application) {
        if (job?.isActive == true) return

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                everForeground = true
            }
            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Чужие статусы применяем мгновенно, из одного места на всё
        // приложение: экраны читают их из общей памяти.
        SignalingClient.addListener { type, senderId, sessionId, payload ->
            if (type == "presence") {
                PresenceRepository.applyPush(senderId, sessionId, payload.toIntOrNull() ?: 0)
            }
        }

        job = CoroutineScope(Dispatchers.IO).launch {
            var reporting = false
            while (isActive) {
                if (everForeground && UserSession.effectiveId > 0) {
                    val idle = idleSeconds()
                    // Человек либо здесь, либо свернул приложение только что.
                    val present = isForeground || inCall || idle <= BACKGROUND_GRACE_SEC
                    if (present) {
                        announce(idle)
                        runCatching { PresenceRepository.heartbeat(idle) }
                        reporting = true
                    } else if (reporting) {
                        // Отсрочка вышла. Говорим «не в сети» ОДИН раз, вслух:
                        // иначе собеседник ждал бы, пока протухнет последняя
                        // отметка, и всё это время видел бы нас в сети.
                        reporting = false
                        announceOffline()
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    /** Выход из аккаунта: перестаём отмечаться, чтобы не «висеть в сети». */
    fun stop() {
        announceOffline()
        job?.cancel()
        job = null
        inCall = false
    }
}
