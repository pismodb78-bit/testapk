package com.pismo.messenger.data.repo

import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.db.str
import com.pismo.messenger.data.model.ReactionSummary
import com.pismo.messenger.data.model.Scope

/**
 * Реакции-эмодзи на сообщения — порт PISMO/ReactionsRepository.cs.
 *
 * КРИТИЧНО: эмодзи сравнивается ПОБАЙТОВО через
 * `CONVERT(? USING utf8mb4) COLLATE utf8mb4_bin`. В коллациях *_ci MySQL
 * считает разные эмодзи равными строками, и тумблер снимал бы чужую
 * реакцию при попытке поставить новую. Явный COLLATE работает даже если
 * миграция 9 ещё не применена.
 */
object ReactionsRepository {

    private const val EQ = " AND emoji = CONVERT(? USING utf8mb4) COLLATE utf8mb4_bin"

    /** Поставить/снять реакцию. true — после операции реакция стоит. */
    suspend fun toggle(messageId: Int, scope: Scope, emoji: String): Boolean {
        if (messageId <= 0 || emoji.isBlank()) return false
        val me = UserSession.effectiveId
        return runCatching {
            val already = Db.exists(
                "SELECT 1 FROM message_reactions WHERE message_id=? AND scope=? AND user_id=?$EQ",
                messageId, scope.db, me, emoji
            )
            if (already) {
                Db.exec(
                    "DELETE FROM message_reactions WHERE message_id=? AND scope=? AND user_id=?$EQ",
                    messageId, scope.db, me, emoji
                )
                false
            } else {
                Db.exec(
                    "INSERT IGNORE INTO message_reactions (message_id, scope, user_id, emoji) " +
                            "VALUES (?, ?, ?, ?)",
                    messageId, scope.db, me, emoji
                )
                true
            }
        }.getOrDefault(false)
    }

    /** Реакции сразу для набора сообщений — один запрос на страницу. */
    suspend fun forMessages(ids: List<Int>, scope: Scope): Map<Int, List<ReactionSummary>> {
        if (ids.isEmpty()) return emptyMap()
        val list = ids.joinToString(",")
        return runCatching {
            Db.query(
                "SELECT message_id, emoji COLLATE utf8mb4_bin AS emoji, COUNT(*) AS cnt, " +
                        "MAX(CASE WHEN user_id=? THEN 1 ELSE 0 END) AS mine " +
                        "FROM message_reactions WHERE scope=? AND message_id IN ($list) " +
                        "GROUP BY message_id, emoji COLLATE utf8mb4_bin ORDER BY MIN(created_at)",
                UserSession.effectiveId, scope.db
            ) { rs ->
                rs.getInt("message_id") to ReactionSummary(
                    emoji = rs.str("emoji"),
                    count = rs.getInt("cnt"),
                    mine = rs.getInt("mine") == 1,
                )
            }.groupBy({ it.first }, { it.second })
        }.getOrDefault(emptyMap())
    }

    suspend fun forMessage(messageId: Int, scope: Scope): List<ReactionSummary> {
        if (messageId <= 0) return emptyList()
        return runCatching {
            Db.query(
                "SELECT emoji COLLATE utf8mb4_bin AS emoji, COUNT(*) AS cnt, " +
                        "MAX(CASE WHEN user_id=? THEN 1 ELSE 0 END) AS mine " +
                        "FROM message_reactions WHERE message_id=? AND scope=? " +
                        "GROUP BY emoji COLLATE utf8mb4_bin ORDER BY MIN(created_at)",
                UserSession.effectiveId, messageId, scope.db
            ) { rs ->
                ReactionSummary(rs.str("emoji"), rs.getInt("cnt"), rs.getInt("mine") == 1)
            }
        }.getOrDefault(emptyList())
    }

    /** Набор быстрых реакций — тот же, что в панели ПК-версии. */
    val QUICK = listOf("👍", "❤️", "😂", "😮", "😢", "🔥", "🎉", "👎")
}
