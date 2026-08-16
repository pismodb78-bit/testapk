package com.pismo.messenger.data.repo

import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.db.bool
import com.pismo.messenger.data.db.str
import com.pismo.messenger.data.model.CallSessionRow

/**
 * Состояние звонков — таблицы call_sessions и call_participants.
 *
 * ВАЖНО: медиа через эти таблицы НЕ идёт. Они хранят только жизненный цикл
 * (ringing / active / ended / rejected) и то, кто кому звонит. Сам звонок
 * ведёт LiveKit, а имя комнаты получается из id сессии — см. LiveKitToken.
 */
object CallRepository {

    /** Активный звонок в группе (чтобы присоединиться, а не создавать новый). */
    suspend fun activeGroupCall(groupId: Int): Int = runCatching {
        Db.scalarInt(
            "SELECT id FROM call_sessions WHERE group_id=? AND status IN ('ringing','active') " +
                    "ORDER BY id DESC LIMIT 1",
            groupId, default = -1
        )
    }.getOrDefault(-1)

    /** Активный личный звонок с собеседником — в любую сторону. */
    suspend fun activeDirectCall(peerId: Int): Int {
        val me = UserSession.effectiveId
        return runCatching {
            Db.scalarInt(
                "SELECT id FROM call_sessions WHERE ((caller_id=? AND callee_id=?) " +
                        "OR (caller_id=? AND callee_id=?)) AND status IN ('ringing','active') " +
                        "ORDER BY id DESC LIMIT 1",
                me, peerId, peerId, me, default = -1
            )
        }.getOrDefault(-1)
    }

    /** Создаёт сессию в статусе ringing. Возвращает её id. */
    suspend fun createCall(peerId: Int?, groupId: Int?, withVideo: Boolean): Int = Db.insert(
        "INSERT INTO call_sessions (caller_id, callee_id, group_id, status, has_video) " +
                "VALUES (?, ?, ?, 'ringing', ?)",
        UserSession.effectiveId,
        peerId?.takeIf { it > 0 },
        groupId?.takeIf { it >= 0 },
        withVideo
    )

    /**
     * Входящие звонки: адресованные лично мне либо в группу, где я состою,
     * новее последнего просмотренного id.
     */
    suspend fun incomingCalls(afterId: Int): List<CallSessionRow> {
        val me = UserSession.effectiveId
        val sql = """
            SELECT cs.id, cs.caller_id, cs.has_video, cs.group_id, cs.callee_id,
                   TRIM(CONCAT(u.Name,' ',u.Surname)) AS caller_name, u.login
            FROM call_sessions cs
            JOIN users u ON u.id = cs.caller_id
            LEFT JOIN group_members gm ON gm.group_id = cs.group_id AND gm.user_id = ?
            WHERE (cs.callee_id = ? OR gm.user_id = ?)
              AND cs.status = 'ringing'
              AND cs.caller_id <> ?
              AND cs.id > ?
        """.trimIndent()

        return runCatching {
            Db.query(sql, me, me, me, me, afterId) { rs ->
                CallSessionRow(
                    id = rs.getInt("id"),
                    callerId = rs.getInt("caller_id"),
                    callerName = rs.str("caller_name").trim().ifBlank { rs.str("login") },
                    calleeId = rs.getInt("callee_id").takeIf { !rs.wasNull() },
                    groupId = rs.getInt("group_id").takeIf { !rs.wasNull() },
                    status = "ringing",
                    hasVideo = rs.bool("has_video"),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun status(sessionId: Int): String =
        Db.scalarString("SELECT status FROM call_sessions WHERE id=?", sessionId).orEmpty()

    suspend fun accept(sessionId: Int) {
        runCatching {
            Db.exec(
                "UPDATE call_sessions SET status='active', answered_at=NOW() WHERE id=?",
                sessionId
            )
        }
    }

    suspend fun reject(sessionId: Int) {
        runCatching {
            Db.exec(
                "UPDATE call_sessions SET status='rejected', ended_at=NOW() WHERE id=?",
                sessionId
            )
        }
    }

    suspend fun end(sessionId: Int) {
        runCatching {
            Db.exec(
                "UPDATE call_sessions SET status='ended', ended_at=NOW() " +
                        "WHERE id=? AND status IN ('ringing','active')",
                sessionId
            )
        }
    }

    // ── Участники ─────────────────────────────────────────────────────

    suspend fun join(sessionId: Int) {
        runCatching {
            Db.exec(
                "INSERT INTO call_participants (call_id, user_id, joined_at) VALUES (?, ?, NOW())",
                sessionId, UserSession.effectiveId
            )
        }
    }

    /**
     * Выход из звонка. Когда участников не осталось — закрываем сессию,
     * иначе следующий вошедший «присоединится» к мёртвому звонку.
     */
    suspend fun leave(sessionId: Int) {
        runCatching {
            Db.exec(
                "DELETE FROM call_participants WHERE call_id=? AND user_id=?",
                sessionId, UserSession.effectiveId
            )
            val left = Db.scalarInt("SELECT COUNT(*) FROM call_participants WHERE call_id=?", sessionId)
            if (left == 0) end(sessionId)
        }
    }

    suspend fun participants(sessionId: Int): List<String> = runCatching {
        Db.query(
            "SELECT TRIM(CONCAT(u.Name, ' ', u.Surname)) AS user_name, u.login " +
                    "FROM call_participants cp JOIN users u ON u.id = cp.user_id " +
                    "WHERE cp.call_id=? ORDER BY cp.joined_at ASC",
            sessionId
        ) { rs -> rs.str("user_name").trim().ifBlank { rs.str("login") } }
    }.getOrDefault(emptyList())

    /** Идёт ли сейчас звонок с этим собеседником или в этой группе. */
    suspend fun ongoingFor(peerId: Int?, groupId: Int?): Int = when {
        groupId != null && groupId >= 0 -> activeGroupCall(groupId)
        peerId != null && peerId > 0 -> activeDirectCall(peerId)
        else -> -1
    }
}
