package com.pismo.messenger.data.repo

import com.pismo.messenger.core.UserSession
import com.pismo.messenger.core.buildName
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.db.bool
import com.pismo.messenger.data.db.str
import com.pismo.messenger.data.model.GroupMember
import com.pismo.messenger.data.model.UserBrief
import com.pismo.messenger.net.SignalingClient

/**
 * Групповые чаты — порт CreateGroupForm.cs, GroupMembersForm.cs и
 * групповой части MainForm_MessageActions.cs.
 *
 * Права как на ПК: добавлять участников может любой член группы,
 * исключать — только системный администратор PISMO; удалить группу —
 * её создатель или администратор.
 */
object GroupRepository {

    /** Кандидаты в новую группу — все, кроме себя. */
    suspend fun candidatesForNewGroup(): List<UserBrief> = Db.query(
        "SELECT id, Name, Surname, login FROM users WHERE id <> ? ORDER BY Name, Surname",
        UserSession.effectiveId
    ) { rs ->
        UserBrief(
            id = rs.getInt("id"),
            name = buildName(rs.str("Name"), rs.str("Surname"), rs.str("login")),
            login = rs.str("login"),
        )
    }

    /** Создаёт группу и добавляет создателя админом. Возвращает id группы. */
    suspend fun createGroup(name: String, memberIds: List<Int>): Int {
        val me = UserSession.effectiveId
        val groupId = Db.insert("INSERT INTO group_chats (name, created_by) VALUES (?, ?)", name, me)
        if (groupId <= 0) return 0

        Db.exec(
            "INSERT INTO group_members (group_id, user_id, is_admin) VALUES (?, ?, 1)",
            groupId, me
        )
        for (uid in memberIds) {
            if (uid == me) continue
            runCatching {
                Db.exec(
                    "INSERT INTO group_members (group_id, user_id, is_admin) VALUES (?, ?, 0)",
                    groupId, uid
                )
            }
        }
        return groupId
    }

    suspend fun members(groupId: Int): List<GroupMember> = Db.query(
        "SELECT u.id, TRIM(CONCAT(u.Name,' ',u.Surname)) AS full_name, u.login, gm.is_admin " +
                "FROM group_members gm JOIN users u ON u.id = gm.user_id " +
                "WHERE gm.group_id=? ORDER BY gm.is_admin DESC, full_name ASC",
        groupId
    ) { rs ->
        GroupMember(
            userId = rs.getInt("id"),
            name = rs.str("full_name").trim().ifBlank { rs.str("login") },
            isAdmin = rs.bool("is_admin"),
        )
    }

    /** Кандидаты на добавление — те, кого в группе ещё нет. */
    /**
     * Кого можно добавить. Забаненных в списке нет: добавить их всё равно
     * нельзя, и показывать их значило бы врать.
     */
    suspend fun candidatesToAdd(groupId: Int): List<UserBrief> {
        val map: (java.sql.ResultSet) -> UserBrief = { rs ->
            UserBrief(
                id = rs.getInt("id"),
                name = rs.str("full_name").trim().ifBlank { rs.str("login") },
                login = rs.str("login"),
            )
        }
        val head = "SELECT u.id, TRIM(CONCAT(u.Name,' ',u.Surname)) AS full_name, u.login " +
                "FROM users u WHERE u.id NOT IN " +
                "(SELECT user_id FROM group_members WHERE group_id=?) "

        // Таблицы банов может не быть: миграцию 25 кладёт администратор, прав
        // на неё у приложения нет. Без запасного пути запрос падал бы целиком,
        // и добавить в группу стало бы некого вовсе.
        if (bansTableOk) {
            val withBans = runCatching {
                Db.query(
                    head + "AND u.id NOT IN (SELECT user_id FROM group_bans WHERE group_id=?) " +
                            "ORDER BY full_name, u.login",
                    groupId, groupId, map = map
                )
            }
            withBans.onSuccess { return it }
            val e = withBans.exceptionOrNull()
            if (e?.message?.contains("group_bans", true) != true) throw e!!
            bansTableOk = false
        }

        return Db.query(head + "ORDER BY full_name, u.login", groupId, map = map)
    }

    /** Есть ли в базе таблица банов (миграция 25). */
    @Volatile private var bansTableOk = true

    /** Забанен ли человек в этой группе. */
    suspend fun isBanned(groupId: Int, userId: Int): Boolean = runCatching {
        Db.exists("SELECT 1 FROM group_bans WHERE group_id=? AND user_id=?", groupId, userId)
    }.getOrDefault(false)

    suspend fun addMembers(groupId: Int, userIds: List<Int>): Int {
        var added = 0
        for (uid in userIds) {
            // Забаненного обратно не берём: иначе бан снимался бы одним
            // нажатием «добавить», и выгонять было бы бессмысленно.
            if (isBanned(groupId, uid)) continue
            val ok = runCatching {
                Db.exec(
                    "INSERT INTO group_members (group_id, user_id, is_admin) VALUES (?, ?, 0)",
                    groupId, uid
                )
            }.isSuccess
            if (ok) added++
        }
        return added
    }

    /**
     * Исключение участника, с баном или без.
     *
     * Права те же, что у серверов: распоряжается группой её создатель,
     * помеченные звёздочкой админы группы и системный администратор —
     * см. canManageGroup.
     *
     * Событие по сокету обязательно: без него выгнанный узнавал бы об этом
     * только при следующей загрузке списка чатов, то есть у открытого
     * приложения — никогда, и продолжал бы писать туда, откуда его выгнали.
     */
    suspend fun kickMember(groupId: Int, userId: Int, alsoBan: Boolean = false) {
        if (alsoBan) {
            runCatching {
                Db.exec(
                    "INSERT IGNORE INTO group_bans (group_id,user_id) VALUES (?,?)",
                    groupId, userId
                )
            }
        }
        Db.exec("DELETE FROM group_members WHERE group_id=? AND user_id=?", groupId, userId)
        runCatching {
            SignalingClient.send("group_kick", userId, groupId, if (alsoBan) "ban" else "kick")
        }
    }

    /** Снять бан. Вернуть человека в группу всё равно придётся вручную. */
    suspend fun unban(groupId: Int, userId: Int) {
        runCatching {
            Db.exec("DELETE FROM group_bans WHERE group_id=? AND user_id=?", groupId, userId)
        }
    }

    suspend fun bannedUsers(groupId: Int): List<UserBrief> = runCatching {
        Db.query(
            "SELECT b.user_id, TRIM(CONCAT(u.Name,' ',u.Surname)) AS fullname, u.login " +
                    "FROM group_bans b JOIN users u ON u.id=b.user_id " +
                    "WHERE b.group_id=? ORDER BY u.login",
            groupId
        ) { rs ->
            UserBrief(
                id = rs.getInt("user_id"),
                name = buildName(rs.str("fullname"), "", rs.str("login")),
                login = rs.str("login"),
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Кто распоряжается группой: создатель, админ группы (звёздочка) или
     * системный администратор.
     *
     * Раньше исключать мог ТОЛЬКО системный администратор — то есть в своей
     * же группе хозяин ничего сделать не мог и звал админа.
     */
    suspend fun canManageGroup(groupId: Int): Boolean = runCatching {
        val me = UserSession.effectiveId
        if (UserSession.isAdmin) return@runCatching true
        if (createdBy(groupId) == me) return@runCatching true
        Db.scalarInt(
            "SELECT COALESCE(MAX(is_admin),0) FROM group_members WHERE group_id=? AND user_id=?",
            groupId, me
        ) > 0
    }.getOrDefault(false)

    // ── Лицо группы: картинка и цвет ─────────────────────────────────

    private val lookCache = HashMap<Int, ByteArray?>()
    private val lookLoadedAt = HashMap<Int, Long>()

    /** Как у серверов и аватаров: три минуты, иначе чужая смена не доедет. */
    private const val LOOK_TTL_MS = 3 * 60 * 1000L

    suspend fun groupAvatar(groupId: Int): ByteArray? {
        synchronized(lookCache) {
            val at = lookLoadedAt[groupId] ?: 0L
            if (lookCache.containsKey(groupId) &&
                System.currentTimeMillis() - at < LOOK_TTL_MS
            ) return lookCache[groupId]
        }
        val data = runCatching {
            Db.scalarBytes("SELECT avatar_data FROM group_chats WHERE id=?", groupId)
        }.getOrElse {
            // Связь моргнула — пусть значок останется прежним, а не пропадёт.
            return synchronized(lookCache) { lookCache[groupId] }
        }
        rememberLook(groupId, data)
        return data
    }

    fun cachedGroupAvatar(groupId: Int): ByteArray? =
        synchronized(lookCache) { lookCache[groupId] }

    fun forgetLook(groupId: Int) {
        synchronized(lookCache) {
            lookCache.remove(groupId)
            lookLoadedAt.remove(groupId)
        }
    }

    private fun rememberLook(groupId: Int, data: ByteArray?) {
        synchronized(lookCache) {
            lookCache[groupId] = data
            lookLoadedAt[groupId] = System.currentTimeMillis()
        }
    }

    /**
     * Картинка и цвет значка. Картинку приводим к универсальному формату той
     * же дорогой, что и вложения: выбранный на телефоне WebP иначе не
     * открылся бы на ПК.
     */
    suspend fun setGroupLook(groupId: Int, avatar: ByteArray?, color: String?): Boolean =
        runCatching {
            if (avatar != null) {
                val (portable, _) = com.pismo.messenger.core.toPortableImage(avatar, "group")
                Db.exec("UPDATE group_chats SET avatar_data=? WHERE id=?", portable, groupId)
                rememberLook(groupId, portable)
            }
            if (color != null) {
                Db.exec("UPDATE group_chats SET avatar_color=? WHERE id=?", color, groupId)
            }
            announceLook(groupId)
            true
        }.getOrDefault(false)

    suspend fun clearGroupAvatar(groupId: Int): Boolean = runCatching {
        Db.exec("UPDATE group_chats SET avatar_data=NULL WHERE id=?", groupId)
        rememberLook(groupId, null)
        announceLook(groupId)
        true
    }.getOrDefault(false)

    suspend fun renameGroup(groupId: Int, name: String): Boolean = runCatching {
        Db.exec("UPDATE group_chats SET name=? WHERE id=?", name.trim(), groupId)
        announceLook(groupId)
        true
    }.getOrDefault(false)

    /** Цвет значка и наличие картинки — для списка чатов. */
    suspend fun groupLook(groupId: Int): Pair<String, Boolean> = runCatching {
        Db.queryFirst(
            "SELECT COALESCE(avatar_color,'') AS c, (avatar_data IS NOT NULL) AS a " +
                    "FROM group_chats WHERE id=?",
            groupId
        ) { rs -> rs.str("c") to rs.bool("a") } ?: ("" to false)
    }.getOrDefault("" to false)

    /**
     * Говорит остальным, что группа сменила вид. Широковещательно, как и у
     * серверов: состав группы клиент знать не обязан, а лишним адресатам
     * событие ничего не стоит.
     */
    private fun announceLook(groupId: Int) {
        runCatching { SignalingClient.send("group_look", 0, groupId, "look") }
    }

    suspend fun leaveGroup(groupId: Int) {
        Db.exec(
            "DELETE FROM group_members WHERE group_id=? AND user_id=?",
            groupId, UserSession.effectiveId
        )
    }

    suspend fun isMember(groupId: Int): Boolean = runCatching {
        Db.scalarInt(
            "SELECT COUNT(*) FROM group_members WHERE group_id=? AND user_id=?",
            groupId, UserSession.effectiveId
        ) > 0
    }.getOrDefault(true)

    /** Название группы — для сообщений вроде «вас забанили в …». */
    suspend fun groupName(groupId: Int): String = runCatching {
        Db.scalarString("SELECT name FROM group_chats WHERE id=?", groupId).orEmpty()
    }.getOrDefault("")

    suspend fun createdBy(groupId: Int): Int =
        Db.scalarInt("SELECT created_by FROM group_chats WHERE id=?", groupId, default = -1)

    /** Удалить группу может только создатель или системный администратор. */
    suspend fun canDeleteGroup(groupId: Int): Boolean =
        createdBy(groupId) == UserSession.effectiveId || UserSession.isAdmin

    suspend fun deleteGroup(groupId: Int) {
        Db.exec("DELETE FROM group_messages WHERE group_id=?", groupId)
        Db.exec("DELETE FROM group_members WHERE group_id=?", groupId)
        Db.exec("DELETE FROM group_chats WHERE id=?", groupId)
    }

    /** Участники группы — для выбора собеседников в групповом звонке. */
    suspend fun callCandidates(groupId: Int): List<UserBrief> = Db.query(
        "SELECT gm.user_id, TRIM(CONCAT(u.Name,' ',u.Surname)) AS fullname, u.login " +
                "FROM group_members gm JOIN users u ON u.id = gm.user_id " +
                "WHERE gm.group_id=? AND gm.user_id <> ?",
        groupId, UserSession.effectiveId
    ) { rs ->
        UserBrief(
            id = rs.getInt("user_id"),
            name = rs.str("fullname").trim().ifBlank { rs.str("login") },
            login = rs.str("login"),
        )
    }
}
