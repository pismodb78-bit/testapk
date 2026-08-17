package com.pismo.messenger.data.repo

import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.db.bool
import com.pismo.messenger.data.db.str
import com.pismo.messenger.data.model.Presence
import com.pismo.messenger.data.model.VoiceParticipant

/**
 * Онлайн-статусы пользователей и присутствие в голосовых каналах —
 * порт MainForm_Presence.cs и VoicePresence.cs.
 */
object PresenceRepository {

    /** Запись «жива», если heartbeat был не давнее 20 секунд (как на ПК). */
    private const val FRESH_SECONDS = 20

    @Volatile private var voiceTableOk = true

    // ── Присутствие пользователя ──────────────────────────────────────

    /**
     * Heartbeat. [active] = пользователь реально что-то делает (иначе
     * обновляем только last_seen, и статус уезжает в «не активен»).
     */
    suspend fun heartbeat(active: Boolean) {
        runCatching {
            Db.exec(
                "UPDATE users SET last_seen=NOW(), last_active=IF(?=1, NOW(), last_active) WHERE id=?",
                active, UserSession.effectiveId
            )
        }
    }

    /** При выходе отодвигаем last_seen на час назад — сразу «оффлайн». */
    suspend fun markOffline() {
        runCatching {
            Db.exec(
                "UPDATE users SET last_seen = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE id=?",
                UserSession.effectiveId
            )
        }
    }

    /**
     * Последние известные статусы.
     *
     * Нужен, чтобы точки появлялись мгновенно при открытии экрана, а не
     * после round-trip к удалённой базе. Каждый экран опрашивает присутствие
     * сам, и без общей памяти переход «список чатов → переписка → участники»
     * каждый раз начинался с пустоты, хотя ответ уже был получен секунду
     * назад на предыдущем экране.
     *
     * Устаревания здесь нет намеренно: значение и так живёт секунды, а
     * показать статус шестисекундной давности честнее, чем не показать
     * ничего.
     */
    private val cache = HashMap<Int, Presence>()

    /** Мгновенное чтение из памяти, без обращения к базе. */
    fun cached(userId: Int): Presence? = synchronized(cache) { cache[userId] }

    fun cachedFor(userIds: Collection<Int>): Map<Int, Presence> = synchronized(cache) {
        userIds.mapNotNull { id -> cache[id]?.let { id to it } }.toMap()
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    suspend fun presenceFor(userIds: Collection<Int>): Map<Int, Presence> {
        if (userIds.isEmpty()) return emptyMap()
        val list = userIds.joinToString(",")
        return runCatching {
            Db.query(
                "SELECT id, TIMESTAMPDIFF(SECOND, last_seen, NOW()) AS seen_ago, " +
                        "TIMESTAMPDIFF(SECOND, last_active, NOW()) AS active_ago " +
                        "FROM users WHERE id IN ($list)"
            ) { rs ->
                val id = rs.getInt("id")
                id to Presence(
                    userId = id,
                    seenAgoSec = rs.getInt("seen_ago").let { if (rs.wasNull()) Int.MAX_VALUE else it },
                    activeAgoSec = rs.getInt("active_ago").let { if (rs.wasNull()) Int.MAX_VALUE else it },
                )
            }.toMap().also { fresh -> synchronized(cache) { cache.putAll(fresh) } }
        }.getOrElse {
            // Связь моргнула — отдаём последнее известное вместо пустоты,
            // иначе все точки разом гаснут на одном неудачном запросе.
            cachedFor(userIds)
        }
    }

    suspend fun presenceOf(userId: Int): Presence =
        presenceFor(listOf(userId))[userId]
            ?: cached(userId)
            ?: Presence(userId, Int.MAX_VALUE, Int.MAX_VALUE)

    // ── Голосовые каналы ──────────────────────────────────────────────

    /**
     * Отмечает присутствие в голосовом канале. [streaming] — включена
     * камера или демонстрация экрана.
     */
    suspend fun voiceHeartbeat(
        channelId: Int,
        streaming: Boolean = false,
        micMuted: Boolean = false,
        deafened: Boolean = false,
    ) {
        if (!voiceTableOk || channelId <= 0) return
        runCatching {
            Db.exec(
                "INSERT INTO voice_presence (channel_id,user_id,joined_at,last_seen,streaming,mic_muted,deafened) " +
                        "VALUES (?,?,NOW(),NOW(),?,?,?) " +
                        "ON DUPLICATE KEY UPDATE last_seen=NOW(), streaming=?, mic_muted=?, deafened=?",
                channelId, UserSession.effectiveId, streaming, micMuted, deafened,
                streaming, micMuted, deafened
            )
        }.onFailure { e ->
            // 1146 = таблицы нет, миграция не выполнена. Больше не долбимся.
            if (e.message?.contains("doesn't exist", true) == true) voiceTableOk = false
        }
    }

    suspend fun voiceLeave(channelId: Int) {
        if (channelId <= 0) return
        runCatching {
            Db.exec(
                "DELETE FROM voice_presence WHERE channel_id=? AND user_id=?",
                channelId, UserSession.effectiveId
            )
        }
    }

    /** Сколько живых участников сейчас в канале — для проверки лимита. */
    suspend fun voiceCount(channelId: Int): Int {
        if (!voiceTableOk || channelId <= 0) return 0
        return runCatching {
            Db.scalarInt(
                "SELECT COUNT(*) FROM voice_presence " +
                        "WHERE channel_id=? AND last_seen > (NOW() - INTERVAL $FRESH_SECONDS SECOND)",
                channelId
            )
        }.getOrDefault(0)
    }

    /** Уже ли я в этом канале — перезаход не должен упираться в лимит. */
    suspend fun amIInChannel(channelId: Int): Boolean {
        if (!voiceTableOk || channelId <= 0) return false
        return runCatching {
            Db.scalarInt(
                "SELECT COUNT(*) FROM voice_presence WHERE channel_id=? AND user_id=? " +
                        "AND last_seen > (NOW() - INTERVAL $FRESH_SECONDS SECOND)",
                channelId, UserSession.effectiveId
            ) > 0
        }.getOrDefault(false)
    }

    /** Живые участники всех голосовых каналов сервера: channelId -> список. */
    suspend fun voiceForServer(serverId: Int): Map<Int, List<VoiceParticipant>> {
        if (!voiceTableOk || serverId <= 0) return emptyMap()
        return runCatching {
            Db.query(
                "SELECT vp.channel_id, vp.user_id, vp.streaming, vp.mic_muted, vp.deafened, " +
                        "TRIM(CONCAT(u.Name,' ',u.Surname)) AS nm, u.login " +
                        "FROM voice_presence vp " +
                        "JOIN server_channels sc ON sc.id = vp.channel_id " +
                        "JOIN users u ON u.id = vp.user_id " +
                        "WHERE sc.server_id=? AND vp.last_seen > (NOW() - INTERVAL $FRESH_SECONDS SECOND)",
                serverId
            ) { rs ->
                rs.getInt("channel_id") to VoiceParticipant(
                    userId = rs.getInt("user_id"),
                    name = rs.str("nm").trim().ifBlank { rs.str("login") },
                    streaming = rs.bool("streaming"),
                    micMuted = rs.bool("mic_muted"),
                    deafened = rs.bool("deafened"),
                )
            }.groupBy({ it.first }, { it.second })
        }.getOrDefault(emptyMap())
    }

    /** Живые участники одного канала. */
    suspend fun voiceForChannel(channelId: Int): List<VoiceParticipant> = runCatching {
        Db.query(
            "SELECT vp.user_id, vp.streaming, vp.mic_muted, vp.deafened, " +
                    "TRIM(CONCAT(u.Name,' ',u.Surname)) AS nm, u.login " +
                    "FROM voice_presence vp JOIN users u ON u.id = vp.user_id " +
                    "WHERE vp.channel_id=? AND vp.last_seen > (NOW() - INTERVAL $FRESH_SECONDS SECOND)",
            channelId
        ) { rs ->
            VoiceParticipant(
                userId = rs.getInt("user_id"),
                name = rs.str("nm").trim().ifBlank { rs.str("login") },
                streaming = rs.bool("streaming"),
                micMuted = rs.bool("mic_muted"),
                deafened = rs.bool("deafened"),
            )
        }
    }.getOrDefault(emptyList())
}
