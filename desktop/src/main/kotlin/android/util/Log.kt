package android.util

/**
 * Настольная замена `android.util.Log` — см. пояснение у Base64.
 *
 * Пишем в поток ошибок с тем же тегом: в терминале это ровно то, что нужно,
 * а под systemd уходит в journal.
 */
object Log {

    fun v(tag: String?, msg: String?): Int = out("V", tag, msg, null)
    fun d(tag: String?, msg: String?): Int = out("D", tag, msg, null)
    fun i(tag: String?, msg: String?): Int = out("I", tag, msg, null)
    fun w(tag: String?, msg: String?): Int = out("W", tag, msg, null)
    fun e(tag: String?, msg: String?): Int = out("E", tag, msg, null)

    fun v(tag: String?, msg: String?, tr: Throwable?): Int = out("V", tag, msg, tr)
    fun d(tag: String?, msg: String?, tr: Throwable?): Int = out("D", tag, msg, tr)
    fun i(tag: String?, msg: String?, tr: Throwable?): Int = out("I", tag, msg, tr)
    fun w(tag: String?, msg: String?, tr: Throwable?): Int = out("W", tag, msg, tr)
    fun e(tag: String?, msg: String?, tr: Throwable?): Int = out("E", tag, msg, tr)

    fun w(tag: String?, tr: Throwable?): Int = out("W", tag, null, tr)

    fun getStackTraceString(tr: Throwable?): String {
        if (tr == null) return ""
        val sw = java.io.StringWriter()
        tr.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }

    private fun out(level: String, tag: String?, msg: String?, tr: Throwable?): Int {
        System.err.println("$level/${tag ?: "PISMO"}: ${msg ?: ""}")
        tr?.printStackTrace()
        return 0
    }
}
