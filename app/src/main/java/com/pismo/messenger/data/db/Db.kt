package com.pismo.messenger.data.db

import com.pismo.messenger.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

/**
 * Прямое подключение к MySQL `bdauth` — той же базе, что использует ПК-клиент.
 * Своего бэкенда у проекта нет, поэтому Android ходит в базу так же напрямую
 * (см. ip.txt и DBHelper.cs).
 *
 * Отличие от ПК-версии: там соединение открывается на каждый запрос, что на
 * мобильной сети означало бы TCP+handshake по несколько сотен миллисекунд на
 * каждое действие. Здесь — небольшой пул переиспользуемых соединений.
 *
 * ВСЕ методы suspend и уходят на Dispatchers.IO: JDBC на главном потоке
 * Android бросает NetworkOnMainThreadException.
 */
object Db {

    private const val MAX_POOL = 4

    private val pool = ArrayDeque<Connection>()
    private val lock = Mutex()

    @Volatile private var driverLoaded = false

    /** Ключ текущей конфигурации: при смене настроек пул сбрасывается. */
    @Volatile private var configKey: String = ""

    private fun currentKey() =
        "${Prefs.dbHost}:${Prefs.dbPort}/${Prefs.dbName}/${Prefs.dbUser}"

    private val jdbcUrl: String
        get() = "jdbc:mysql://${Prefs.dbHost}:${Prefs.dbPort}/${Prefs.dbName}" +
                "?useUnicode=true&characterEncoding=UTF-8&characterSetResults=UTF-8" +
                "&connectionCollation=utf8mb4_unicode_ci" +
                "&useSSL=false&allowPublicKeyRetrieval=true" +
                "&connectTimeout=10000&socketTimeout=30000" +
                "&autoReconnect=true&serverTimezone=UTC"

    private fun ensureDriver() {
        if (driverLoaded) return
        // Драйвер 5.1.x регистрируется по имени класса; ServiceLoader в APK
        // не всегда срабатывает из-за слияния META-INF/services.
        try {
            Class.forName("com.mysql.jdbc.Driver")
        } catch (_: Throwable) {
            Class.forName("org.mariadb.jdbc.Driver")
        }
        driverLoaded = true
    }

    private fun openNew(): Connection {
        ensureDriver()
        val conn = DriverManager.getConnection(jdbcUrl, Prefs.dbUser, Prefs.dbPassword)
        // Кириллица и эмодзи: гарантируем utf8mb4 независимо от настроек сервера.
        runCatching { conn.createStatement().use { it.execute("SET NAMES utf8mb4") } }
        return conn
    }

    private suspend fun borrow(): Connection {
        val key = currentKey()
        lock.withLock {
            if (key != configKey) {
                // Настройки подключения изменились — старые соединения не годятся.
                pool.forEach { runCatching { it.close() } }
                pool.clear()
                configKey = key
            }
            while (pool.isNotEmpty()) {
                val c = pool.removeFirst()
                val alive = runCatching { !c.isClosed && c.isValid(2) }.getOrDefault(false)
                if (alive) return c
                runCatching { c.close() }
            }
        }
        return openNew()
    }

    private suspend fun giveBack(conn: Connection) {
        lock.withLock {
            if (pool.size < MAX_POOL && runCatching { !conn.isClosed }.getOrDefault(false)) {
                pool.addLast(conn)
            } else {
                runCatching { conn.close() }
            }
        }
    }

    /** Закрывает пул — вызывать при выходе из аккаунта и смене настроек БД. */
    suspend fun closeAll() = withContext(Dispatchers.IO) {
        lock.withLock {
            pool.forEach { runCatching { it.close() } }
            pool.clear()
            configKey = ""
        }
    }

    /**
     * Выполняет блок на соединении из пула. Соединение, на котором вылетело
     * SQL-исключение, в пул не возвращается — оно может быть уже разорвано.
     */
    suspend fun <T> use(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        val conn = borrow()
        var broken = false
        try {
            block(conn)
        } catch (e: Throwable) {
            broken = true
            throw e
        } finally {
            if (broken) runCatching { conn.close() } else giveBack(conn)
        }
    }

    /** Проверка связи — для экрана настроек. */
    suspend fun testConnection(): Result<String> = runCatching {
        use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT VERSION()").use { rs ->
                    if (rs.next()) rs.getString(1) else "?"
                }
            }
        }
    }

    // ── Хелперы запросов ───────────────────────────────────────────────

    private fun bind(ps: PreparedStatement, params: Array<out Any?>) {
        params.forEachIndexed { i, p ->
            val idx = i + 1
            when (p) {
                null -> ps.setNull(idx, java.sql.Types.NULL)
                is Int -> ps.setInt(idx, p)
                is Long -> ps.setLong(idx, p)
                is Boolean -> ps.setInt(idx, if (p) 1 else 0)
                is Double -> ps.setDouble(idx, p)
                is ByteArray -> ps.setBytes(idx, p)
                is String -> ps.setString(idx, p)
                else -> ps.setObject(idx, p)
            }
        }
    }

    suspend fun <T> query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
        use { conn ->
            conn.prepareStatement(sql).use { ps ->
                bind(ps, params)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<T>()
                    while (rs.next()) out.add(map(rs))
                    out
                }
            }
        }

    suspend fun <T> queryFirst(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
        use { conn ->
            conn.prepareStatement(sql).use { ps ->
                bind(ps, params)
                ps.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
            }
        }

    suspend fun exec(sql: String, vararg params: Any?): Int = use { conn ->
        conn.prepareStatement(sql).use { ps ->
            bind(ps, params)
            ps.executeUpdate()
        }
    }

    /** INSERT с возвратом сгенерированного id (аналог LastInsertedId). */
    suspend fun insert(sql: String, vararg params: Any?): Int = use { conn ->
        conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
            bind(ps, params)
            ps.executeUpdate()
            ps.generatedKeys.use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    suspend fun scalarInt(sql: String, vararg params: Any?, default: Int = 0): Int =
        queryFirst(sql, *params) { rs -> rs.getInt(1) } ?: default

    suspend fun scalarLong(sql: String, vararg params: Any?, default: Long = 0): Long =
        queryFirst(sql, *params) { rs -> rs.getLong(1) } ?: default

    suspend fun scalarString(sql: String, vararg params: Any?): String? =
        queryFirst(sql, *params) { rs -> rs.getString(1) }

    suspend fun scalarBytes(sql: String, vararg params: Any?): ByteArray? =
        queryFirst(sql, *params) { rs -> rs.getBytes(1) }

    suspend fun exists(sql: String, vararg params: Any?): Boolean =
        queryFirst(sql, *params) { true } ?: false
}

// ── Удобные расширения ResultSet ───────────────────────────────────────

fun ResultSet.intOrNull(column: String): Int? {
    val v = getInt(column)
    return if (wasNull()) null else v
}

fun ResultSet.str(column: String): String = getString(column) ?: ""

fun ResultSet.bool(column: String): Boolean = getInt(column) != 0

/**
 * Время читаем как epoch-секунды через UNIX_TIMESTAMP() в самом SQL —
 * так на клиенте не возникает сдвига часовых поясов, который иначе вносит
 * JDBC при конвертации DATETIME (у ПК-версии есть отдельный костыль на
 * TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW()) ровно по этой причине).
 */
fun ResultSet.epochMillis(column: String): Long = getLong(column) * 1000L
