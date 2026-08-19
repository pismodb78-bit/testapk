package com.pismo.messenger.core

/**
 * Клиентское ограничение частоты — порт RateLimiter.cs.
 *
 * Защищает от перебора пароля и спама заявками в друзья. Живёт в памяти
 * процесса и сбрасывается перезапуском: полноценный лимит — дело сервера,
 * а здесь задача скромнее — не дать выстрелить сотней запросов в базу с
 * одного экрана и притормозить подбор пароля настолько, чтобы он потерял
 * смысл.
 */
object RateLimiter {

    private val lock = Any()

    // ── Скользящее окно: не больше maxEvents событий за window ──
    private val events = HashMap<String, ArrayList<Long>>()

    /** Разрешить событие [key]? Регистрирует его, если да. */
    fun allow(key: String, maxEvents: Int, windowMs: Long): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        val list = events.getOrPut(key) { ArrayList() }
        list.removeAll { now - it > windowMs }
        if (list.size >= maxEvents) return false
        list.add(now)
        true
    }

    // ── Блокировка входа с нарастающей задержкой ──
    private class Fail(var count: Int = 0, var lockedUntil: Long = 0)

    private val login = HashMap<String, Fail>()

    /**
     * Остаток блокировки входа в миллисекундах; 0 — не заблокирован.
     * Логин сверяется без учёта регистра, как на ПК.
     */
    fun loginLockRemainingMs(login: String): Long = synchronized(lock) {
        val f = this.login[login.lowercase()] ?: return 0
        (f.lockedUntil - System.currentTimeMillis()).coerceAtLeast(0)
    }

    /**
     * Неудачная попытка входа. После пяти подряд — блокировка, растущая с
     * каждой следующей: 30 с, 60 с, 90 с… до пяти минут.
     */
    fun registerLoginFailure(login: String) = synchronized(lock) {
        val key = login.lowercase()
        val f = this.login.getOrPut(key) { Fail() }
        f.count++
        if (f.count >= 5) {
            val over = f.count - 4
            val secs = (30 * over).coerceAtMost(300)
            f.lockedUntil = System.currentTimeMillis() + secs * 1000L
        }
    }

    /** Успешный вход — счётчик неудач сбрасывается. */
    fun registerLoginSuccess(login: String) = synchronized(lock) {
        this.login.remove(login.lowercase())
        Unit
    }
}
