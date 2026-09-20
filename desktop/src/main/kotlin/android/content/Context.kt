package android.content

import java.io.File
import java.util.Properties

/**
 * Настольная замена `android.content.Context` и `SharedPreferences`.
 *
 * Причина та же, что у Base64: общий код (Prefs, MediaCache, ChatDiskCache,
 * ChatListMemory, EmojiCatalog, CrashLog) написан под Android и спрашивает
 * у системы ровно две вещи — куда класть файлы и где хранить настройки.
 * Обе на Linux есть, просто называются иначе. Даём их под привычными
 * именами, и общий код едет на настольную сборку как есть.
 *
 * Расположение — по XDG, как принято в Linux:
 *   настройки  $XDG_CONFIG_HOME/pismo   (по умолчанию ~/.config/pismo)
 *   данные     $XDG_DATA_HOME/pismo     (по умолчанию ~/.local/share/pismo)
 *   кеш        $XDG_CACHE_HOME/pismo    (по умолчанию ~/.cache/pismo)
 */
open class Context {

    companion object {
        const val MODE_PRIVATE = 0
        const val MODE_APPEND = 32768

        private fun xdg(envVar: String, fallback: String): File {
            val base = System.getenv(envVar)?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/" + fallback)
            return File(base, "pismo").apply { mkdirs() }
        }

        private val configDir by lazy { xdg("XDG_CONFIG_HOME", ".config") }
        private val dataDir by lazy { xdg("XDG_DATA_HOME", ".local/share") }
        private val cacheDirRoot by lazy { xdg("XDG_CACHE_HOME", ".cache") }

        private val prefsByName = HashMap<String, SharedPreferences>()
    }

    /** Постоянные данные: кеш переписки, вложения, аватары. */
    open val filesDir: File get() = dataDir

    /** То, что не жалко потерять. */
    open val cacheDir: File get() = cacheDirRoot

    @Synchronized
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefsByName.getOrPut(name) { FilePreferences(File(configDir, "$name.properties")) }
}

/**
 * Настройки. Интерфейс повторяет андроидный ровно настолько, насколько его
 * использует общий код, — лишнего не выдумываем.
 */
interface SharedPreferences {

    fun getString(key: String, defValue: String?): String?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        /** На Android — запись в фоне; здесь файл маленький, пишем сразу. */
        fun apply()
        fun commit(): Boolean
    }
}

/**
 * Хранилище — обычный .properties рядом с прочими настройками пользователя.
 *
 * Пишем через временный файл и переименование: если питание пропадёт посреди
 * записи, на диске останется прежняя целая версия, а не половина новой. На
 * Android то же самое делает сам SharedPreferences, и терять это свойство
 * при переезде не хочется — в этом файле лежит и сохранённый пароль.
 */
private class FilePreferences(private val file: File) : SharedPreferences {

    private val props = Properties()

    init {
        runCatching { if (file.isFile) file.inputStream().use { props.load(it) } }
    }

    @Synchronized private fun raw(key: String): String? = props.getProperty(key)

    override fun getString(key: String, defValue: String?): String? = raw(key) ?: defValue
    override fun getInt(key: String, defValue: Int): Int = raw(key)?.toIntOrNull() ?: defValue
    override fun getLong(key: String, defValue: Long): Long = raw(key)?.toLongOrNull() ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = raw(key)?.toFloatOrNull() ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        raw(key)?.toBooleanStrictOrNull() ?: defValue

    @Synchronized override fun contains(key: String): Boolean = props.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    @Synchronized fun writeNow(changes: Map<String, String?>, wipe: Boolean) {
        if (wipe) props.clear()
        for ((k, v) in changes) if (v == null) props.remove(k) else props.setProperty(k, v)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { props.store(it, "PISMO") }
            // Файл с сохранённым паролем не должен быть читаем соседями по машине.
            runCatching {
                tmp.setReadable(false, false); tmp.setReadable(true, true)
                tmp.setWritable(false, false); tmp.setWritable(true, true)
            }
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        }
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, String?>()
        private var wipe = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            changes[key] = value; return this
        }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            changes[key] = value.toString(); return this
        }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            changes[key] = value.toString(); return this
        }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            changes[key] = value.toString(); return this
        }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            changes[key] = value.toString(); return this
        }
        override fun remove(key: String): SharedPreferences.Editor {
            changes[key] = null; return this
        }
        override fun clear(): SharedPreferences.Editor { wipe = true; return this }

        override fun apply() { writeNow(changes, wipe) }
        override fun commit(): Boolean { writeNow(changes, wipe); return true }
    }
}
