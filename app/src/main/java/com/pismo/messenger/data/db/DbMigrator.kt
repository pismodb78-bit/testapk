package com.pismo.messenger.data.db

import android.util.Log
import java.sql.Connection
import java.sql.SQLException

/**
 * Порт PISMO/DbMigrator.cs — версионированные миграции схемы.
 *
 * Список и номера миграций обязаны совпадать с ПК-версией: обе программы
 * пишут в один и тот же журнал `schema_migrations`, поэтому уже применённая
 * на ПК миграция здесь просто пропускается, и наоборот.
 *
 * Если у учётной записи нет прав на ALTER (на этом хостинге так и есть),
 * миграция тихо не применяется и не отмечается — ровно как на ПК. Это не
 * ошибка: соответствующие SQL-файлы выполняются администратором вручную.
 */
object DbMigrator {

    private const val TAG = "DbMigrator"

    @Volatile private var done = false

    private data class Migration(val id: Int, val name: String, val up: (Connection) -> Unit)

    private val migrations: List<Migration> = listOf(
        Migration(1, "friends: заявки + status") { c ->
            exec(c, "CREATE TABLE IF NOT EXISTS friends (" +
                    "user_id INT NOT NULL, friend_id INT NOT NULL, " +
                    "status TINYINT NOT NULL DEFAULT 0, " +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "PRIMARY KEY (user_id, friend_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
            if (!columnExists(c, "friends", "status"))
                exec(c, "ALTER TABLE friends ADD COLUMN status TINYINT NOT NULL DEFAULT 1")
        },
        Migration(2, "user_prefs: приватность ЛС") { c ->
            exec(c, "CREATE TABLE IF NOT EXISTS user_prefs (" +
                    "user_id INT NOT NULL PRIMARY KEY, " +
                    "dm_privacy TINYINT NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
        },
        Migration(3, "users.dm_privacy (запасное хранилище)") { c ->
            if (tableExists(c, "users") && !columnExists(c, "users", "dm_privacy"))
                exec(c, "ALTER TABLE users ADD COLUMN dm_privacy TINYINT NOT NULL DEFAULT 0")
        },
        Migration(4, "message_reactions: реакции на сообщения") { c ->
            exec(c, "CREATE TABLE IF NOT EXISTS message_reactions (" +
                    "message_id INT NOT NULL, scope TINYINT NOT NULL DEFAULT 0, " +
                    "user_id INT NOT NULL, emoji VARCHAR(16) NOT NULL, " +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "PRIMARY KEY (message_id, scope, user_id, emoji), " +
                    "KEY idx_react_msg (message_id, scope)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
        },
        Migration(5, "pinned_messages: закреплённые сообщения") { c ->
            exec(c, "CREATE TABLE IF NOT EXISTS pinned_messages (" +
                    "message_id INT NOT NULL, scope TINYINT NOT NULL DEFAULT 0, " +
                    "pinned_by INT NOT NULL, " +
                    "pinned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "PRIMARY KEY (message_id, scope)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
        },
        Migration(6, "message_edits: история изменений сообщений") { c ->
            exec(c, "CREATE TABLE IF NOT EXISTS message_edits (" +
                    "id INT NOT NULL AUTO_INCREMENT, message_id INT NOT NULL, " +
                    "scope TINYINT NOT NULL DEFAULT 0, old_text TEXT NULL, " +
                    "edited_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "PRIMARY KEY (id), KEY idx_edits_msg (message_id, scope)) " +
                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
        },
        Migration(7, "message_reactions: PK с эмодзи") { c ->
            if (!emojiInPrimaryKey(c))
                exec(c, "ALTER TABLE message_reactions DROP PRIMARY KEY, " +
                        "ADD PRIMARY KEY (message_id, scope, user_id, emoji)")
        },
        Migration(8, "server_reads: метки прочитанного в каналах серверов") { c ->
            exec(c, "CREATE TABLE IF NOT EXISTS server_reads (" +
                    "user_id INT NOT NULL, channel_id INT NOT NULL, " +
                    "last_read_id INT NOT NULL DEFAULT 0, " +
                    "read_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                    "PRIMARY KEY (user_id, channel_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
        },
        Migration(9, "message_reactions.emoji: бинарная коллация") { c ->
            // В utf8mb4_general_ci РАЗНЫЕ эмодзи сравниваются как равные —
            // из-за этого тумблер реакции снимал чужую реакцию. utf8mb4_bin
            // сравнивает по кодпоинтам.
            exec(c, "ALTER TABLE message_reactions " +
                    "MODIFY emoji VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL")
        },
        Migration(10, "message_reactions: пересборка PK после бинарной коллации") { c ->
            runCatching {
                exec(c, "ALTER TABLE message_reactions " +
                        "MODIFY emoji VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL")
            }
            if (!emojiInPrimaryKey(c)) {
                if (hasPrimaryKey(c)) runCatching {
                    exec(c, "ALTER TABLE message_reactions DROP PRIMARY KEY")
                }
                exec(c, "ALTER TABLE message_reactions " +
                        "ADD PRIMARY KEY (message_id, scope, user_id, emoji)")
            }
        },
        Migration(11, "voice_presence: значки мьюта микрофона/наушников") { c ->
            if (tableExists(c, "voice_presence")) {
                if (!columnExists(c, "voice_presence", "mic_muted"))
                    exec(c, "ALTER TABLE voice_presence ADD COLUMN mic_muted TINYINT NOT NULL DEFAULT 0")
                if (!columnExists(c, "voice_presence", "deafened"))
                    exec(c, "ALTER TABLE voice_presence ADD COLUMN deafened TINYINT NOT NULL DEFAULT 0")
            }
        },
        Migration(12, "text-колонки сообщений → LONGTEXT") { c ->
            runCatching { exec(c, "SET SESSION sql_mode = ''") }
            for (t in listOf("messages", "group_messages", "server_messages")) {
                if (tableExists(c, t) && columnExists(c, t, "text")) {
                    runCatching {
                        exec(c, "ALTER TABLE $t MODIFY text LONGTEXT CHARACTER SET utf8mb4 " +
                                "COLLATE utf8mb4_general_ci NOT NULL")
                    }.onFailure {
                        exec(c, "ALTER TABLE $t MODIFY text LONGTEXT CHARACTER SET utf8mb4 " +
                                "COLLATE utf8mb4_general_ci NULL")
                    }
                }
            }
        },
        Migration(13, "server_messages.reply_to_id: ответы в каналах серверов") { c ->
            if (tableExists(c, "server_messages") && !columnExists(c, "server_messages", "reply_to_id")) {
                exec(c, "ALTER TABLE server_messages ADD COLUMN reply_to_id INT UNSIGNED NULL")
                runCatching { exec(c, "ALTER TABLE server_messages ADD KEY idx_reply (reply_to_id)") }
            }
        },
        Migration(14, "server_channels.user_limit: вместимость голосового канала") { c ->
            if (tableExists(c, "server_channels") && !columnExists(c, "server_channels", "user_limit"))
                exec(c, "ALTER TABLE server_channels ADD COLUMN user_limit INT NOT NULL DEFAULT 0")
        },
        Migration(15, "server_mentions: упоминания в каналах (текст в БД зашифрован)") { c ->
            // Считать упоминания запросом LIKE по server_messages.text нельзя:
            // текст хранится зашифрованным (AES-GCM в Base64), и «@логин» там не
            // встретится никогда. Адресаты вычисляются при отправке и лежат тут.
            exec(c, "CREATE TABLE IF NOT EXISTS server_mentions (" +
                    "message_id BIGINT NOT NULL, " +
                    "channel_id INT NOT NULL, " +
                    "user_id INT NOT NULL, " +
                    "PRIMARY KEY (message_id, user_id), " +
                    "KEY idx_mention_lookup (user_id, channel_id, message_id)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
        },
        Migration(16, "server_roles.can_channels: отдельное право на каналы") { c ->
            // Управление каналами (создать, переименовать, удалить, лимит)
            // отделено от общего управления сервером: снятая галочка отбирает
            // каналы даже у того, кто правит роли. Владельца это не касается.
            //
            // На ПК ту же колонку добавляют вручную скриптом
            // sql/2026-08-19_server_roles_can_channels.sql — там приложение
            // DDL не выполняет. Здесь мигратор есть, поэтому доводим сами;
            // обе стороны сходятся на одной колонке.
            if (!columnExists(c, "server_roles", "can_channels")) {
                exec(c, "ALTER TABLE server_roles ADD COLUMN can_channels TINYINT(1) NOT NULL DEFAULT 0")
                // Разовый перенос: у кого было общее управление, тот не должен
                // потерять каналы при обновлении.
                exec(c, "UPDATE server_roles SET can_channels=1 WHERE can_manage=1")
            }
        },
        Migration(17, "messages: индексы под горячие запросы личных сообщений") { c ->
            // В messages были только idx_msg_pair (sender_id, receiver_id) и
            // idx_msg_created (created_at). Запросы, которые идут «от
            // получателя» — непрочитанные, отметка прочитанного, список
            // диалогов — опереться на них не могут и сканируют таблицу
            // целиком. А вложения лежат в этой же таблице, в LONGBLOB, и с
            // диска поднимаются вместе со строками: отсюда десятки МБ/с при
            // отправке простого текста.
            //
            // На ПК те же индексы кладут вручную скриптом
            // sql/2026-08-19_message_indexes.sql — там приложение DDL не
            // делает. Здесь мигратор есть, поэтому доводим сами.
            if (!indexExists(c, "messages", "idx_msg_recv_read")) {
                exec(c, "ALTER TABLE messages ADD INDEX idx_msg_recv_read (receiver_id, is_read, sender_id)")
            }
            if (!indexExists(c, "messages", "idx_msg_recv_time")) {
                exec(c, "ALTER TABLE messages ADD INDEX idx_msg_recv_time (receiver_id, created_at, id)")
            }
            if (!indexExists(c, "messages", "idx_msg_send_time")) {
                exec(c, "ALTER TABLE messages ADD INDEX idx_msg_send_time (sender_id, created_at, id)")
            }
            if (!indexExists(c, "messages", "idx_msg_pair_time")) {
                exec(c, "ALTER TABLE messages ADD INDEX idx_msg_pair_time (sender_id, receiver_id, id)")
            }
        },
        Migration(18, "messages: индекс под запросы «от получателя»") { c ->
            // ПОЧЕМУ ЕЩЁ РАЗ, ПОСЛЕ 17. Миграция 17 спрашивала у
            // information_schema, есть ли уже такой индекс, и при отказе в
            // доступе (#1044) считала, что есть. То есть могла молча ничего не
            // создать и при этом отметиться выполненной — а журнал общий с ПК,
            // и второй клиент такую отметку уважает. Здесь у information_schema
            // не спрашиваем вовсе: пробуем создать и глотаем 1061 «Duplicate
            // key name», которая означает ровно то, что нужно.
            //
            // Зачем он. Всё, что спрашивается ОТ ПОЛУЧАТЕЛЯ, на существующие
            // idx_msg_pair (sender_id, receiver_id) и idx_msg_created опереться
            // не может и сканирует messages целиком: непрочитанные по
            // отправителям (у списка чатов — каждые 2,5 секунды), отметка
            // «прочитано», список диалогов. Это и есть та самая полка чтения на
            // сотню мегабайт после каждого отправленного сообщения.
            //
            // Индекс ровно один. Направление «я писал» ложится на idx_msg_pair;
            // (sender_id, receiver_id, id) — это он и есть, потому что в InnoDB
            // первичный ключ дописывается в конец любого вторичного индекса. У
            // group_messages и server_messages индексы по (group_id) и
            // (channel_id) есть с самого начала, и MAX(id) по ним — движение к
            // концу индекса.
            //
            // Права ALTER у учётной записи приложения может не быть; тогда
            // индекс кладётся руками, см. sql/2026-08-20_feed_indexes.sql.
            addIndex(c, "messages", "idx_msg_recv_read", "(receiver_id, is_read, sender_id)")
        },
    )

    /**
     * Прогоняет непримененные миграции. Безопасно вызывать многократно;
     * при отсутствии связи тихо выходит и повторит при следующем запуске.
     */
    suspend fun run() {
        if (done) return
        runCatching {
            Db.use { conn ->
                ensureLedger(conn)
                val applied = loadApplied(conn)
                for (m in migrations) {
                    if (m.id in applied) continue
                    try {
                        m.up(conn)
                        markApplied(conn, m.id, m.name)
                        Log.d(TAG, "✓ ${m.id} ${m.name}")
                    } catch (e: Exception) {
                        // Одна миграция упала — не блокируем остальные и не
                        // помечаем её применённой (повторим в следующий раз).
                        Log.w(TAG, "✗ ${m.id} ${m.name}: ${e.message}")
                    }
                }
            }
            done = true
        }.onFailure { Log.w(TAG, "нет связи: ${it.message}") }
    }

    // ── Журнал ─────────────────────────────────────────────────────────
    private fun ensureLedger(c: Connection) {
        exec(c, "CREATE TABLE IF NOT EXISTS schema_migrations (" +
                "id INT NOT NULL PRIMARY KEY, name VARCHAR(255) NULL, " +
                "applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) " +
                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    }

    private fun loadApplied(c: Connection): Set<Int> = buildSet {
        runCatching {
            c.createStatement().use { st ->
                st.executeQuery("SELECT id FROM schema_migrations").use { rs ->
                    while (rs.next()) add(rs.getInt("id"))
                }
            }
        }
    }

    private fun markApplied(c: Connection, id: Int, name: String) {
        c.prepareStatement("INSERT IGNORE INTO schema_migrations (id, name) VALUES (?, ?)").use {
            it.setInt(1, id)
            it.setString(2, name)
            it.executeUpdate()
        }
    }

    // ── Помощники ──────────────────────────────────────────────────────
    private fun exec(c: Connection, sql: String) {
        c.createStatement().use { it.execute(sql) }
    }

    /**
     * Создаёт индекс, если его ещё нет.
     *
     * Существование заранее НЕ проверяем: на части хостингов закрыт доступ к
     * information_schema, и проверка упала бы раньше самого ALTER. Вместо неё
     * просто пробуем создать и глотаем 1061 «Duplicate key name» — она значит,
     * что индекс уже на месте. Любая другая ошибка (например, нет права ALTER)
     * уходит наверх, миграция не отмечается применённой и повторится при
     * следующем запуске; до тех пор индексы кладутся руками скриптом из sql/.
     */
    private fun addIndex(c: Connection, table: String, name: String, columns: String) {
        try {
            exec(c, "ALTER TABLE `$table` ADD INDEX `$name` $columns")
        } catch (e: SQLException) {
            if (e.errorCode != 1061) throw e
        }
    }

    /**
     * На части хостингов доступ к information_schema закрыт даже
     * администратору (#1044), поэтому есть фолбэк на SHOW-запросы — им
     * хватает обычных прав на таблицу.
     */
    private fun tableExists(c: Connection, table: String): Boolean {
        runCatching {
            c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?"
            ).use { ps ->
                ps.setString(1, table)
                ps.executeQuery().use { rs -> return rs.next() && rs.getInt(1) > 0 }
            }
        }
        return runCatching {
            c.createStatement().use { st ->
                st.executeQuery("SHOW TABLES").use { rs ->
                    while (rs.next()) if (rs.getString(1).equals(table, true)) return true
                }
            }
            false
        }.getOrDefault(false)
    }

    private fun indexExists(c: Connection, table: String, index: String): Boolean {
        runCatching {
            c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=?"
            ).use { ps ->
                ps.setString(1, table)
                ps.setString(2, index)
                ps.executeQuery().use { rs -> return rs.next() && rs.getInt(1) > 0 }
            }
        }
        // Не смогли спросить — считаем, что индекс есть: лишний ALTER на
        // большой таблице дороже, чем его отсутствие.
        return true
    }

    private fun columnExists(c: Connection, table: String, column: String): Boolean {
        runCatching {
            c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?"
            ).use { ps ->
                ps.setString(1, table)
                ps.setString(2, column)
                ps.executeQuery().use { rs -> return rs.next() && rs.getInt(1) > 0 }
            }
        }
        if (!isPlainIdentifier(table)) return false
        return runCatching {
            c.createStatement().use { st ->
                st.executeQuery("SHOW COLUMNS FROM `$table`").use { rs ->
                    while (rs.next()) if (rs.getString(1).equals(column, true)) return true
                }
            }
            false
        }.getOrDefault(false)
    }

    private fun emojiInPrimaryKey(c: Connection): Boolean = runCatching {
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'message_reactions' " +
                        "AND INDEX_NAME = 'PRIMARY' AND COLUMN_NAME = 'emoji'"
            ).use { rs -> rs.next() && rs.getInt(1) > 0 }
        }
    }.getOrDefault(false)

    private fun hasPrimaryKey(c: Connection): Boolean = runCatching {
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'message_reactions' " +
                        "AND INDEX_NAME = 'PRIMARY'"
            ).use { rs -> rs.next() && rs.getInt(1) > 0 }
        }
    }.getOrDefault(false)

    private fun isPlainIdentifier(s: String): Boolean =
        s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '_' }
}
