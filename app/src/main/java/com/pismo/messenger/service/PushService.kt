package com.pismo.messenger.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.PushLog
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
        val data = message.data
        val kind = data["kind"] ?: "message"
        // Поле зовётся sender, а НЕ from: "from" зарезервировано в FCM
        // наряду с message_type, notification и всем на google/gcm — Google
        // отвергает такое сообщение целиком, с messaging/invalid-argument.
        val fromId = data["sender"]?.toIntOrNull() ?: 0
        val name = data["name"].orEmpty().ifBlank { "Новое сообщение" }

        // Каждое решение — в журнал. Push приходит в выгруженное приложение,
        // и без записи «дошло и молча отброшено» ничем не отличается от «не
        // дошло вовсе»; причин для первого хватает, и все они тихие.
        PushLog.add("пришло: вид=$kind, от=$fromId")

        if (!Prefs.notificationsEnabled) {
            PushLog.add("  пропущено: уведомления выключены в настройках")
            return
        }
        if (!Notifications.allowed(this)) {
            PushLog.add("  пропущено: Android не разрешил уведомления")
            return
        }

        // Кто мы — из памяти, а если её нет, из настроек.
        //
        // Ради выгруженного приложения push и заводился, но именно тогда
        // система поднимает НОВЫЙ процесс: onCreate приложения отработал,
        // а входа в аккаунт не было, и UserSession пуст. Проверка на него
        // отбрасывала ровно тот случай, ради которого всё делалось.
        //
        // Пустой id больше не повод молчать: он нужен только чтобы найти
        // список заглушённых. Не нашли — лучше показать лишнее, чем
        // проглотить сообщение.
        val me = UserSession.effectiveId.takeIf { it > 0 } ?: Prefs.pushUserId

        when (kind) {
            "group" -> {
                val gid = data["group"]?.toIntOrNull() ?: 0
                if (gid <= 0) {
                    PushLog.add("  пропущено: в push нет номера группы")
                    return
                }
                Notifications.showGroupMessage(this, gid, name, "Новое сообщение")
                PushLog.add("  показано: группа $gid")
            }
            "channel" -> {
                val cid = data["channel"]?.toIntOrNull() ?: 0
                if (cid <= 0) {
                    PushLog.add("  пропущено: в push нет номера канала")
                    return
                }
                Notifications.showChannelMessage(this, cid, name, mentions = 0)
                PushLog.add("  показано: канал $cid")
            }
            else -> {
                if (fromId <= 0) {
                    PushLog.add("  пропущено: в push нет отправителя")
                    return
                }
                // Заглушённых не беспокоим и здесь: список местный, сервер о
                // нём не знает и знать не должен.
                if (me > 0 && fromId in Prefs.ignoredUsers(me)) {
                    PushLog.add("  пропущено: отправитель заглушён")
                    return
                }
                Notifications.showMessage(this, fromId, name, "Новое сообщение")
                PushLog.add("  показано: сообщение от $fromId")
            }
        }
    }
}
