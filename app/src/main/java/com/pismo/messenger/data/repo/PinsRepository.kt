package com.pismo.messenger.data.repo

import com.pismo.messenger.core.Crypto
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.db.str
import com.pismo.messenger.data.model.Scope

/**
 * Закреплённые сообщения — порт PISMO/PinsRepository.cs.
 * Таблица pinned_messages (message_id, scope) — закреп общий для чата.
 */
object PinsRepository {

    data class PinnedItem(val messageId: Int, val sender: String, val text: String)

    suspend fun isPinned(messageId: Int, scope: Scope): Boolean = runCatching {
        Db.exists(
            "SELECT 1 FROM pinned_messages WHERE message_id=? AND scope=?",
            messageId, scope.db
        )
    }.getOrDefault(false)

    /** Тумблер закрепа. true — после операции сообщение закреплено. */
    suspend fun toggle(messageId: Int, scope: Scope): Boolean = runCatching {
        if (isPinned(messageId, scope)) {
            Db.exec("DELETE FROM pinned_messages WHERE message_id=? AND scope=?", messageId, scope.db)
            false
        } else {
            Db.exec(
                "INSERT IGNORE INTO pinned_messages (message_id, scope, pinned_by) VALUES (?, ?, ?)",
                messageId, scope.db, UserSession.effectiveId
            )
            true
        }
    }.getOrDefault(false)

    /** Все закреплённые id в этой области — для пометки пузырей. */
    suspend fun pinnedIds(scope: Scope): Set<Int> = runCatching {
        Db.query("SELECT message_id FROM pinned_messages WHERE scope=?", scope.db) { rs ->
            rs.getInt("message_id")
        }.toSet()
    }.getOrDefault(emptySet())

    /** Список закреплённых личного диалога. */
    suspend fun listDirect(partnerId: Int): List<PinnedItem> {
        val me = UserSession.effectiveId
        return runCatching {
            Db.query(
                "SELECT m.id, m.text, TRIM(CONCAT(u.Name,' ',u.Surname)) AS sender, u.login " +
                        "FROM pinned_messages p JOIN messages m ON m.id = p.message_id " +
                        "JOIN users u ON u.id = m.sender_id " +
                        "WHERE p.scope=0 AND ((m.sender_id=? AND m.receiver_id=?) " +
                        "                  OR (m.sender_id=? AND m.receiver_id=?)) " +
                        "ORDER BY p.pinned_at DESC",
                me, partnerId, partnerId, me
            ) { rs -> mapPin(rs) }
        }.getOrDefault(emptyList())
    }

    /** Список закреплённых группы. */
    suspend fun listGroup(groupId: Int): List<PinnedItem> = runCatching {
        Db.query(
            "SELECT gm.id, gm.text, TRIM(CONCAT(u.Name,' ',u.Surname)) AS sender, u.login " +
                    "FROM pinned_messages p JOIN group_messages gm ON gm.id = p.message_id " +
                    "JOIN users u ON u.id = gm.sender_id " +
                    "WHERE p.scope=1 AND gm.group_id=? ORDER BY p.pinned_at DESC",
            groupId
        ) { rs -> mapPin(rs) }
    }.getOrDefault(emptyList())

    /** Список закреплённых канала сервера. */
    suspend fun listChannel(channelId: Int): List<PinnedItem> = runCatching {
        Db.query(
            "SELECT sm.id, sm.text, TRIM(CONCAT(u.Name,' ',u.Surname)) AS sender, u.login " +
                    "FROM pinned_messages p JOIN server_messages sm ON sm.id = p.message_id " +
                    "JOIN users u ON u.id = sm.sender_id " +
                    "WHERE p.scope=2 AND sm.channel_id=? ORDER BY p.pinned_at DESC",
            channelId
        ) { rs -> mapPin(rs) }
    }.getOrDefault(emptyList())

    private fun mapPin(rs: java.sql.ResultSet) = PinnedItem(
        messageId = rs.getInt("id"),
        sender = rs.str("sender").trim().ifBlank { rs.str("login") },
        text = Crypto.dec(rs.getString("text")),
    )
}
