package com.pismo.messenger.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.PushLog
import com.pismo.messenger.call.IncomingCallMonitor
import com.pismo.messenger.data.repo.AuthRepository
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.ServerRepository
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

    /**
     * Вернуть вход в аккаунт, если процесс поднят push'ем с нуля.
     *
     * Ничего своего не изобретаем: autoLogin — ровно то, чем входит обычный
     * запуск приложения, вместе с его правилом «только если человек просил
     * запомнить». Если данных нет, вход не подделываем.
     */
    private fun restoreSession(): Boolean {
        if (UserSession.effectiveId > 0) return true
        return runCatching {
            kotlinx.coroutines.runBlocking { AuthRepository.autoLogin() }
        }.getOrDefault(false)
    }

    /**
     * Текст для шторки — из базы.
     *
     * Push его не несёт и нести не должен: сообщения лежат зашифрованными, а
     * ключ есть только у клиентов. Расшифровать на сервере было бы можно, но
     * тогда он начал бы читать переписку. Поэтому push — это повод сходить в
     * базу, а не сама доставка.
     *
     * Не получилось — показываем без текста. Уведомление без содержимого
     * хуже, чем с ним, но лучше, чем ничего.
     */
    private fun preview(load: suspend () -> String): String = runCatching {
        kotlinx.coroutines.runBlocking { load() }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Новое сообщение"

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

        // Возвращаем вход в аккаунт, если процесс подняли push'ем с нуля.
        //
        // Нужен он для текста: push несёт только «кто и куда написал», а сам
        // текст лежит в базе, зашифрованным, и достать его может лишь тот,
        // кто вошёл. Без входа уведомление получается безликим — «Новое
        // сообщение» вместо самого сообщения.
        //
        // Неудача здесь не повод молчать: покажем без текста, как и раньше.
        // Для списка заглушённых хватит пометки с номером.
        restoreSession()
        val me = UserSession.effectiveId.takeIf { it > 0 } ?: Prefs.pushUserId

        when (kind) {
            "group" -> {
                val gid = data["group"]?.toIntOrNull() ?: 0
                if (gid <= 0) {
                    PushLog.add("  пропущено: в push нет номера группы")
                    return
                }
                val text = preview { ChatRepository.previewOfLatestInGroup(gid) }
                Notifications.showGroupMessage(this, gid, name, text)
                PushLog.add("  показано: группа $gid")
            }
            "call" -> {
                // Звонок. Раньше о нём в закрытом приложении узнавала только
                // фоновая служба — та самая, ради уведомления которой всё и
                // затевалось. Карточку берём из базы: push несёт номер, а не
                // содержимое.
                val callId = data["call"]?.toIntOrNull() ?: 0
                if (callId <= 0) {
                    PushLog.add("  пропущено: в push нет номера звонка")
                    return
                }
                // Звонку сессия ОБЯЗАТЕЛЬНА, в отличие от сообщения.
                //
                // Уведомление без текста — это неудобно, а разговор без своего
                // id собрать нельзя вовсе: в комнате оказывается один участник
                // вместо двух, а имя показывается как «0». Открыть такой
                // звонок можно, но говорить в нём не с кем.
                if (UserSession.effectiveId <= 0) {
                    PushLog.add("  пропущено: не удалось войти в аккаунт для звонка")
                    return
                }

                // Через монитор, а НЕ напрямую: у него есть защиты, которых
                // здесь быть не должно во второй раз — «уже идёт разговор»,
                // «этот вызов уже показывали», заглушённые и запреты на
                // вызовы. Прямой показ поднимал карточку поверх разговора и
                // по второму разу на тот же вызов.
                val why = IncomingCallMonitor.onPush(this, callId)
                if (why.isEmpty()) PushLog.add("  показано: звонок $callId")
                else PushLog.add("  пропущено: $why")
            }
            "friend" -> {
                if (fromId <= 0) {
                    PushLog.add("  пропущено: в push нет отправителя заявки")
                    return
                }
                // «Принял» показываем тем же уведомлением, но другим текстом:
                // отдельный канал заводить незачем, повод один — кто-то
                // сделал шаг навстречу.
                if (data["state"] == "accepted") {
                    Notifications.showFriendAccepted(this, fromId, name)
                    PushLog.add("  показано: заявку принял $fromId")
                } else {
                    Notifications.showFriendRequest(this, fromId, name)
                    PushLog.add("  показано: заявка в друзья от $fromId")
                }
            }
            "channel" -> {
                val cid = data["channel"]?.toIntOrNull() ?: 0
                if (cid <= 0) {
                    PushLog.add("  пропущено: в push нет номера канала")
                    return
                }
                Notifications.showChannelMessage(
                    this, cid, name, mentions = 0,
                    preview = preview { ServerRepository.previewOfLatestInChannel(cid) },
                )
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
                // Текст и счётчик — ровно как это делал опрос базы, чтобы в
                // шторке было видно, ЧТО прислали, а не безликое «новое
                // сообщение».
                val text = preview {
                    val p = ChatRepository.previewOfLatestFrom(fromId)
                    val unread = ChatRepository.unreadBySender()[fromId] ?: 0
                    if (unread > 1) "$p  ·  ещё ${unread - 1}" else p
                }
                Notifications.showMessage(this, fromId, name, text)
                PushLog.add("  показано: сообщение от $fromId")
            }
        }
    }
}
