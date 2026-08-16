package com.pismo.messenger.data.repo

import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.db.str

/**
 * Упоминания в каналах серверов — порт PISMO/ServerMentions.cs (миграция 15).
 *
 * Зачем отдельная таблица: текст сообщений лежит в БД ЗАШИФРОВАННЫМ
 * (Crypto.enc → AES-GCM в Base64), поэтому искать «@логин» запросом LIKE
 * по server_messages.text невозможно в принципе — в алфавите Base64 нет
 * даже символа «@». Раньше красная цифра упоминаний считалась именно так
 * и не срабатывала никогда, ни на ПК, ни здесь.
 *
 * Теперь адресаты вычисляются ОДИН РАЗ при отправке, пока открытый текст
 * на руках, и складываются строками (message_id, channel_id, user_id).
 * Бейдж после этого — обычный COUNT по индексу.
 */
object ServerMentions {

    @Volatile private var tableOk = true

    val available: Boolean get() = tableOk

    /**
     * Разбирает «@…» в тексте и записывает упоминания. Вызывать сразу
     * после вставки сообщения, с ОТКРЫТЫМ текстом и id вставленной строки.
     */
    suspend fun record(messageId: Int, channelId: Int, senderId: Int, plainText: String?) {
        if (!tableOk || messageId <= 0 || channelId <= 0) return
        if (plainText.isNullOrEmpty() || !plainText.contains('@')) return

        val lower = plainText.lowercase()
        val all = lower.contains("@все") || lower.contains("@all") || lower.contains("@everyone")

        runCatching {
            // Кандидаты — участники сервера, которому принадлежит канал, вместе
            // с логином и названием роли. Сравниваем вхождением подстроки, а не
            // разбором на слова: названия ролей бывают из нескольких слов, и
            // прежняя логика (LIKE '%@роль%') вела себя именно так.
            val targets = Db.query(
                "SELECT m.user_id, u.login, r.name AS role_name " +
                        "FROM server_channels sc " +
                        "JOIN server_members m ON m.server_id = sc.server_id " +
                        "JOIN users u ON u.id = m.user_id " +
                        "LEFT JOIN server_roles r ON r.id = m.role_id " +
                        "WHERE sc.id = ?",
                channelId
            ) { rs ->
                Triple(rs.getInt("user_id"), rs.str("login"), rs.getString("role_name").orEmpty())
            }.filter { (uid, login, role) ->
                when {
                    uid == senderId -> false                     // сам себя не упоминаешь
                    all -> true
                    login.isNotEmpty() && lower.contains("@${login.lowercase()}") -> true
                    role.isNotBlank() && lower.contains("@${role.lowercase()}") -> true
                    else -> false
                }
            }.map { it.first }

            if (targets.isEmpty()) return@runCatching

            val values = targets.joinToString(",") { "(?,?,?)" }
            val params = ArrayList<Any?>(targets.size * 3)
            targets.forEach { uid ->
                params.add(messageId); params.add(channelId); params.add(uid)
            }
            Db.exec(
                "INSERT IGNORE INTO server_mentions (message_id, channel_id, user_id) VALUES $values",
                *params.toTypedArray()
            )
        }.onFailure { e ->
            // Таблицы нет — миграция 15 не применена, больше не пытаемся.
            if (e.message?.contains("doesn't exist", ignoreCase = true) == true) tableOk = false
        }
    }

    /**
     * Упоминания при пересылке намеренно НЕ записываются: повторная
     * отправка старого сообщения не должна пинговать всех, кто внутри
     * него назван. Так же сделано на ПК.
     */
    suspend fun clearForMessage(messageId: Int) {
        runCatching { Db.exec("DELETE FROM server_mentions WHERE message_id=?", messageId) }
    }
}
