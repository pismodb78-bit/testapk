package com.pismo.messenger.data.repo

import com.pismo.messenger.core.RateLimiter
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.core.buildName
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.db.str
import com.pismo.messenger.data.model.DmPrivacy
import com.pismo.messenger.data.model.FriendEntry

/**
 * Друзья и приватность личных сообщений — порт PISMO/FriendsRepository.cs.
 *
 * status: 0 — заявка отправлена, 1 — дружба подтверждена.
 * На старых базах колонки status может не быть, поэтому предикат
 * «принятая заявка» строится динамически (см. acceptedPredicate).
 */
object FriendsRepository {

    enum class Relation { NONE, FRIEND, OUTGOING_PENDING, INCOMING_PENDING }

    @Volatile private var hasStatus = true
    @Volatile private var hasDmPrivacyColumn = true
    @Volatile private var probed = false

    /** Определяет доступные колонки один раз за сессию. */
    suspend fun ensureSchema() {
        if (probed) return
        runCatching {
            hasStatus = columnExists("friends", "status")
            hasDmPrivacyColumn = columnExists("users", "dm_privacy")
            probed = true
        }
    }

    private suspend fun columnExists(table: String, column: String): Boolean = runCatching {
        Db.scalarInt(
            "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",
            table, column
        ) > 0
    }.getOrDefault(true)

    /** Предикат «дружба подтверждена» для указанного алиаса таблицы. */
    fun acceptedPredicate(alias: String): String =
        if (hasStatus) "$alias.status=1" else "(1=1)"

    suspend fun isFriend(a: Int, b: Int): Boolean = runCatching {
        Db.exists(
            "SELECT 1 FROM friends WHERE ${acceptedPredicate("friends")} AND " +
                    "((user_id=? AND friend_id=?) OR (user_id=? AND friend_id=?)) LIMIT 1",
            a, b, b, a
        )
    }.getOrDefault(false)

    /** Отношение me → them. */
    suspend fun relation(me: Int, them: Int): Relation = runCatching {
        Db.queryFirst(
            "SELECT user_id, status FROM friends WHERE (user_id=? AND friend_id=?) " +
                    "OR (user_id=? AND friend_id=?)",
            me, them, them, me
        ) { rs ->
            val from = rs.getInt("user_id")
            val st = rs.getInt("status")
            when {
                st == 1 -> Relation.FRIEND
                from == me -> Relation.OUTGOING_PENDING
                else -> Relation.INCOMING_PENDING
            }
        } ?: Relation.NONE
    }.getOrDefault(Relation.NONE)

    /**
     * Отправить заявку. Не чаще двадцати в минуту — порт ограничения из
     * FriendsRepository.cs: рассылать заявки пачкой всему списку
     * пользователей это не мешает, а автоматическому спаму мешает.
     */
    suspend fun sendRequest(targetId: Int) {
        if (!RateLimiter.allow("friendreq:${UserSession.effectiveId}", 20, 60_000L)) return
        Db.exec(
            "INSERT IGNORE INTO friends (user_id, friend_id, status) VALUES (?, ?, 0)",
            UserSession.effectiveId, targetId
        )
    }

    /** Принять входящую заявку от requesterId. */
    suspend fun accept(requesterId: Int) {
        Db.exec(
            "UPDATE friends SET status=1 WHERE user_id=? AND friend_id=?",
            requesterId, UserSession.effectiveId
        )
    }

    /** Отклонить входящую заявку. */
    suspend fun decline(requesterId: Int) {
        Db.exec(
            "DELETE FROM friends WHERE user_id=? AND friend_id=? AND status=0",
            requesterId, UserSession.effectiveId
        )
    }

    /** Удалить из друзей (в обе стороны). */
    suspend fun remove(otherId: Int) {
        val me = UserSession.effectiveId
        Db.exec(
            "DELETE FROM friends WHERE (user_id=? AND friend_id=?) OR (user_id=? AND friend_id=?)",
            me, otherId, otherId, me
        )
    }

    /** Id всех подтверждённых друзей. */
    suspend fun acceptedIds(me: Int): Set<Int> = runCatching {
        Db.query(
            "SELECT user_id, friend_id FROM friends WHERE ${acceptedPredicate("friends")} " +
                    "AND (user_id=? OR friend_id=?)",
            me, me
        ) { rs ->
            val u = rs.getInt("user_id")
            val f = rs.getInt("friend_id")
            if (u == me) f else u
        }.toSet()
    }.getOrDefault(emptySet())

    /** Список друзей с именами. */
    suspend fun friends(): List<FriendEntry> {
        val me = UserSession.effectiveId
        return Db.query(
            "SELECT u.id, u.Name, u.Surname, u.login FROM friends f " +
                    "JOIN users u ON u.id = IF(f.user_id=?, f.friend_id, f.user_id) " +
                    "WHERE ${acceptedPredicate("f")} AND (f.user_id=? OR f.friend_id=?) " +
                    "ORDER BY u.Name, u.Surname",
            me, me, me
        ) { rs -> mapFriend(rs) }
    }

    /** Входящие заявки. */
    suspend fun incomingRequests(): List<FriendEntry> = Db.query(
        "SELECT u.id, u.Name, u.Surname, u.login FROM friends f " +
                "JOIN users u ON u.id = f.user_id " +
                "WHERE f.friend_id=? AND f.status=0 ORDER BY u.Name",
        UserSession.effectiveId
    ) { rs -> mapFriend(rs) }

    /** Исходящие заявки. */
    suspend fun outgoingRequests(): List<FriendEntry> = Db.query(
        "SELECT u.id, u.Name, u.Surname, u.login FROM friends f " +
                "JOIN users u ON u.id = f.friend_id " +
                "WHERE f.user_id=? AND f.status=0 ORDER BY u.Name",
        UserSession.effectiveId
    ) { rs -> mapFriend(rs) }

    suspend fun incomingCount(): Int = runCatching {
        Db.scalarInt(
            "SELECT COUNT(*) FROM friends WHERE friend_id=? AND status=0",
            UserSession.effectiveId
        )
    }.getOrDefault(0)

    /** Поиск пользователей для добавления в друзья. */
    suspend fun searchUsers(query: String, limit: Int = 50): List<FriendEntry> {
        val like = "%${query.trim()}%"
        return Db.query(
            "SELECT id, Name, Surname, login FROM users " +
                    "WHERE id <> ? AND (login LIKE ? OR Name LIKE ? OR Surname LIKE ?) " +
                    "ORDER BY Name, Surname LIMIT $limit",
            UserSession.effectiveId, like, like, like
        ) { rs -> mapFriend(rs) }
    }

    private fun mapFriend(rs: java.sql.ResultSet) = FriendEntry(
        userId = rs.getInt("id"),
        name = buildName(rs.str("Name"), rs.str("Surname"), rs.str("login")),
        login = rs.str("login"),
    )

    // ── Приватность личных сообщений ──────────────────────────────────

    suspend fun dmPrivacy(userId: Int): DmPrivacy {
        runCatching {
            Db.queryFirst("SELECT dm_privacy FROM user_prefs WHERE user_id=?", userId) { rs ->
                rs.getInt("dm_privacy")
            }?.let { return DmPrivacy.of(it) }
        }
        if (hasDmPrivacyColumn) {
            runCatching {
                Db.queryFirst("SELECT dm_privacy FROM users WHERE id=?", userId) { rs ->
                    rs.getInt("dm_privacy")
                }?.let { return DmPrivacy.of(it) }
            }
        }
        return DmPrivacy.EVERYONE
    }

    suspend fun setDmPrivacy(mode: DmPrivacy) {
        val me = UserSession.effectiveId
        runCatching {
            Db.exec(
                "INSERT INTO user_prefs (user_id, dm_privacy) VALUES (?, ?) " +
                        "ON DUPLICATE KEY UPDATE dm_privacy=?",
                me, mode.db, mode.db
            )
        }
        if (hasDmPrivacyColumn) {
            runCatching { Db.exec("UPDATE users SET dm_privacy=? WHERE id=?", mode.db, me) }
        }
    }

    /** Может ли текущий пользователь писать этому адресату. */
    suspend fun canWriteTo(targetId: Int): Boolean {
        if (dmPrivacy(targetId) == DmPrivacy.EVERYONE) return true
        return isFriend(UserSession.effectiveId, targetId)
    }
}
