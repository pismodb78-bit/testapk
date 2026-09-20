package android.util

/**
 * Настольная замена `android.util.LruCache` — см. пояснение у Base64.
 *
 * Поведение то же, что у оригинала, включая главное: размер считается не в
 * штуках, а тем, что вернёт [sizeOf]. Общий код хранит здесь вложения и
 * меряет их байтами — считай он записями, десяток видео вытеснил бы всё
 * остальное и выел бы память.
 *
 * `LinkedHashMap` в режиме порядка обращения сам двигает тронутую запись в
 * конец, поэтому «самое давнее» — всегда первое.
 */
open class LruCache<K : Any, V : Any>(private val maxSize: Int) {

    init { require(maxSize > 0) { "maxSize <= 0" } }

    private val map = LinkedHashMap<K, V>(0, 0.75f, true)
    private var size = 0

    private var hitCount = 0
    private var missCount = 0
    private var evictionCount = 0

    /** Во сколько обходится одна запись. По умолчанию — одна штука. */
    protected open fun sizeOf(key: K, value: V): Int = 1

    /** Зовётся после вытеснения или замены — оригинал даёт тот же крючок. */
    protected open fun entryRemoved(evicted: Boolean, key: K, oldValue: V, newValue: V?) {}

    /** Чем заполнить промах. По умолчанию ничем. */
    protected open fun create(key: K): V? = null

    @Synchronized
    fun get(key: K): V? {
        map[key]?.let { hitCount++; return it }
        missCount++
        val made = create(key) ?: return null
        return put(key, made) ?: made
    }

    @Synchronized
    fun put(key: K, value: V): V? {
        size += safeSizeOf(key, value)
        val previous = map.put(key, value)
        if (previous != null) size -= safeSizeOf(key, previous)
        if (previous != null) entryRemoved(false, key, previous, value)
        trimToSize(maxSize)
        return previous
    }

    @Synchronized
    fun remove(key: K): V? {
        val previous = map.remove(key) ?: return null
        size -= safeSizeOf(key, previous)
        entryRemoved(false, key, previous, null)
        return previous
    }

    @Synchronized
    fun trimToSize(target: Int) {
        while (size > target && map.isNotEmpty()) {
            val eldest = map.entries.iterator().next()
            map.remove(eldest.key)
            size -= safeSizeOf(eldest.key, eldest.value)
            evictionCount++
            entryRemoved(true, eldest.key, eldest.value, null)
        }
    }

    fun evictAll() = trimToSize(-1)

    @Synchronized fun size(): Int = size
    @Synchronized fun maxSize(): Int = maxSize
    @Synchronized fun hitCount(): Int = hitCount
    @Synchronized fun missCount(): Int = missCount
    @Synchronized fun evictionCount(): Int = evictionCount
    @Synchronized fun snapshot(): MutableMap<K, V> = LinkedHashMap(map)

    private fun safeSizeOf(key: K, value: V): Int {
        val n = sizeOf(key, value)
        require(n >= 0) { "отрицательный размер записи: $key" }
        return n
    }

    override fun toString(): String = "LruCache[maxSize=$maxSize]"
}
