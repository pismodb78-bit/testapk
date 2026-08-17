package com.pismo.messenger.service

import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.PresenceRepository
import com.pismo.messenger.data.repo.ServerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Фоновая проверка новых сообщений, когда приложение свёрнуто — замена
 * трею ПК-версии с его балунами.
 *
 * Push-инфраструктуры (FCM) в проекте нет, а база опрашивается напрямую,
 * поэтому единственный способ узнать о новом сообщении в фоне — тот же
 * опрос, что и на ПК. Интервал здесь больше (10 с против 2.5 с), чтобы
 * не сажать батарею.
 */
class PollingService : LifecycleService() {

    private val previousUnread = HashMap<Int, Int>()
    private val previousGroupMax = HashMap<Int, Int>()
    private val previousChannelUnread = HashMap<Int, Int>()
    private var groupBaselineReady = false
    private var channelBaselineReady = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(Notifications.ID_SERVICE, Notifications.serviceNotification(this))

        lifecycleScope.launch {
            while (isActive) {
                if (UserSession.effectiveId > 0 && Prefs.backgroundPolling) {
                    runCatching { pollOnce() }
                }
                delay(10_000)
            }
        }
        return START_STICKY
    }

    private suspend fun pollOnce() {
        // Личные сообщения.
        val unread = ChatRepository.unreadBySender()

        for ((senderId, count) in unread) {
            val before = previousUnread[senderId] ?: 0
            if (count > before) {
                val name = runCatching {
                    com.pismo.messenger.data.repo.AuthRepository.loadUser(senderId)?.first
                }.getOrNull() ?: "Пользователь #$senderId"
                Notifications.showMessage(
                    this, senderId, name,
                    "Новых сообщений: $count"
                )
            }
        }
        previousUnread.clear()
        previousUnread.putAll(unread)

        // Групповые: у групп нет отметки прочтения на пользователя, поэтому
        // базовую точку держим в памяти — как _prevGroupMax на ПК.
        val groupMax = ChatRepository.groupMaxIncoming()
        if (!groupBaselineReady) {
            groupMax.forEach { (gid, v) -> previousGroupMax[gid] = v.first }
            groupBaselineReady = true
            return
        }
        for ((gid, value) in groupMax) {
            val (maxId, name) = value
            val before = previousGroupMax[gid] ?: 0
            if (maxId > before) {
                Notifications.showGroupMessage(this, gid, name, "Новое сообщение")
            }
            previousGroupMax[gid] = maxId
        }

        pollChannels()
    }

    /**
     * Каналы серверов. Раньше их здесь не было вовсе — сообщение в канале
     * не давало уведомления ни в фоне, ни свёрнутым, хотя красная цифра в
     * списке серверов появлялась.
     *
     * Считаем по тем же бейджам, что рисует список серверов: отдельный
     * запрос «что нового» дал бы расхождение между цифрой и уведомлением.
     */
    private suspend fun pollChannels() {
        val badges = runCatching { ServerRepository.badges(UserSession.userName) }
            .getOrDefault(emptyList())
        if (badges.isEmpty()) return

        // Первый проход только запоминает состояние: иначе при каждом
        // запуске сервиса сыпались бы уведомления о давно прочитанном.
        if (!channelBaselineReady) {
            badges.forEach { previousChannelUnread[it.channelId] = it.unread }
            channelBaselineReady = true
            return
        }

        val names = runCatching { ServerRepository.channelNames() }.getOrDefault(emptyMap())

        for (b in badges) {
            val before = previousChannelUnread[b.channelId] ?: 0
            previousChannelUnread[b.channelId] = b.unread
            // Заглушённые каналы молчат — так же, как на ПК.
            if (b.muted || b.unread <= before) continue

            Notifications.showChannelMessage(
                this,
                channelId = b.channelId,
                channelName = names[b.channelId] ?: "Канал",
                mentions = b.mentions,
            )
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        fun start(context: Context) {
            if (!Prefs.backgroundPolling) return
            runCatching {
                val intent = Intent(context, PollingService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PollingService::class.java)) }
        }
    }
}
