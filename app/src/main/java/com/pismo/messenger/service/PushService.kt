package com.pismo.messenger.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession

/**
 * Приём push-сообщений.
 *
 * Сервер шлёт ТОЛЬКО data-сообщения, без блока notification. Разница
 * существенная: notification-сообщение при свёрнутом приложении рисует
 * система сама, и мы не можем ни выбрать текст, ни решить, показывать ли
 * его вообще (например, когда чат уже открыт). Data-сообщение всегда
 * приходит сюда, и решаем мы.
 *
 * Содержимого сообщения в push НЕТ — только кто и куда написал. Текст
 * лежит в базе зашифрованным, а ключ есть только у клиентов: расшифровать
 * его на сервере, чтобы положить в push, было бы можно, но тогда сервер
 * начал бы читать переписку. Поэтому push — это повод сходить в базу, а
 * не сама доставка.
 */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushTokens.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!Prefs.notificationsEnabled) return
        if (UserSession.effectiveId <= 0) return

        val data = message.data
        val kind = data["kind"] ?: "message"
        val fromId = data["from"]?.toIntOrNull() ?: 0
        val name = data["name"].orEmpty().ifBlank { "Новое сообщение" }

        when (kind) {
            "group" -> {
                val gid = data["group"]?.toIntOrNull() ?: return
                Notifications.showGroupMessage(this, gid, name, "Новое сообщение")
            }
            "channel" -> {
                val cid = data["channel"]?.toIntOrNull() ?: return
                Notifications.showChannelMessage(this, cid, name, mentions = 0)
            }
            else -> {
                if (fromId <= 0) return
                // Заглушённых не беспокоим и здесь: список местный, сервер о
                // нём не знает и знать не должен.
                if (fromId in Prefs.ignoredUsers()) return
                Notifications.showMessage(this, fromId, name, "Новое сообщение")
            }
        }
    }
}
