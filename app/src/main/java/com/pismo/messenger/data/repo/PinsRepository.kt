package com.pismo.messenger.data.repo

import com.pismo.messenger.core.Crypto
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.db.str
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.net.SignalingClient

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

    /**
     * Почему последняя операция не удалась, или null.
     *
     * Здесь всё завёрнуто в runCatching и возвращает false — закреп не то,
     * ради чего стоит ронять экран. Но «не удалось» и «открепил» выглядели
     * СНАРУЖИ одинаково: нажал «Закрепить», ничего не произошло, и понять, в
     * чём дело — нет прав на таблицу, нет самой таблицы, нет связи, — было
     * нельзя ни по чему.
     */
    @Volatile
    var lastError: String? = null
        private set

    /** Тумблер закрепа. true — после операции сообщение закреплено. */
    suspend fun toggle(messageId: Int, scope: Scope): Boolean = runCatching {
        lastError = null
        val nowPinned = if (isPinned(messageId, scope)) {
            Db.exec("DELETE FROM pinned_messages WHERE message_id=? AND scope=?", messageId, scope.db)
            false
        } else {
            Db.exec(
                "INSERT IGNORE INTO pinned_messages (message_id, scope, pinned_by) VALUES (?, ?, ?)",
                messageId, scope.db, UserSession.effectiveId
            )
            true
        }
        announce(messageId)
        nowPinned
    }.getOrElse { e -> lastError = e.message ?: e.toString(); false }

    /**
     * Сказать остальным, что закрепы изменились.
     *
     * Отсюда, а не от кнопки: закрепляют из двух мест — меню пузыря и список
     * закреплённых, — и каждое пришлось бы помнить об этом отдельно. Здесь
     * место одно, и забыть негде.
     *
     * Широковещательно, как new_message: закреп сообщения виден обеим
     * сторонам переписки, и перечитать его должен каждый, у кого этот чат
     * открыт. (В отличие от закреплённых ЧАТОВ — те личные, и событие о них
     * уходит только своим же устройствам.)
     */
    private fun announce(messageId: Int) {
        runCatching { SignalingClient.send("pin", 0, messageId, "") }
    }

    /**
     * Отпечаток закрепов ОТКРЫТОГО чата — чтобы опрос замечал правку, до
     * которой событие не дошло (клиент мог быть не на связи в этот момент).
     *
     * Считается по одному чату, а не по всей таблице. Общий отпечаток менялся
     * от любого закрепа любого человека в любой переписке, и каждый, у кого
     * открыт хоть какой-то чат, получал полную перезагрузку — из-за события,
     * которое его не касается.
     *
     * CAST обязателен: SUM() от целой колонки MySQL возвращает DECIMAL. JDBC
     * его к long приводит сам, но на ПК тот же запрос читался строго и падал —
     * пусть тип будет одинаковым и однозначным на обеих сторонах.
     */
    suspend fun fingerprint(scope: Scope, chatId: Int): String = runCatching {
        val head = "SELECT COUNT(*) AS n, CAST(COALESCE(SUM(p.message_id),0) AS SIGNED) AS s " +
                "FROM pinned_messages p JOIN ${scope.table} t ON t.id = p.message_id WHERE p.scope=? AND "
        // Чем чат опознаётся в своей таблице: у группы и канала это одна
        // колонка, у переписки — пара отправитель/получатель в обе стороны.
        val sql = when (scope) {
            Scope.GROUP -> head + "t.group_id=?"
            Scope.SERVER -> head + "t.channel_id=?"
            Scope.DM -> head + "((t.sender_id=? AND t.receiver_id=?) OR (t.sender_id=? AND t.receiver_id=?))"
        }
        val args: Array<Any> = when (scope) {
            Scope.DM -> arrayOf(scope.db, UserSession.effectiveId, chatId, chatId, UserSession.effectiveId)
            else -> arrayOf(scope.db, chatId)
        }
        Db.queryFirst(sql, *args) { rs ->
            rs.getLong("n").toString() + ":" + rs.getLong("s")
        } ?: ""
    }.getOrDefault("")

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
