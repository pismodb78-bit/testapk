package com.pismo.messenger.data.db

import com.pismo.messenger.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * Соединение в пуле вместе со временем, когда его последний раз отдавали.
     *
     * Время нужно, чтобы НЕ проверять соединение перед каждой выдачей. Проверка
     * (`isValid`) — это пинг до сервера, то есть полный круг по сети ДО того,
     * как уйдёт сам запрос. На дистанции в 64 мс каждая операция стоила из-за
     * неё вдвое дороже: 64 на проверку и 64 на дело. При открытии чата запросов
     * подряд идёт полдесятка, и лишние круги складывались в заметную паузу.
     *
     * Проверять всё же надо: соединение, пролежавшее долго, сервер мог закрыть
     * сам по wait_timeout, и тогда запрос уйдёт в никуда. Поэтому проверяем
     * только те, что залежались, — свежие отдаём как есть.
     */
    private class Pooled(val conn: Connection, var idleSince: Long)

    /**
     * Сколько соединение считается заведомо живым. wait_timeout на сервере —
     * полчаса, так что тридцать секунд это с большим запасом.
     */
    private const val FRESH_MS = 30_000L

    private val pool = ArrayDeque<Pooled>()
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
                pool.forEach { runCatching { it.conn.close() } }
                pool.clear()
                configKey = key
            }
            val now = System.currentTimeMillis()
            while (pool.isNotEmpty()) {
                val p = pool.removeFirst()
                val fresh = now - p.idleSince < FRESH_MS
                val alive = runCatching {
                    // Свежее — только закрыто оно или нет, без похода на сервер.
                    !p.conn.isClosed && (fresh || p.conn.isValid(2))
                }.getOrDefault(false)
                if (alive) return p.conn
                runCatching { p.conn.close() }
            }
        }
        return openNew()
    }

    private suspend fun giveBack(conn: Connection) {
        lock.withLock {
            if (pool.size < MAX_POOL && runCatching { !conn.isClosed }.getOrDefault(false)) {
                pool.addLast(Pooled(conn, System.currentTimeMillis()))
            } else {
                runCatching { conn.close() }
            }
        }
    }

    /** Закрывает пул — вызывать при выходе из аккаунта и смене настроек БД. */
    suspend fun closeAll() = withContext(Dispatchers.IO) {
        lock.withLock {
            pool.forEach { runCatching { it.conn.close() } }
            pool.clear()
            configKey = ""
        }
    }

    // ── Состояние связи ────────────────────────────────────────────────
    //
    // ПК открывает соединение на КАЖДЫЙ запрос, поэтому перезапуск сервера
    // там незаметен: следующий же запрос открывает новое. Здесь пул, и
    // после падения сервера в нём остаются дохлые соединения, а экраны,
    // загрузившиеся один раз, больше ничего не перезапрашивают — отсюда и
    // «пришлось перезапустить приложение».
    //
    // Лечится двумя вещами: запрос сам повторяется на свежем соединении,
    // а [reconnects] считает восстановления связи, чтобы экраны могли
    // перезагрузить данные, не дожидаясь действий пользователя.

    private val _online = MutableStateFlow(true)

    /** false — последняя попытка обращения к базе не удалась. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _reconnects = MutableStateFlow(0)

    /** Счётчик восстановлений связи: меняется — экранам пора перезагрузиться. */
    val reconnects: StateFlow<Int> = _reconnects.asStateFlow()

    private fun markOnline() {
        if (!_online.value) {
            _online.value = true
            _reconnects.value = _reconnects.value + 1
        }
    }

    private fun markOffline() {
        val was = _online.value
        _online.value = false
        if (was) startReconnectLoop()
    }

    private val guardScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null

    /**
     * Пока связи нет — стучимся в базу сами, раз в 2,5 секунды. Порт
     * _retryTimer из ConnectionGuard.
     *
     * Без этого цикла заставка «нет связи» висела бы до первого запроса
     * пользователя: экраны с опросом (переписка) восстановились бы сами, а
     * настройки или профиль — никогда. На телефоне это обычное дело: сеть
     * пропала в лифте и вернулась, а приложение об этом не узнало.
     */
    @Synchronized
    private fun startReconnectLoop() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = guardScope.launch {
            while (isActive && !_online.value) {
                delay(RETRY_MS)
                // Пробуем открыть соединение начисто: лежащие в пуле сокеты
                // после обрыва мертвы, и проверять надо не их.
                val ok = runCatching { openNew().use { it.isValid(3) } }.getOrDefault(false)
                if (ok) markOnline()
            }
        }
    }

    /** Период проб при обрыве — тот же, что у ConnectionGuard на ПК. */
    private const val RETRY_MS = 2500L

    /**
     * Похоже ли исключение на разрыв соединения, а не на ошибку в запросе.
     * Повторять имеет смысл только первое: повтор синтаксической ошибки
     * просто удвоит задержку.
     */
    private fun isConnectionFailure(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is java.net.SocketException ||
                cause is java.net.SocketTimeoutException ||
                cause is java.net.ConnectException ||
                cause is java.io.EOFException
            ) return true
            val sqlState = (cause as? java.sql.SQLException)?.sqlState
            // 08xxx — класс «connection exception» по SQL-стандарту.
            if (sqlState != null && sqlState.startsWith("08")) return true
            val text = cause.message.orEmpty()
            if (text.contains("Communications link failure", ignoreCase = true) ||
                text.contains("connection is closed", ignoreCase = true) ||
                text.contains("Broken pipe", ignoreCase = true) ||
                text.contains("server closed", ignoreCase = true)
            ) return true
            cause = cause.cause
        }
        return false
    }

    /**
     * Соединение не удалось даже взять — значит, запрос до сервера не дошёл
     * заведомо. Обёртка нужна, чтобы отличить этот случай от обрыва ПОСЛЕ
     * отправки запроса: повторять их можно по-разному.
     */
    private class NotStarted(override val cause: Throwable) : Exception(cause)

    /**
     * Выполняет блок на соединении из пула. Соединение, на котором вылетело
     * SQL-исключение, в пул не возвращается — оно может быть уже разорвано.
     *
     * ПОВТОР ПРИ ОБРЫВЕ И ПОЧЕМУ ОН НЕ ДЛЯ ВСЕХ. Лежащие в пуле сокеты
     * умирают молча — сервер перезапустили, вышка сменилась, — и повтор на
     * заново открытом соединении спасает запрос, который иначе упал бы на
     * ровном месте. Для чтения это чистая польза.
     *
     * А для записи — нет. Обрыв на INSERT ничего не говорит о том, дошёл ли
     * он: строка могла отлично записаться, а по дороге назад потеряться
     * подтверждение. Слепой повтор в этом случае вставляет ВТОРУЮ строку —
     * ровно отсюда брались дубли сообщений на плохой сети, без всяких
     * двойных нажатий.
     *
     * Поэтому [idempotent] = false (записи) повторяются только тогда, когда
     * запрос заведомо не ушёл: соединение не удалось даже открыть. Это самый
     * частый случай обрыва, так что польза от повтора почти не теряется, а
     * дубли исключены полностью.
     */
    suspend fun <T> use(
        idempotent: Boolean = true,
        block: (Connection) -> T,
    ): T = withContext(Dispatchers.IO) {
        try {
            val result = runOnce(block)
            markOnline()
            return@withContext result
        } catch (first: Throwable) {
            val cause = unwrap(first)
            if (!isConnectionFailure(cause)) throw cause
            markOffline()

            // Запись, которая могла дойти до сервера, второй раз не шлём.
            if (!idempotent && first !is NotStarted) throw cause

            // Пул после обрыва целиком под подозрением — выбрасываем его,
            // иначе повтор возьмёт следующее такое же мёртвое соединение.
            lock.withLock {
                pool.forEach { runCatching { it.conn.close() } }
                pool.clear()
            }

            try {
                val result = runOnce(block)
                markOnline()
                return@withContext result
            } catch (second: Throwable) {
                throw unwrap(second)
            }
        }
    }

    private fun unwrap(e: Throwable): Throwable = if (e is NotStarted) e.cause else e

    private suspend fun <T> runOnce(block: (Connection) -> T): T {
        val conn = try {
            borrow()
        } catch (e: Throwable) {
            throw NotStarted(e)
        }
        var broken = false
        try {
            return block(conn)
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

    /** Запись. idempotent = false: повторять её вслепую нельзя, см. use(). */
    suspend fun exec(sql: String, vararg params: Any?): Int = use(idempotent = false) { conn ->
        conn.prepareStatement(sql).use { ps ->
            bind(ps, params)
            ps.executeUpdate()
        }
    }

    /** INSERT с возвратом сгенерированного id (аналог LastInsertedId). */
    suspend fun insert(sql: String, vararg params: Any?): Int = use(idempotent = false) { conn ->
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
