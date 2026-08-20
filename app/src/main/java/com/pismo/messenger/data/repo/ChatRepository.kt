package com.pismo.messenger.data.repo

import com.pismo.messenger.core.MessagePreview
import com.pismo.messenger.core.Crypto
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.core.buildName
import com.pismo.messenger.data.MediaCache
import com.pismo.messenger.data.MessageMemory
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.db.bool
import com.pismo.messenger.data.db.epochMillis
import com.pismo.messenger.data.db.str
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.Conversation
import com.pismo.messenger.data.model.GroupSummary
import com.pismo.messenger.data.model.ReplyQuote
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.net.SignalingClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Личные и групповые чаты — порт MainForm.cs, MainForm_CachePatch.cs и
 * MainForm_MessageActions.cs.
 *
 * Текст сообщений в БД зашифрован (Crypto), поэтому любой text проходит
 * через enc/dec на границе репозитория — выше по стеку работают с открытым
 * текстом.
 */
object ChatRepository {

    /** Размер страницы истории — как postраничная загрузка на ПК. */
    const val PAGE_SIZE = 40

    /** Видео-файлы до 30 МБ подгружаются сразу для встроенного плеера. */
    private const val INLINE_VIDEO_MAX_BYTES = 30L * 1024 * 1024

    private val VIDEO_EXT = setOf("mp4", "webm", "mov", "mkv", "avi", "3gp", "m4v")

    // ════════════════════════════════════════════════════════════════
    //  СПИСОК ДИАЛОГОВ
    // ════════════════════════════════════════════════════════════════

    /**
     * Диалоги: друзья + все, с кем есть переписка (условие один-в-один
     * с LoadConversations на ПК).
     */
    suspend fun loadConversations(): List<Conversation> {
        val me = UserSession.effectiveId
        val accepted = FriendsRepository.acceptedPredicate("f")
        // ВАЖНО ПРО НАГРУЗКУ НА ДИСК. Прежний вариант джойнил messages
        // условием «(sender=я AND receiver=u.id) OR (sender=u.id AND
        // receiver=я)». OR в ON не даёт использовать индекс, поэтому на
        // КАЖДУЮ строку users шло полное сканирование messages — а вложения
        // лежат там же, в LONGBLOB, то есть с диска поднимались и они. Сверху
        // коррелированный подзапрос за текстом последнего сообщения и EXISTS,
        // тоже на каждого пользователя. Список чатов перечитывается на каждую
        // отметку «прочитано», отсюда полки в десятки МБ/с на ровном тексте.
        //
        // Теперь агрегат считается ОДИН раз по своим сообщениям: две ветки
        // UNION ALL, каждая ложится на свой индекс, а текст последнего
        // сообщения берётся одной выборкой по первичному ключу. Порт запроса
        // с ПК — там его переписали ровно по этой же жалобе.
        //
        // ВТОРОЙ ЗАХОД. Прошлый вариант брал из messages created_at и is_read по
        // КАЖДОМУ своему сообщению — этих колонок нет в индексах, поэтому на
        // каждую строку шёл поход в основную таблицу. Теперь из индекса берётся
        // только максимальный id по собеседнику (id есть в любом индексе InnoDB —
        // он первичный ключ), а время и текст последнего сообщения читаются одной
        // строкой по этому id. Непрочитанные считаются отдельно и только по
        // непрочитанным строкам. Запрос слово в слово совпадает с ПК.
        val sql = """
            SELECT u.id, u.Name, u.Surname, u.login,
                   UNIX_TIMESTAMP(lm.created_at) AS last_time,
                   lm.text AS last_msg,
                   IFNULL(ur.unread, 0) AS unread
            FROM users u
            LEFT JOIN (
                SELECT partner_id, MAX(id) AS last_id
                FROM (
                    SELECT receiver_id AS partner_id, MAX(id) AS id
                    FROM messages WHERE sender_id = ? GROUP BY receiver_id
                    UNION ALL
                    SELECT sender_id AS partner_id, MAX(id) AS id
                    FROM messages WHERE receiver_id = ? GROUP BY sender_id
                ) t
                GROUP BY partner_id
            ) c ON c.partner_id = u.id
            LEFT JOIN messages lm ON lm.id = c.last_id
            LEFT JOIN (
                SELECT sender_id AS partner_id, COUNT(*) AS unread
                FROM messages WHERE receiver_id = ? AND is_read = 0
                GROUP BY sender_id
            ) ur ON ur.partner_id = u.id
            WHERE u.id <> ?
              AND ( c.partner_id IS NOT NULL
                 OR EXISTS (SELECT 1 FROM friends f
                            WHERE $accepted AND ((f.user_id=? AND f.friend_id=u.id)
                                              OR (f.user_id=u.id AND f.friend_id=?))) )
            ORDER BY last_time DESC, u.Name ASC
        """.trimIndent()

        return Db.query(sql, me, me, me, me, me, me) { rs ->
            val lastRaw = rs.getString("last_msg")
            Conversation(
                userId = rs.getInt("id"),
                name = buildName(rs.str("Name"), rs.str("Surname"), rs.str("login")),
                login = rs.str("login"),
                lastMessage = if (lastRaw == null) "" else Crypto.dec(lastRaw),
                lastTimeMs = rs.getLong("last_time").takeIf { it > 0 }?.times(1000),
                unread = rs.getInt("unread"),
            )
        }
    }

    /**
     * Все пользователи — режим админа (LoadAllUsersForAdmin).
     *
     * Себя из списка исключаем: переписки с самим собой в проекте нет, а
     * строка в списке вела в пустой чат и только путала. Обычный список
     * диалогов делает то же самое условием `u.id <> ?`.
     */
    suspend fun loadAllUsers(): List<Conversation> = Db.query(
        "SELECT id, Name, Surname, login, role FROM users WHERE id <> ? ORDER BY Name",
        UserSession.effectiveId
    ) { rs ->
        Conversation(
            userId = rs.getInt("id"),
            name = buildName(rs.str("Name"), rs.str("Surname"), rs.str("login")),
            lastMessage = rs.str("role"),
            lastTimeMs = null,
            unread = 0,
            login = rs.str("login"),
        )
    }

    /**
     * Есть ли у group_chats колонка с датой создания. Значение кэшируем:
     * лезть в information_schema на каждое обновление списка ни к чему.
     */
    @Volatile
    private var groupCreatedCol: String? = null
    @Volatile
    private var groupCreatedColChecked = false

    private suspend fun groupCreatedColumn(): String? {
        if (groupCreatedColChecked) return groupCreatedCol
        groupCreatedCol = listOf("created_at", "createdAt").firstOrNull { col ->
            runCatching {
                Db.scalarInt(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='group_chats' " +
                            "AND COLUMN_NAME=?",
                    col
                ) > 0
            }.getOrDefault(false)
        }
        groupCreatedColChecked = true
        return groupCreatedCol
    }

    /** Группы, где состоит текущий пользователь. */
    suspend fun loadGroups(): List<GroupSummary> {
        val me = UserSession.effectiveId

        // У пустой группы даты сообщений нет, и строка выглядела так, будто
        // время потеряли: у одной группы дата есть, у соседних — пусто.
        // Показываем дату создания группы, если схема её хранит.
        val createdCol = groupCreatedColumn()
        val lastTime = if (createdCol == null) {
            "UNIX_TIMESTAMP((SELECT MAX(gm3.created_at) FROM group_messages gm3 " +
                    "WHERE gm3.group_id = gc.id))"
        } else {
            "COALESCE(" +
                    "UNIX_TIMESTAMP((SELECT MAX(gm3.created_at) FROM group_messages gm3 " +
                    "WHERE gm3.group_id = gc.id)), " +
                    "UNIX_TIMESTAMP(gc.`$createdCol`))"
        }

        val sql = """
            SELECT gc.id, gc.name, gc.avatar_color,
                   (SELECT gm2.text FROM group_messages gm2
                    WHERE gm2.group_id = gc.id
                    ORDER BY gm2.created_at DESC LIMIT 1) AS last_msg,
                   $lastTime AS last_time,
                   (SELECT COUNT(*) FROM group_members gmem2
                    WHERE gmem2.group_id = gc.id) AS member_count
            FROM group_chats gc
            JOIN group_members gmem ON gmem.group_id = gc.id AND gmem.user_id = ?
            ORDER BY last_time DESC, gc.name ASC
        """.trimIndent()

        return Db.query(sql, me) { rs ->
            val lastRaw = rs.getString("last_msg")
            GroupSummary(
                id = rs.getInt("id"),
                name = rs.str("name"),
                lastMessage = if (lastRaw == null) "" else Crypto.dec(lastRaw),
                memberCount = rs.getInt("member_count"),
                avatarColorHex = rs.getString("avatar_color") ?: "#5865F2",
                lastTimeMs = rs.getLong("last_time").takeIf { it > 0 }?.times(1000),
            )
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ИСТОРИЯ СООБЩЕНИЙ
    // ════════════════════════════════════════════════════════════════

    /**
     * Последние [limit] сообщений диалога в хронологическом порядке.
     * Приём тот же, что на ПК: выбираем по id DESC с LIMIT, затем
     * разворачиваем подзапросом в ASC.
     */
    /**
     * Сколько сообщений в переписке начиная с указанного дня — порт счётчика
     * из JumpToDate (MainForm.cs:3323).
     *
     * Нужен, чтобы понять, насколько расширить страницу: лента грузится с
     * конца, и без этого прыжок на дату месячной давности показывал бы
     * пустоту — нужного сообщения просто нет в загруженной странице.
     */
    suspend fun countSince(scope: Scope, target: Int, dayStartMs: Long): Int {
        val me = UserSession.effectiveId
        val seconds = dayStartMs / 1000
        return runCatching {
            if (scope == Scope.GROUP) {
                Db.scalarInt(
                    "SELECT COUNT(*) FROM group_messages " +
                            "WHERE group_id=? AND UNIX_TIMESTAMP(created_at) >= ?",
                    target, seconds
                )
            } else if (scope == Scope.SERVER) {
                // Для канала target — это channel_id. Раньше эта ветка
                // проваливалась в личные сообщения и считала переписку с
                // пользователем под номером канала: «перейти к дате» в
                // канале уводило в никуда.
                Db.scalarInt(
                    "SELECT COUNT(*) FROM server_messages " +
                            "WHERE channel_id=? AND UNIX_TIMESTAMP(created_at) >= ?",
                    target, seconds
                )
            } else {
                Db.scalarInt(
                    "SELECT COUNT(*) FROM messages WHERE ((sender_id=? AND receiver_id=?) " +
                            "OR (sender_id=? AND receiver_id=?)) AND UNIX_TIMESTAMP(created_at) >= ?",
                    me, target, target, me, seconds
                )
            }
        }.getOrDefault(0)
    }

    suspend fun loadDirectMessages(partnerId: Int, limit: Int = PAGE_SIZE, beforeId: Int = 0): List<ChatMessage> {
        val me = UserSession.effectiveId
        val cursor = if (beforeId > 0) "AND id < $beforeId " else ""

        /*
         * СНАЧАЛА ТОЛЬКО НОМЕРА, И ТОЛЬКО СОРОК ШТУК.
         *
         * Было: одно условие «(я→он) OR (он→я)» с ORDER BY id DESC LIMIT 40.
         * Ни один индекс не покрывает OR целиком, поэтому сервер собирал
         * объединением ВСЮ переписку и сортировал её в файле, чтобы отдать
         * сорок последних строк. На переписке в десятки тысяч сообщений это
         * значит поднять с диска десятки тысяч строк — а строки здесь
         * широкие, вложения лежат в этой же таблице. И так на каждый заход в
         * чат и на каждое обновление ленты.
         *
         * Стало: две отдельные ветки, каждая — своё направление переписки.
         * Каждая ложится на (sender_id, receiver_id, id) и берёт ровно сорок
         * последних движением к концу индекса. Итого не больше восьмидесяти
         * строк, из которых внешний LIMIT оставит сорок.
         *
         * UNION, а не UNION ALL: если переписку открыли с самим собой, обе
         * ветки совпадают, и без слияния сообщения задвоились бы.
         */
        val keys = """
            (SELECT id FROM messages
              WHERE sender_id=? AND receiver_id=? $cursor
              ORDER BY id DESC LIMIT $limit)
            UNION
            (SELECT id FROM messages
              WHERE sender_id=? AND receiver_id=? $cursor
              ORDER BY id DESC LIMIT $limit)
        """.trimIndent()

        val inner = """
            SELECT m.id, m.sender_id, m.text, m.file_name,
                   m.reply_to_id, m.is_deleted, m.edited_at, m.is_read,
                   UNIX_TIMESTAMP(m.created_at) AS created_ts,
                   TRIM(CONCAT(u.Name,' ',u.Surname)) AS sender_name, u.login,
                   (m.image_data IS NOT NULL) AS has_img,
                   (m.audio_data IS NOT NULL) AS has_audio,
                   (m.video_data IS NOT NULL) AS has_video,
                   (m.file_data  IS NOT NULL) AS has_file
                   -- Размер вложения здесь НЕ считаем. OCTET_LENGTH по
                   -- LONGBLOB заставляет сервер поднять вложение с диска
                   -- целиком, а лента перечитывается каждые 2.5 секунды:
                   -- на большой переписке это и были те самые полки
                   -- нагрузки на диск. Столбец при этом никто не читал.
                   -- Размер нужен только карточке файла — она берёт его
                   -- отдельным запросом по одному сообщению (fileSize).
            FROM ($keys) k
            JOIN messages m ON m.id = k.id
            JOIN users u ON u.id = m.sender_id
            ORDER BY m.id DESC LIMIT $limit
        """.trimIndent()
        val sql = "SELECT * FROM ($inner) sub ORDER BY id ASC"

        return Db.query(sql, me, partnerId, partnerId, me) { rs -> mapMessage(rs, Scope.DM) }
    }

    suspend fun loadGroupMessages(groupId: Int, limit: Int = PAGE_SIZE, beforeId: Int = 0): List<ChatMessage> {
        val cursor = if (beforeId > 0) "AND gm.id < $beforeId " else ""
        val inner = """
            SELECT gm.id, gm.sender_id, gm.text, gm.file_name,
                   gm.reply_to_id, gm.is_deleted, gm.edited_at,
                   UNIX_TIMESTAMP(gm.created_at) AS created_ts,
                   TRIM(CONCAT(u.Name,' ',u.Surname)) AS sender_name, u.login,
                   (gm.image_data IS NOT NULL) AS has_img,
                   (gm.audio_data IS NOT NULL) AS has_audio,
                   (gm.video_data IS NOT NULL) AS has_video,
                   (gm.file_data  IS NOT NULL) AS has_file
            FROM group_messages gm
            JOIN users u ON u.id = gm.sender_id
            WHERE gm.group_id=? $cursor
            ORDER BY gm.id DESC LIMIT $limit
        """.trimIndent()
        val sql = "SELECT * FROM ($inner) sub ORDER BY id ASC"

        return Db.query(sql, groupId) { rs -> mapMessage(rs, Scope.GROUP) }
    }

    private fun mapMessage(rs: java.sql.ResultSet, scope: Scope): ChatMessage {
        val senderName = rs.str("sender_name").trim().ifBlank { rs.str("login") }
        val hasIsRead = runCatching { rs.findColumn("is_read"); true }.getOrDefault(false)
        return ChatMessage(
            id = rs.getInt("id"),
            senderId = rs.getInt("sender_id"),
            senderName = senderName,
            text = Crypto.dec(rs.getString("text")),
            createdAtMs = rs.epochMillis("created_ts"),
            replyToId = rs.getInt("reply_to_id"),
            isDeleted = rs.bool("is_deleted"),
            isEdited = rs.getString("edited_at") != null,
            hasImage = rs.bool("has_img"),
            hasAudio = rs.bool("has_audio"),
            hasVideo = rs.bool("has_video"),
            hasFile = rs.bool("has_file"),
            fileName = rs.getString("file_name"),
            scope = scope,
            isRead = if (hasIsRead) rs.bool("is_read") else true,
        )
    }

    /** Мини-цитата сообщения, на которое отвечают. */
    suspend fun loadReplyQuote(replyToId: Int, scope: Scope): ReplyQuote? {
        if (replyToId <= 0) return null
        val sql = """
            SELECT m.text, TRIM(CONCAT(u.Name,' ',u.Surname)) AS sname, u.login
            FROM ${scope.table} m JOIN users u ON u.id = m.sender_id
            WHERE m.id = ?
        """.trimIndent()
        return Db.queryFirst(sql, replyToId) { rs ->
            ReplyQuote(
                messageId = replyToId,
                sender = rs.str("sname").trim().ifBlank { rs.str("login") },
                text = Crypto.dec(rs.getString("text")),
            )
        }
    }

    // ── Счётчики для polling ──────────────────────────────────────────
    /**
     * Самый большой id в переписке — по нему опрос и узнаёт о новом.
     *
     * ПОЧЕМУ НЕ COUNT(*). Счёт обязан пройти по ВСЕМ строкам переписки, и
     * делал он это каждые 2.5 секунды у каждого открытого чата — на большой
     * истории это и есть та самая постоянная нагрузка. MAX(id) по индексу
     * (sender_id, receiver_id, id) — одно движение к концу индекса,
     * независимо от размера переписки.
     *
     * Две ветки UNION ALL, а не OR: с OR ни одна из них на индекс не ляжет.
     */
    suspend fun directMaxId(partnerId: Int): Int {
        val me = UserSession.effectiveId
        return Db.scalarInt(
            "SELECT MAX(id) FROM (" +
                    "(SELECT MAX(id) AS id FROM messages WHERE sender_id=? AND receiver_id=?) " +
                    "UNION ALL " +
                    "(SELECT MAX(id) AS id FROM messages WHERE sender_id=? AND receiver_id=?)" +
                    ") t",
            me, partnerId, partnerId, me
        )
    }

    /** То же для группы: MAX по индексу вместо счёта всех строк. */
    suspend fun groupMaxId(groupId: Int): Int =
        Db.scalarInt("SELECT MAX(id) FROM group_messages WHERE group_id=?", groupId)

    suspend fun directMessageCount(partnerId: Int): Int {
        val me = UserSession.effectiveId
        return Db.scalarInt(
            "SELECT COUNT(*) FROM messages WHERE (sender_id=? AND receiver_id=?) " +
                    "OR (sender_id=? AND receiver_id=?)",
            me, partnerId, partnerId, me
        )
    }

    suspend fun groupMessageCount(groupId: Int): Int =
        Db.scalarInt("SELECT COUNT(*) FROM group_messages WHERE group_id=?", groupId)

    /** Непрочитанные по отправителям, сразу исключая блокировки (один запрос). */
    suspend fun unreadBySender(): Map<Int, Int> {
        val me = UserSession.effectiveId
        val sql = """
            SELECT m.sender_id, COUNT(*) AS cnt FROM messages m
            WHERE m.receiver_id=? AND m.is_read=0
              AND NOT EXISTS (SELECT 1 FROM user_blocks b WHERE
                    (b.blocker_id=? AND b.blocked_id=m.sender_id) OR
                    (b.blocker_id=m.sender_id AND b.blocked_id=?))
            GROUP BY m.sender_id
        """.trimIndent()
        return Db.query(sql, me, me, me) { rs -> rs.getInt("sender_id") to rs.getInt("cnt") }.toMap()
    }

    /**
     * Описание последнего непрочитанного сообщения от отправителя — для
     * текста уведомления. Раньше в шторке было безликое «Новых сообщений: N»,
     * по которому нельзя понять, прислали текст, фото или документ.
     *
     * BLOB-ы не тянем: только флаги наличия через IS NOT NULL, иначе
     * уведомление о присланном архиве качало бы этот архив целиком.
     */
    suspend fun previewOfLatestFrom(senderId: Int): String {
        val me = UserSession.effectiveId
        val sql = """
            SELECT text, file_name,
                   image_data IS NOT NULL AS has_img,
                   audio_data IS NOT NULL AS has_aud,
                   video_data IS NOT NULL AS has_vid,
                   file_data  IS NOT NULL AS has_file
            FROM messages
            WHERE receiver_id=? AND sender_id=? AND is_read=0
            ORDER BY id DESC LIMIT 1
        """.trimIndent()
        return runCatching {
            Db.queryFirst(sql, me, senderId) { rs ->
                MessagePreview.describe(
                    text = Crypto.dec(rs.getString("text").orEmpty()),
                    hasImage = rs.bool("has_img"),
                    hasAudio = rs.bool("has_aud"),
                    hasVideo = rs.bool("has_vid"),
                    hasFile = rs.bool("has_file"),
                    fileName = rs.getString("file_name"),
                )
            }
        }.getOrNull() ?: "Новое сообщение"
    }

    /** То же для группы — по последнему чужому сообщению. */
    suspend fun previewOfLatestInGroup(groupId: Int): String {
        val me = UserSession.effectiveId
        val sql = """
            SELECT gm.text, gm.file_name,
                   gm.image_data IS NOT NULL AS has_img,
                   gm.audio_data IS NOT NULL AS has_aud,
                   gm.video_data IS NOT NULL AS has_vid,
                   gm.file_data  IS NOT NULL AS has_file,
                   TRIM(CONCAT(u.Name,' ',u.Surname)) AS sender_name, u.login
            FROM group_messages gm
            JOIN users u ON u.id = gm.sender_id
            WHERE gm.group_id=? AND gm.sender_id<>? AND gm.is_deleted=0
            ORDER BY gm.id DESC LIMIT 1
        """.trimIndent()
        return runCatching {
            Db.queryFirst(sql, groupId, me) { rs ->
                MessagePreview.withSender(
                    rs.str("sender_name").trim().ifBlank { rs.str("login") },
                    MessagePreview.describe(
                        text = Crypto.dec(rs.getString("text").orEmpty()),
                        hasImage = rs.bool("has_img"),
                        hasAudio = rs.bool("has_aud"),
                        hasVideo = rs.bool("has_vid"),
                        hasFile = rs.bool("has_file"),
                        fileName = rs.getString("file_name"),
                    ),
                )
            }
        }.getOrNull() ?: "Новое сообщение"
    }

    /** Максимальный id чужого сообщения по каждой группе — база для пушей. */
    suspend fun groupMaxIncoming(): Map<Int, Pair<Int, String>> {
        val me = UserSession.effectiveId
        val sql = """
            SELECT gc.id, gc.name, COALESCE(MAX(gm.id),0) AS max_id
            FROM group_chats gc
            JOIN group_members mem ON mem.group_id = gc.id AND mem.user_id = ?
            LEFT JOIN group_messages gm ON gm.group_id = gc.id
                 AND gm.sender_id <> ? AND gm.is_deleted = 0
            GROUP BY gc.id, gc.name
        """.trimIndent()
        return Db.query(sql, me, me) { rs ->
            rs.getInt("id") to (rs.getInt("max_id") to rs.str("name").ifBlank { "группа" })
        }.toMap()
    }

    suspend fun markAsRead(partnerId: Int) {
        runCatching {
            val affected = Db.exec(
                "UPDATE messages SET is_read=1 WHERE sender_id=? AND receiver_id=? AND is_read=0",
                partnerId, UserSession.effectiveId
            )
            // Говорим отправителю, что его сообщения прочитаны. Без этого синяя
            // галочка на ПК не появлялась ВООБЩЕ: его опрос следит за числом
            // сообщений открытого чата, а прочтение число не меняет — он узнавал
            // о нём только из этого события, которого мы не слали. Раскладка
            // полей взята с ПК (MarkAsRead): в userId едет тот, кто прочитал.
            if (affected > 0) {
                SignalingClient.send("read", partnerId, UserSession.effectiveId, "direct")
            }
        }
    }

    suspend fun markAllAsRead() {
        runCatching {
            Db.exec(
                "UPDATE messages SET is_read=1 WHERE receiver_id=? AND is_read=0",
                UserSession.effectiveId
            )
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ОТПРАВКА
    // ════════════════════════════════════════════════════════════════

    /**
     * Отправка сообщения. Возвращает id новой строки.
     *
     * Крупный файл заливается так же, как на ПК: сначала строка метаданных
     * (чтобы получатель увидел сообщение сразу), затем один UPDATE с blob,
     * а если пакет не пролезает в max_allowed_packet — дозапись кусками по
     * 4 МБ через CONCAT.
     */
    suspend fun sendMessage(
        scope: Scope,
        target: Int,                 // partnerId для ЛС, groupId для группы
        text: String,
        image: ByteArray? = null,
        audio: ByteArray? = null,
        video: ByteArray? = null,
        file: ByteArray? = null,
        fileName: String? = null,
        replyToId: Int = 0,
        onProgress: ((Float) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        val me = UserSession.effectiveId
        val table = scope.table
        val encText = Crypto.enc(text)
        val reply = if (replyToId > 0) replyToId else null

        val newId = if (scope == Scope.GROUP) {
            Db.insert(
                "INSERT INTO group_messages (group_id, sender_id, text, image_data, audio_data, " +
                        "video_data, file_data, file_name, reply_to_id) VALUES (?,?,?,?,?,?,NULL,?,?)",
                target, me, encText, image, audio, video, fileName, reply
            )
        } else {
            Db.insert(
                "INSERT INTO messages (sender_id, receiver_id, text, image_data, audio_data, " +
                        "video_data, file_data, file_name, reply_to_id) VALUES (?,?,?,?,?,?,NULL,?,?)",
                me, target, encText, image, audio, video, fileName, reply
            )
        }

        if (file != null && file.isNotEmpty() && newId > 0) {
            uploadFileData(table, newId, file, onProgress)
        }

        // Своё вложение кладём в кеш прямо здесь. Байты уже на руках, а без
        // этого открытие собственной картинки тянуло её обратно из базы —
        // то есть отправитель платил за неё дважды.
        cacheOwnAttachment(newId, image, audio, video, file, fileName)

        newId
    }

    /** Кладёт только что отправленное вложение в кеш под новым id сообщения. */
    private fun cacheOwnAttachment(
        msgId: Int,
        image: ByteArray?,
        audio: ByteArray?,
        video: ByteArray?,
        file: ByteArray?,
        fileName: String?,
    ) {
        if (msgId <= 0) return
        runCatching {
            image?.takeIf { it.isNotEmpty() }?.let { MediaCache.put(msgId, "img", it, fileName) }
            audio?.takeIf { it.isNotEmpty() }?.let { MediaCache.put(msgId, "audio", it) }
            video?.takeIf { it.isNotEmpty() }?.let { MediaCache.put(msgId, "video", it) }
            file?.takeIf { it.isNotEmpty() }?.let { MediaCache.put(msgId, "file", it, fileName) }
        }
    }

    /**
     * Запись большого вложения в blob.
     *
     * internal, потому что тем же путём кладут файлы и каналы серверов:
     * двести мегабайт одним пакетом сервер не примет (max_allowed_packet
     * обычно кратно меньше), а два разных способа дозаписи в одном
     * приложении — это два разных набора граблей.
     */
    internal suspend fun uploadFileData(
        table: String,
        msgId: Int,
        data: ByteArray,
        onProgress: ((Float) -> Unit)? = null,
    ) {
        // Сессионные таймауты: запись большого blob легко выходит за
        // дефолтные 30 секунд, и сервер рвёт соединение посреди команды.
        runCatching {
            Db.exec("SET SESSION net_read_timeout=600, net_write_timeout=600, wait_timeout=600")
        }

        val single = runCatching {
            Db.exec("UPDATE $table SET file_data=? WHERE id=?", data, msgId)
        }
        if (single.isSuccess) {
            onProgress?.invoke(1f)
            return
        }

        // Пакет великоват — дозапись порциями.
        Db.exec("UPDATE $table SET file_data=NULL WHERE id=?", msgId)
        val chunk = 4 * 1024 * 1024
        var off = 0
        while (off < data.size) {
            val len = minOf(chunk, data.size - off)
            val part = data.copyOfRange(off, off + len)
            Db.exec(
                "UPDATE $table SET file_data = CONCAT(IFNULL(file_data, _binary''), ?) WHERE id=?",
                part, msgId
            )
            off += len
            onProgress?.invoke(off.toFloat() / data.size)
        }
    }

    /** Удаление строки — откат после отмены отправки файла. */
    suspend fun deleteMessageRow(scope: Scope, msgId: Int) {
        runCatching { Db.exec("DELETE FROM ${scope.table} WHERE id=?", msgId) }
    }

    // ════════════════════════════════════════════════════════════════
    //  РЕДАКТИРОВАНИЕ / УДАЛЕНИЕ / ПЕРЕСЫЛКА
    // ════════════════════════════════════════════════════════════════

    /** Правка с сохранением прежнего текста в message_edits. */
    suspend fun editMessage(scope: Scope, msgId: Int, newText: String) {
        val old = Db.scalarString("SELECT text FROM ${scope.table} WHERE id=?", msgId)
        if (old != null) {
            runCatching {
                Db.exec(
                    "INSERT INTO message_edits (message_id, scope, old_text) VALUES (?,?,?)",
                    msgId, scope.db, old
                )
            }
        }
        Db.exec(
            "UPDATE ${scope.table} SET text=?, edited_at=NOW() WHERE id=?",
            Crypto.enc(newText), msgId
        )
    }

    /** История правок (старый текст сохранён зашифрованным). */
    suspend fun editHistory(scope: Scope, msgId: Int): List<Pair<String, Long>> = Db.query(
        "SELECT old_text, UNIX_TIMESTAMP(edited_at) AS ts FROM message_edits " +
                "WHERE message_id=? AND scope=? ORDER BY edited_at DESC",
        msgId, scope.db
    ) { rs -> Crypto.dec(rs.getString("old_text")) to rs.epochMillis("ts") }

    /** Мягкое удаление — как на ПК: флаг + текст-заглушка + очистка медиа. */
    suspend fun deleteMessage(scope: Scope, msgId: Int, fileName: String? = null) {
        if (scope == Scope.SERVER) {
            Db.exec("DELETE FROM server_messages WHERE id=?", msgId)
        } else {
            Db.exec(
                "UPDATE ${scope.table} SET is_deleted=1, text=?, " +
                        "image_data=NULL, audio_data=NULL, video_data=NULL WHERE id=?",
                Crypto.enc("[сообщение удалено]"), msgId
            )
        }
        MediaCache.invalidateAll(msgId, fileName)
    }

    /**
     * Пересылка. BLOB-ы копируются прямо в SQL через INSERT … SELECT —
     * байты не гоняются через клиент (порт ForwardHelper.cs).
     */
    suspend fun forwardMessage(
        srcScope: Scope, srcId: Int,
        dstScope: Scope, dstTarget: Int,
        captionText: String,
    ) {
        val me = UserSession.effectiveId
        val (dstCols, dstVals, params) = when (dstScope) {
            Scope.DM -> Triple("sender_id, receiver_id", "?, ?", listOf(me, dstTarget))
            Scope.GROUP -> Triple("group_id, sender_id", "?, ?", listOf(dstTarget, me))
            Scope.SERVER -> Triple("channel_id, sender_id", "?, ?", listOf(dstTarget, me))
        }
        val enc = Crypto.enc(captionText)

        val ok = runCatching {
            Db.exec(
                "INSERT INTO ${dstScope.table} ($dstCols, text, image_data, audio_data, " +
                        "video_data, file_data, file_name) " +
                        "SELECT $dstVals, ?, image_data, audio_data, video_data, file_data, file_name " +
                        "FROM ${srcScope.table} WHERE id=?",
                *(params + listOf(enc, srcId)).toTypedArray()
            )
        }

        // Источник без медиа-колонок (например, канал сервера) — шлём только текст.
        if (ok.isFailure) {
            Db.exec(
                "INSERT INTO ${dstScope.table} ($dstCols, text) VALUES ($dstVals, ?)",
                *(params + listOf(enc)).toTypedArray()
            )
        }
    }

    /** Удаление всей переписки с собеседником. */
    suspend fun deleteConversation(partnerId: Int) {
        val me = UserSession.effectiveId
        Db.exec(
            "DELETE FROM messages WHERE (sender_id=? AND receiver_id=?) OR (sender_id=? AND receiver_id=?)",
            me, partnerId, partnerId, me
        )
        // Иначе очищенная переписка ещё раз мелькнёт из памяти при
        // следующем открытии чата — до того, как приедет пустой ответ.
        MessageMemory.invalidate(Scope.DM, partnerId)
    }

    // ════════════════════════════════════════════════════════════════
    //  МЕДИА
    // ════════════════════════════════════════════════════════════════

    /**
     * Пакетная предзагрузка картинок и голосовых для целой страницы —
     * один IN-запрос вместо запроса на каждое сообщение (порт
     * PrefetchPageMedia). Видео и файлы намеренно не трогаем: они большие.
     */
    suspend fun prefetchPageMedia(messages: List<ChatMessage>, scope: Scope) {
        val ids = messages.filter { m ->
            (m.hasImage && !MediaCache.has(m.id, "img", m.fileName)) ||
                    (m.hasAudio && !MediaCache.has(m.id, "audio"))
        }.map { it.id }
        if (ids.isEmpty()) return

        runCatching {
            val list = ids.joinToString(",")
            Db.use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT id, image_data, audio_data, file_name FROM ${scope.table} WHERE id IN ($list)"
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

    /** Картинка сообщения — из кеша либо из БД. */
    suspend fun loadImage(msgId: Int, scope: Scope, fileName: String?): ByteArray? =
        loadBlob(msgId, scope, "image_data", "img", fileName)

    suspend fun loadAudio(msgId: Int, scope: Scope): ByteArray? =
        loadBlob(msgId, scope, "audio_data", "audio", null)

    suspend fun loadVideoCircle(msgId: Int, scope: Scope): ByteArray? =
        loadBlob(msgId, scope, "video_data", "video", null)

    suspend fun loadFile(msgId: Int, scope: Scope, fileName: String?): ByteArray? =
        loadBlob(msgId, scope, "file_data", "file", fileName)

    private suspend fun loadBlob(
        msgId: Int, scope: Scope, column: String, kind: String, fileName: String?,
    ): ByteArray? {
        MediaCache.get(msgId, kind, fileName)?.let { return it }
        val data = runCatching {
            Db.scalarBytes("SELECT $column FROM ${scope.table} WHERE id=?", msgId)
        }.getOrNull()
        if (data != null && data.isNotEmpty()) MediaCache.put(msgId, kind, data, fileName)
        return data
    }

    /**
     * Сколько МОИХ сообщений этому собеседнику ещё не прочитано.
     *
     * Нужно ровно для галочек. Опрос переписки следит за ЧИСЛОМ сообщений, а
     * прочтение число не меняет — поэтому синие галочки появлялись только
     * тогда, когда кто-нибудь напишет следующее сообщение, и выглядело это
     * как «долго помечает». Запрос ложится на idx_msg_recv_read
     * (receiver_id, is_read, sender_id) целиком и стоит копейки.
     */
    suspend fun unreadToPartner(partnerId: Int): Int = Db.scalarInt(
        "SELECT COUNT(*) FROM messages WHERE receiver_id=? AND is_read=0 AND sender_id=?",
        partnerId, UserSession.effectiveId
    )

    suspend fun fileSize(msgId: Int, scope: Scope): Long =
        Db.scalarLong("SELECT OCTET_LENGTH(file_data) FROM ${scope.table} WHERE id=?", msgId)

    /**
     * Поиск по переписке.
     *
     * LIKE по колонке text бесполезен: в БД лежит шифртекст. Поэтому
     * берём последние [depth] сообщений и фильтруем уже расшифрованные на
     * клиенте — единственный способ, пока текст зашифрован общим ключом.
     */
    suspend fun searchInChat(
        scope: Scope,
        target: Int,
        query: String,
        depth: Int = 500,
    ): List<ChatMessage> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        val recent = when (scope) {
            Scope.GROUP -> loadGroupMessages(target, limit = depth)
            else -> loadDirectMessages(target, limit = depth)
        }
        // По тексту И по автору — как на ПК: там строка поиска склеивается
        // из текста сообщения и имени отправителя, поэтому «что писал
        // Петров» находится в групповом чате одним словом.
        return recent.filter {
            !it.isDeleted &&
                (it.text.lowercase().contains(q) || it.senderName.lowercase().contains(q))
        }
    }

    /** Стоит ли показать встроенный плеер сразу (видео-файл до 30 МБ). */
    fun isInlineVideo(fileName: String?, sizeBytes: Long): Boolean {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in VIDEO_EXT && sizeBytes in 1..INLINE_VIDEO_MAX_BYTES
    }

    // ════════════════════════════════════════════════════════════════
    //  БЛОКИРОВКИ
    // ════════════════════════════════════════════════════════════════

    suspend fun isBlocked(blockerId: Int, blockedId: Int): Boolean = runCatching {
        Db.exists(
            "SELECT 1 FROM user_blocks WHERE blocker_id=? AND blocked_id=? LIMIT 1",
            blockerId, blockedId
        )
    }.getOrDefault(false)

    /** Есть ли блокировка в любую сторону — тогда чат в режиме «только чтение». */
    suspend fun blockState(partnerId: Int): Pair<Boolean, Boolean> {
        val me = UserSession.effectiveId
        return isBlocked(me, partnerId) to isBlocked(partnerId, me)
    }

    suspend fun block(blockedId: Int) {
        runCatching {
            Db.exec(
                "CREATE TABLE IF NOT EXISTS user_blocks (id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "blocker_id INT NOT NULL, blocked_id INT NOT NULL, " +
                        "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                        "UNIQUE KEY ux_block (blocker_id, blocked_id)) " +
                        "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            )
        }
        Db.exec(
            "INSERT IGNORE INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)",
            UserSession.effectiveId, blockedId
        )
    }

    suspend fun unblock(blockedId: Int) {
        Db.exec(
            "DELETE FROM user_blocks WHERE blocker_id=? AND blocked_id=?",
            UserSession.effectiveId, blockedId
        )
    }
}
