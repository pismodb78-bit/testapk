package com.pismo.messenger.data.repo

import com.pismo.messenger.core.Crypto
import com.pismo.messenger.core.MessagePreview
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.MediaCache
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.db.bool
import com.pismo.messenger.data.db.epochMillis
import com.pismo.messenger.data.db.str
import com.pismo.messenger.data.model.ChannelType
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.model.ServerChannel
import com.pismo.messenger.data.model.ServerMemberRow
import com.pismo.messenger.data.model.ServerPermissions
import com.pismo.messenger.data.model.ServerRole
import com.pismo.messenger.data.model.ServerSummary

/**
 * Серверы, каналы, роли, баны и сообщения каналов — порт ServersForm.cs,
 * ServerReads.cs и MainForm_ServerRail.cs.
 *
 * Схема необязательных колонок (reply_to_id, is_deleted) проверяется в
 * рантайме: на части баз миграции не применены, и жёсткая ссылка на
 * отсутствующую колонку роняла бы весь запрос бейджей — как это было на ПК.
 */
object ServerRepository {

    @Volatile private var hasReplyCol: Boolean? = null
    @Volatile private var hasDeletedCol: Boolean? = null
    @Volatile private var hasMentionsTbl: Boolean? = null

    /**
     * На этом хостинге доступ к information_schema закрыт даже
     * администратору (#1044). Без фолбэка на SHOW проверка возвращала бы
     * false, и мы бы молча решили, что колонки reply_to_id нет: ответы в
     * каналах перестали бы сохраняться, а половина условий бейджа —
     * считаться. Та же ошибка была на ПК и чинилась там же.
     */
    private suspend fun columnExists(table: String, col: String): Boolean {
        runCatching {
            return Db.scalarInt(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND table_name=? AND column_name=?",
                table, col
            ) > 0
        }
        if (!isPlainIdentifier(table)) return false
        return runCatching {
            Db.query("SHOW COLUMNS FROM `$table`") { rs -> rs.getString(1) }
                .any { it.equals(col, ignoreCase = true) }
        }.getOrDefault(false)
    }

    private suspend fun tableExists(table: String): Boolean {
        runCatching {
            return Db.scalarInt(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() AND table_name=?",
                table
            ) > 0
        }
        return runCatching {
            Db.query("SHOW TABLES") { rs -> rs.getString(1) }
                .any { it.equals(table, ignoreCase = true) }
        }.getOrDefault(false)
    }

    private fun isPlainIdentifier(s: String): Boolean =
        s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '_' }

    // ════════════════════════════════════════════════════════════════
    //  СЕРВЕРЫ
    // ════════════════════════════════════════════════════════════════

    suspend fun myServers(): List<ServerSummary> = Db.query(
        "SELECT s.id, s.name, s.owner_id FROM servers s " +
                "JOIN server_members m ON m.server_id=s.id WHERE m.user_id=? ORDER BY s.id",
        UserSession.effectiveId
    ) { rs ->
        ServerSummary(rs.getInt("id"), rs.str("name"), rs.getInt("owner_id"))
    }

    /** Создаёт сервер с каналами по умолчанию — как на ПК. */
    suspend fun createServer(name: String): Int {
        val me = UserSession.effectiveId
        val serverId = Db.insert("INSERT INTO servers (name, owner_id) VALUES (?,?)", name, me)
        if (serverId <= 0) return 0
        Db.exec("INSERT INTO server_members (server_id, user_id) VALUES (?,?)", serverId, me)
        Db.exec(
            "INSERT INTO server_channels (server_id,name,type,position) " +
                    "VALUES (?,'основной','text',0),(?,'Общий','voice',1)",
            serverId, serverId
        )
        return serverId
    }

    sealed interface JoinResult {
        data class Ok(val name: String) : JoinResult
        data object Banned : JoinResult
        data object NotFound : JoinResult
    }

    suspend fun joinServer(serverId: Int): JoinResult {
        val me = UserSession.effectiveId
        val banned = runCatching {
            Db.exists("SELECT 1 FROM server_bans WHERE server_id=? AND user_id=?", serverId, me)
        }.getOrDefault(false)
        if (banned) return JoinResult.Banned

        val name = Db.scalarString("SELECT name FROM servers WHERE id=?", serverId)
            ?: return JoinResult.NotFound

        Db.exec("INSERT IGNORE INTO server_members (server_id,user_id) VALUES (?,?)", serverId, me)
        return JoinResult.Ok(name)
    }

    suspend fun leaveServer(serverId: Int) {
        Db.exec(
            "DELETE FROM server_members WHERE server_id=? AND user_id=?",
            serverId, UserSession.effectiveId
        )
    }

    suspend fun serverInfo(serverId: Int): Pair<String, Int>? = Db.queryFirst(
        "SELECT name, owner_id FROM servers WHERE id=?", serverId
    ) { rs -> rs.str("name") to rs.getInt("owner_id") }

    suspend fun renameServer(serverId: Int, name: String) {
        runCatching { Db.exec("UPDATE servers SET name=? WHERE id=?", name.trim(), serverId) }
    }

    suspend fun deleteServer(serverId: Int) {
        runCatching {
            Db.exec(
                "DELETE FROM server_messages WHERE channel_id IN " +
                        "(SELECT id FROM server_channels WHERE server_id=?)", serverId
            )
        }
        runCatching { Db.exec("DELETE FROM server_channels WHERE server_id=?", serverId) }
        runCatching { Db.exec("DELETE FROM server_members WHERE server_id=?", serverId) }
        runCatching { Db.exec("DELETE FROM server_roles WHERE server_id=?", serverId) }
        runCatching { Db.exec("DELETE FROM server_bans WHERE server_id=?", serverId) }
        Db.exec("DELETE FROM servers WHERE id=?", serverId)
    }

    // ════════════════════════════════════════════════════════════════
    //  ПРАВА
    // ════════════════════════════════════════════════════════════════

    suspend fun permissions(serverId: Int): ServerPermissions {
        val me = UserSession.effectiveId
        val ownerId = Db.scalarInt("SELECT owner_id FROM servers WHERE id=?", serverId, default = -1)
        val isOwner = ownerId == me

        return runCatching {
            Db.queryFirst(
                "SELECT m.muted_notifs, r.name AS rname, r.can_ban, r.can_kick, r.can_mute, r.can_manage " +
                        "FROM server_members m LEFT JOIN server_roles r ON r.id=m.role_id " +
                        "WHERE m.server_id=? AND m.user_id=?",
                serverId, me
            ) { rs ->
                ServerPermissions(
                    isOwner = isOwner,
                    // Владелец может всё, независимо от роли.
                    canBan = isOwner || rs.bool("can_ban"),
                    canKick = isOwner || rs.bool("can_kick"),
                    canMute = isOwner || rs.bool("can_mute"),
                    canManage = isOwner || rs.bool("can_manage"),
                    mutedNotifications = rs.bool("muted_notifs"),
                )
            } ?: ServerPermissions(isOwner = isOwner, canBan = isOwner, canKick = isOwner,
                canMute = isOwner, canManage = isOwner)
        }.getOrDefault(ServerPermissions(isOwner = isOwner))
    }

    suspend fun myRoleName(serverId: Int): String = runCatching {
        Db.scalarString(
            "SELECT r.name FROM server_members m JOIN server_roles r ON r.id=m.role_id " +
                    "WHERE m.server_id=? AND m.user_id=?",
            serverId, UserSession.effectiveId
        ).orEmpty()
    }.getOrDefault("")

    // ════════════════════════════════════════════════════════════════
    //  КАНАЛЫ
    // ════════════════════════════════════════════════════════════════

    suspend fun channels(serverId: Int): List<ServerChannel> {
        // user_limit добавлен миграцией 14 — на старых базах его может не быть.
        val withLimit = runCatching {
            Db.query(
                "SELECT id,name,type,user_limit FROM server_channels WHERE server_id=? ORDER BY position,id",
                serverId
            ) { rs -> mapChannel(rs, serverId, true) }
        }
        if (withLimit.isSuccess) return withLimit.getOrDefault(emptyList())

        return Db.query(
            "SELECT id,name,type FROM server_channels WHERE server_id=? ORDER BY position,id",
            serverId
        ) { rs -> mapChannel(rs, serverId, false) }
    }

    private fun mapChannel(rs: java.sql.ResultSet, serverId: Int, withLimit: Boolean) = ServerChannel(
        id = rs.getInt("id"),
        serverId = serverId,
        name = rs.str("name"),
        type = ChannelType.of(rs.getString("type")),
        userLimit = if (withLimit) rs.getInt("user_limit") else 0,
    )

    suspend fun createChannel(serverId: Int, name: String, type: ChannelType): Int = Db.insert(
        "INSERT INTO server_channels (server_id,name,type,position) VALUES (?,?,?,99)",
        serverId, name, type.db
    )

    suspend fun renameChannel(channelId: Int, name: String) {
        Db.exec("UPDATE server_channels SET name=? WHERE id=?", name, channelId)
    }

    suspend fun deleteChannel(channelId: Int) {
        runCatching { Db.exec("DELETE FROM server_messages WHERE channel_id=?", channelId) }
        Db.exec("DELETE FROM server_channels WHERE id=?", channelId)
    }

    /** 0 — без ограничения вместимости. */
    suspend fun setChannelUserLimit(channelId: Int, limit: Int) {
        Db.exec("UPDATE server_channels SET user_limit=? WHERE id=?", limit, channelId)
    }

    // ════════════════════════════════════════════════════════════════
    //  СООБЩЕНИЯ КАНАЛА
    // ════════════════════════════════════════════════════════════════

    suspend fun channelMessages(channelId: Int, limit: Int = 40, beforeId: Int = 0): List<ChatMessage> {
        if (hasReplyCol == null) hasReplyCol = columnExists("server_messages", "reply_to_id")
        val withReply = hasReplyCol == true
        val replyCol = if (withReply) "sm.reply_to_id," else "0 AS reply_to_id,"
        val cursor = if (beforeId > 0) "AND sm.id < $beforeId " else ""

        val inner = """
            SELECT sm.id, sm.sender_id, sm.text, $replyCol
                   sm.file_name,
                   UNIX_TIMESTAMP(sm.created_at) AS created_ts,
                   TRIM(CONCAT(u.Name,' ',u.Surname)) AS nm, u.login,
                   (sm.image_data IS NOT NULL) AS has_img,
                   (sm.audio_data IS NOT NULL) AS has_audio,
                   (sm.video_data IS NOT NULL) AS has_video,
                   (sm.file_data  IS NOT NULL) AS has_file,
                   OCTET_LENGTH(sm.file_data) AS file_size
            FROM server_messages sm
            JOIN users u ON u.id = sm.sender_id
            WHERE sm.channel_id=? $cursor
            ORDER BY sm.id DESC LIMIT $limit
        """.trimIndent()

        return Db.query("SELECT * FROM ($inner) sub ORDER BY id ASC", channelId) { rs ->
            ChatMessage(
                id = rs.getInt("id"),
                senderId = rs.getInt("sender_id"),
                senderName = rs.str("nm").trim().ifBlank { rs.str("login") },
                text = Crypto.dec(rs.getString("text")),
                createdAtMs = rs.epochMillis("created_ts"),
                replyToId = rs.getInt("reply_to_id"),
                hasImage = rs.bool("has_img"),
                hasAudio = rs.bool("has_audio"),
                hasVideo = rs.bool("has_video"),
                hasFile = rs.bool("has_file"),
                fileName = rs.getString("file_name"),
                scope = Scope.SERVER,
            )
        }
    }

    suspend fun channelMessageCount(channelId: Int): Int =
        Db.scalarInt("SELECT COUNT(*) FROM server_messages WHERE channel_id=?", channelId)

    suspend fun sendChannelMessage(
        channelId: Int,
        text: String,
        replyToId: Int = 0,
        image: ByteArray? = null,
        audio: ByteArray? = null,
        video: ByteArray? = null,
        file: ByteArray? = null,
        fileName: String? = null,
    ): Int {
        val me = UserSession.effectiveId
        val enc = Crypto.enc(text)
        if (hasReplyCol == null) hasReplyCol = columnExists("server_messages", "reply_to_id")

        val hasMedia = image != null || audio != null || video != null || file != null
        val newId = if (hasMedia) {
            val id = Db.insert(
                "INSERT INTO server_messages (channel_id, sender_id, text, image_data, audio_data, " +
                        "video_data, file_data, file_name" +
                        (if (hasReplyCol == true) ", reply_to_id" else "") +
                        ") VALUES (?,?,?,?,?,?,NULL,?" +
                        (if (hasReplyCol == true) ",?" else "") + ")",
                *buildList {
                    addAll(listOf(channelId, me, enc, image, audio, video, fileName))
                    if (hasReplyCol == true) add(if (replyToId > 0) replyToId else null)
                }.toTypedArray()
            )
            if (file != null && file.isNotEmpty() && id > 0) {
                runCatching { Db.exec("UPDATE server_messages SET file_data=? WHERE id=?", file, id) }
            }
            id
        } else if (hasReplyCol == true && replyToId > 0) {
            Db.insert(
                "INSERT INTO server_messages (channel_id, sender_id, text, reply_to_id) VALUES (?,?,?,?)",
                channelId, me, enc, replyToId
            )
        } else {
            Db.insert(
                "INSERT INTO server_messages (channel_id, sender_id, text) VALUES (?,?,?)",
                channelId, me, enc
            )
        }

        // Адресатов «@…» вычисляем ЗДЕСЬ, пока текст открытый: в БД он ляжет
        // зашифрованным, и после этого разобрать упоминания уже невозможно.
        // Подпись к вложению разбирается тем же вызовом.
        ServerMentions.record(newId, channelId, me, text)

        return newId
    }

    suspend fun editChannelMessage(msgId: Int, newText: String) {
        Db.exec("UPDATE server_messages SET text=? WHERE id=?", Crypto.enc(newText), msgId)
    }

    /** Своё сообщение может удалить автор; чужое — только модератор. */
    suspend fun deleteChannelMessage(msgId: Int, asModerator: Boolean) {
        if (asModerator) {
            Db.exec("DELETE FROM server_messages WHERE id=?", msgId)
        } else {
            Db.exec(
                "DELETE FROM server_messages WHERE id=? AND sender_id=?",
                msgId, UserSession.effectiveId
            )
        }
        MediaCache.invalidateAll(msgId)
    }

    suspend fun channelMessageAuthor(msgId: Int): Int =
        Db.scalarInt("SELECT sender_id FROM server_messages WHERE id=?", msgId, default = -1)

    /** Пакетная предзагрузка медиа канала — один IN-запрос. */
    suspend fun prefetchChannelMedia(messages: List<ChatMessage>) {
        val ids = messages.filter { m ->
            (m.hasImage && !MediaCache.has(m.id, "img", m.fileName)) ||
                    (m.hasAudio && !MediaCache.has(m.id, "audio"))
        }.map { it.id }
        if (ids.isEmpty()) return

        runCatching {
            Db.use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT id, image_data, audio_data, file_name FROM server_messages " +
                                "WHERE id IN (${ids.joinToString(",")})"
                    ).use { rs ->
                        while (rs.next()) {
                            val id = rs.getInt("id")
                            val fn = rs.getString("file_name")
                            rs.getBytes("image_data")?.let { MediaCache.put(id, "img", it, fn) }
                            rs.getBytes("audio_data")?.let { MediaCache.put(id, "audio", it) }
                        }
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  УЧАСТНИКИ, РОЛИ, БАНЫ
    // ════════════════════════════════════════════════════════════════

    suspend fun members(serverId: Int): List<ServerMemberRow> = Db.query(
        "SELECT m.user_id, m.role_id, TRIM(CONCAT(u.Name,' ',u.Surname)) AS nm, u.login, " +
                "s.owner_id, r.name AS rname, r.color AS rcolor " +
                "FROM server_members m JOIN users u ON u.id=m.user_id " +
                "JOIN servers s ON s.id=m.server_id " +
                "LEFT JOIN server_roles r ON r.id=m.role_id " +
                "WHERE m.server_id=? ORDER BY (m.user_id=s.owner_id) DESC, u.login",
        serverId
    ) { rs ->
        val uid = rs.getInt("user_id")
        ServerMemberRow(
            userId = uid,
            name = rs.str("nm").trim().ifBlank { rs.str("login") },
            login = rs.str("login"),
            roleId = rs.getInt("role_id").takeIf { !rs.wasNull() },
            roleName = rs.getString("rname").orEmpty(),
            isOwner = rs.getInt("owner_id") == uid,
        )
    }

    suspend fun roles(serverId: Int): List<ServerRole> = Db.query(
        "SELECT id, name, color, can_ban, can_kick, can_mute, can_manage, position " +
                "FROM server_roles WHERE server_id=? ORDER BY position,id",
        serverId
    ) { rs ->
        ServerRole(
            id = rs.getInt("id"),
            name = rs.str("name"),
            colorHex = rs.getString("color") ?: "#FFFFFF",
            canBan = rs.bool("can_ban"),
            canKick = rs.bool("can_kick"),
            canMute = rs.bool("can_mute"),
            canManage = rs.bool("can_manage"),
            position = rs.getInt("position"),
        )
    }

    suspend fun createRole(serverId: Int, role: ServerRole): Int = Db.insert(
        "INSERT INTO server_roles (server_id,name,color,can_ban,can_kick,can_mute,can_manage,position) " +
                "VALUES (?,?,?,?,?,?,?,?)",
        serverId, role.name, role.colorHex, role.canBan, role.canKick,
        role.canMute, role.canManage, role.position
    )

    suspend fun updateRole(role: ServerRole) {
        Db.exec(
            "UPDATE server_roles SET name=?,color=?,can_ban=?,can_kick=?,can_mute=?,can_manage=? WHERE id=?",
            role.name, role.colorHex, role.canBan, role.canKick, role.canMute, role.canManage, role.id
        )
    }

    suspend fun deleteRole(roleId: Int) {
        Db.exec("UPDATE server_members SET role_id=NULL WHERE role_id=?", roleId)
        Db.exec("DELETE FROM server_roles WHERE id=?", roleId)
    }

    suspend fun assignRole(serverId: Int, userId: Int, roleId: Int?) {
        Db.exec(
            "UPDATE server_members SET role_id=? WHERE server_id=? AND user_id=?",
            roleId, serverId, userId
        )
    }

    /** Заглушить уведомления сервера — только для текущего пользователя. */
    suspend fun setMutedNotifications(serverId: Int, muted: Boolean) {
        Db.exec(
            "UPDATE server_members SET muted_notifs=? WHERE server_id=? AND user_id=?",
            muted, serverId, UserSession.effectiveId
        )
    }

    suspend fun kickMember(serverId: Int, userId: Int, alsoBan: Boolean) {
        Db.exec("DELETE FROM server_members WHERE server_id=? AND user_id=?", serverId, userId)
        if (alsoBan) {
            runCatching {
                Db.exec("INSERT IGNORE INTO server_bans (server_id,user_id) VALUES (?,?)", serverId, userId)
            }
        }
    }

    suspend fun unban(serverId: Int, userId: Int) {
        runCatching { Db.exec("DELETE FROM server_bans WHERE server_id=? AND user_id=?", serverId, userId) }
    }

    suspend fun bannedUsers(serverId: Int): List<ServerMemberRow> = runCatching {
        Db.query(
            "SELECT b.user_id, TRIM(CONCAT(u.Name,' ',u.Surname)) AS nm, u.login " +
                    "FROM server_bans b JOIN users u ON u.id=b.user_id WHERE b.server_id=? ORDER BY u.login",
            serverId
        ) { rs ->
            ServerMemberRow(
                userId = rs.getInt("user_id"),
                name = rs.str("nm").trim().ifBlank { rs.str("login") },
                login = rs.str("login"),
                roleId = null, roleName = "", isOwner = false,
            )
        }
    }.getOrDefault(emptyList())

    // ════════════════════════════════════════════════════════════════
    //  ПРОЧИТАННОЕ И БЕЙДЖИ (порт ServerReads.cs)
    // ════════════════════════════════════════════════════════════════

    data class Badge(
        val serverId: Int,
        val channelId: Int,
        val unread: Int,
        val mentions: Int,
        val muted: Boolean,
    )

    /**
     * id канала → его имя, по всем серверам, где я состою.
     * Нужно уведомлениям: бейдж знает только id, а в шторке должно быть
     * название канала, а не «Канал #17».
     */
    /**
     * Максимальный id ЧУЖОГО сообщения по каждому каналу, где я состою.
     *
     * Отдельный простой запрос вместо бейджей. Бейджи собирают тяжёлый SQL с
     * проверками необязательных колонок и таблицы упоминаний, и если он
     * падает (нет server_mentions, закрыт information_schema), уведомления о
     * каналах пропадают целиком и молча. Здесь ломаться нечему.
     */
    suspend fun maxIncomingPerChannel(): Map<Int, Int> = runCatching {
        val me = UserSession.effectiveId
        Db.query(
            "SELECT ch.id, COALESCE(MAX(sm.id),0) AS max_id " +
                    "FROM server_channels ch " +
                    "JOIN server_members m ON m.server_id = ch.server_id AND m.user_id = ? " +
                    "LEFT JOIN server_messages sm ON sm.channel_id = ch.id AND sm.sender_id <> ? " +
                    // Голосовые каналы отсюда БОЛЬШЕ НЕ ИСКЛЮЧАЮТСЯ. У них
                    // есть своя переписка (на ПК она открывается значком 💬),
                    // и написанное в ней — такое же сообщение, как в любом
                    // другом канале. Из-за старого условия упоминание в чате
                    // голосового канала не давало уведомления вообще: с ПК
                    // пингуют, а на телефоне тишина.
                    "GROUP BY ch.id",
            me, me
        ) { rs -> rs.getInt("id") to rs.getInt("max_id") }.toMap()
    }.getOrDefault(emptyMap())

    /** Заглушённые мной серверы — их каналы не должны звенеть. */
    suspend fun mutedChannelIds(): Set<Int> = runCatching {
        Db.query(
            "SELECT ch.id FROM server_channels ch " +
                    "JOIN server_members m ON m.server_id = ch.server_id AND m.user_id = ? " +
                    "WHERE m.muted_notifs = 1",
            UserSession.effectiveId
        ) { rs -> rs.getInt("id") }.toSet()
    }.getOrDefault(emptySet())

    suspend fun channelNames(): Map<Int, String> = runCatching {
        Db.query(
            "SELECT ch.id, ch.name FROM server_channels ch " +
                    "JOIN server_members m ON m.server_id = ch.server_id AND m.user_id = ?",
            UserSession.effectiveId
        ) { rs -> rs.getInt("id") to rs.str("name") }.toMap()
    }.getOrDefault(emptyMap())

    suspend fun markChannelRead(channelId: Int) {
        runCatching {
            Db.exec(
                "INSERT INTO server_reads (user_id, channel_id, last_read_id) " +
                        "SELECT ?, ?, COALESCE(MAX(id),0) FROM server_messages WHERE channel_id=? " +
                        "ON DUPLICATE KEY UPDATE last_read_id=VALUES(last_read_id)",
                UserSession.effectiveId, channelId, channelId
            )
        }
    }

    suspend fun markServerRead(serverId: Int) {
        runCatching {
            Db.exec(
                "INSERT INTO server_reads (user_id, channel_id, last_read_id) " +
                        "SELECT ?, ch.id, COALESCE((SELECT MAX(sm.id) FROM server_messages sm " +
                        "WHERE sm.channel_id=ch.id),0) FROM server_channels ch WHERE ch.server_id=? " +
                        "ON DUPLICATE KEY UPDATE last_read_id=VALUES(last_read_id)",
                UserSession.effectiveId, serverId
            )
        }
    }

    /**
     * Непрочитанные и упоминания по каждому каналу — одним запросом.
     * Упоминание = @login / @роль-на-этом-сервере / @все|@all|@everyone,
     * либо ответ на моё сообщение.
     */
    suspend fun badges(myLogin: String): List<Badge> {
        val me = UserSession.effectiveId
        if (hasReplyCol == null) hasReplyCol = columnExists("server_messages", "reply_to_id")
        if (hasDeletedCol == null) hasDeletedCol = columnExists("server_messages", "is_deleted")
        if (hasMentionsTbl == null) hasMentionsTbl = tableExists("server_mentions")

        // Слагаемые «что считать упоминанием» собираем списком: части
        // необязательные, а склейка строк с ручной обрезкой «OR» слишком
        // легко даёт битый SQL.
        val mentionParts = buildList {
            if (hasReplyCol == true) {
                add("EXISTS(SELECT 1 FROM server_messages p WHERE p.id = sm.reply_to_id AND p.sender_id = ?)")
            }
            // Упоминания берём из server_mentions (миграция 15). Прежний
            // вариант искал «@логин» через LIKE по sm.text — а текст в БД
            // зашифрован, символа «@» там нет вовсе, и условие не
            // выполнялось никогда.
            if (hasMentionsTbl == true) {
                add("EXISTS(SELECT 1 FROM server_mentions mn WHERE mn.message_id = sm.id AND mn.user_id = ?)")
            }
        }

        // Нет ни таблицы, ни колонки ответов — считать нечего, но SQL обязан
        // остаться валидным: подставляем заведомо ложное условие.
        val mentionExpr = if (mentionParts.isEmpty()) "0=1 "
        else mentionParts.joinToString(" OR ") + " "

        val notDeleted = if (hasDeletedCol == true) "AND sm.is_deleted = 0 " else ""

        val sql = "SELECT sc.server_id, sm.channel_id, mm.muted_notifs, COUNT(*) AS unread, " +
                "SUM(CASE WHEN " + mentionExpr + "THEN 1 ELSE 0 END) AS mentions " +
                "FROM server_messages sm " +
                "JOIN server_channels sc ON sc.id = sm.channel_id " +
                "JOIN server_members mm ON mm.server_id = sc.server_id AND mm.user_id = ? " +
                "LEFT JOIN server_reads r ON r.user_id = ? AND r.channel_id = sm.channel_id " +
                "WHERE sm.sender_id <> ? " + notDeleted +
                "  AND sm.id > COALESCE(r.last_read_id, 0) " +
                "GROUP BY sc.server_id, sm.channel_id, mm.muted_notifs"

        // Порядок подстановок: сначала параметры выражения упоминаний (в том
        // же порядке, что и части списка), затем три @me из JOIN и WHERE.
        val params = buildList {
            repeat(mentionParts.size) { add(me) }
            add(me); add(me); add(me)
        }

        return runCatching {
            Db.query(sql, *params.toTypedArray()) { rs ->
                Badge(
                    serverId = rs.getInt("server_id"),
                    channelId = rs.getInt("channel_id"),
                    unread = rs.getInt("unread"),
                    mentions = rs.getInt("mentions"),
                    muted = rs.bool("muted_notifs"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * ВНИМАНИЕ на порядок плейсхолдеров: `?` для @login стоит первым в
     * SELECT, затем (опционально) параметр подзапроса про ответ, и только
     * потом три `?` для JOIN/WHERE. Именно в этом порядке они и передаются.
     */
    /**
     * Упоминания меня среди новых сообщений канала — запасной путь для
     * уведомлений, когда таблицы server_mentions на сервере нет.
     *
     * Она заводится миграцией 15, а у приложения нет прав на CREATE TABLE:
     * её применяют руками (sql/2026-08-16_server_mentions.sql). Пока этого
     * не сделали, bейдж упоминаний всегда ноль — и уведомление «вас
     * упомянули» не приходило бы никогда. Здесь разбираем расшифрованный
     * текст на месте: дороже, зато работает на любой схеме.
     *
     * Правила те же, что при записи: @логин, @название-роли и @все/@all/
     * @everyone; свои сообщения не считаем.
     */
    suspend fun mentionsAmongNew(channelId: Int, sinceUnread: Int): Int {
        if (sinceUnread < 0) return 0
        val me = UserSession.effectiveId

        val recent = runCatching { channelMessages(channelId, limit = 20) }
            .getOrDefault(emptyList())
        if (recent.isEmpty()) return 0

        val myLogin = runCatching {
            Db.scalarString("SELECT login FROM users WHERE id=?", me).orEmpty().lowercase()
        }.getOrDefault("")

        val myRole = runCatching {
            Db.scalarString(
                "SELECT r.name FROM server_channels sc " +
                        "JOIN server_members m ON m.server_id = sc.server_id AND m.user_id = ? " +
                        "JOIN server_roles r ON r.id = m.role_id WHERE sc.id = ?",
                me, channelId
            ).orEmpty().lowercase()
        }.getOrDefault("")

        return recent.count { msg ->
            if (msg.senderId == me) return@count false
            val lower = msg.text.lowercase()
            if (!lower.contains('@')) return@count false
            lower.contains("@все") || lower.contains("@all") || lower.contains("@everyone") ||
                    (myLogin.isNotEmpty() && lower.contains("@$myLogin")) ||
                    (myRole.isNotBlank() && lower.contains("@$myRole"))
        }
    }

    /**
     * Сколько среди свежих сообщений канала — ОТВЕТЫ на мои.
     *
     * На ПК ответ считается упоминанием наравне с «@логин», но повод у них
     * разный, и в шторке разницу видно: «ответили» и «упомянули» — не одно
     * и то же.
     */
    suspend fun repliesToMeAmongNew(channelId: Int): Int {
        if (hasReplyCol == null) hasReplyCol = columnExists("server_messages", "reply_to_id")
        if (hasReplyCol != true) return 0
        val me = UserSession.effectiveId
        return runCatching {
            Db.scalarInt(
                "SELECT COUNT(*) FROM server_messages sm " +
                        "JOIN server_messages p ON p.id = sm.reply_to_id " +
                        "LEFT JOIN server_reads r ON r.user_id = ? AND r.channel_id = sm.channel_id " +
                        "WHERE sm.channel_id = ? AND sm.sender_id <> ? AND p.sender_id = ? " +
                        "  AND sm.id > COALESCE(r.last_read_id, 0)",
                me, channelId, me, me
            )
        }.getOrDefault(0)
    }

    /** Описание последнего сообщения канала — для текста уведомления. */
    suspend fun previewOfLatestInChannel(channelId: Int): String {
        val last = runCatching { channelMessages(channelId, limit = 1) }
            .getOrDefault(emptyList())
            .lastOrNull() ?: return "Новое сообщение"
        return MessagePreview.withSender(
            last.senderName,
            MessagePreview.describe(
                text = last.text,
                hasImage = last.hasImage,
                hasAudio = last.hasAudio,
                hasVideo = last.hasVideo,
                hasFile = last.hasFile,
                fileName = last.fileName,
            ),
        )
    }

    suspend fun searchInChannel(channelId: Int, query: String, limit: Int = 50): List<ChatMessage> {
        // Текст в БД зашифрован, поэтому LIKE по нему не работает: выбираем
        // страницу сообщений и фильтруем уже расшифрованные на клиенте.
        val recent = channelMessages(channelId, limit = 500)
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return recent.filter { it.text.lowercase().contains(q) }.takeLast(limit)
    }
}
