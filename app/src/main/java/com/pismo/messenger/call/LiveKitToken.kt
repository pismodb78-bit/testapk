package com.pismo.messenger.call

import com.pismo.messenger.core.Jwt
import com.pismo.messenger.core.Prefs

/**
 * Access-токены LiveKit — порт LiveKitSettings.CreateToken из ПК-версии.
 *
 * Токен подписывается прямо в приложении (токен-сервера в проекте нет),
 * HS256 секретом проекта. Ключ и секрет обязаны совпадать со значениями
 * из livekitsettings.json на ПК, иначе клиенты окажутся в разных комнатах.
 */
object LiveKitToken {

    /**
     * Имя комнаты для личного или группового звонка.
     *
     * Префикс "call_" ОБЯЗАТЕЛЕН и не косметический: ПК формирует имя как
     * `_isChannel ? _channelRoom : "call_" + _sessionId` (CallForm.cs:273).
     * Без префикса телефон заходил в комнату "17", а компьютер — в "call_17",
     * и оба сидели в пустых комнатах, каждый в своей: звонок «шёл», но
     * собеседник не появлялся никогда.
     */
    fun roomForCall(sessionId: Int): String = "call_$sessionId"

    /**
     * Имя комнаты голосового канала сервера — "vch_<id канала>".
     * Формат обязателен: по нему VoicePresence определяет канал.
     */
    fun roomForVoiceChannel(channelId: Int): String = "vch_$channelId"

    /** "vch_123" -> 123, иначе -1 (порт VoicePresence.ChannelIdFromRoom). */
    fun channelIdFromRoom(room: String?): Int {
        if (room.isNullOrEmpty()) return -1
        if (!room.startsWith("vch_", ignoreCase = true)) return -1
        return room.substring(4).toIntOrNull() ?: -1
    }

    /**
     * Создаёт JWT для входа участника в комнату.
     *
     * @param roomName  имя комнаты (см. roomForCall / roomForVoiceChannel)
     * @param identity  id пользователя строкой — по нему участники
     *                  сопоставляются с плитками на ПК
     * @param displayName отображаемое имя
     */
    fun create(roomName: String, identity: String, displayName: String): String {
        val now = System.currentTimeMillis() / 1000
        val exp = now + Prefs.liveKitTokenTtl

        // canUpdateOwnMetadata обязателен: без него сервер молча отбрасывает
        // обновление атрибутов участника, и значки мьюта микрофона/наушников
        // перестают работать — без единой ошибки в логах.
        val payload = buildString {
            append("""{"iss":"""").append(Jwt.esc(Prefs.liveKitApiKey)).append('"')
            append(""","sub":"""").append(Jwt.esc(identity)).append('"')
            append(""","name":"""").append(Jwt.esc(displayName)).append('"')
            append(""","nbf":""").append(now)
            append(""","exp":""").append(exp)
            append(""","video":{"roomJoin":true,"room":"""").append(Jwt.esc(roomName)).append('"')
            append(""","canPublish":true,"canSubscribe":true,"canPublishData":true""")
            append(""","canUpdateOwnMetadata":true}}""")
        }

        return Jwt.sign(payload, Prefs.liveKitApiSecret)
    }
}
