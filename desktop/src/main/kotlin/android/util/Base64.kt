package android.util

/**
 * Настольная замена `android.util.Base64`.
 *
 * Общий код (Crypto, Jwt, PasswordHasher, Prefs) писался под Android и зовёт
 * именно этот класс. Переписывать его ради настольной сборки было бы худшим
 * из решений: пароли и переписка шифруются одним и тем же кодом на всех
 * платформах, и любая правка «только для Linux» — это будущее расхождение,
 * которое вскроется на чужих данных.
 *
 * Поэтому тут не адаптер, а сам класс: та же сигнатура, те же флаги, поверх
 * `java.util.Base64`. Общий код компилируется без единой правки.
 */
object Base64 {

    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8

    fun encodeToString(input: ByteArray, flags: Int): String =
        String(encode(input, flags), Charsets.US_ASCII)

    fun encode(input: ByteArray, flags: Int): ByteArray {
        var enc =
            if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder()
            else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) enc = enc.withoutPadding()
        val raw = enc.encodeToString(input)
        if (flags and NO_WRAP != 0 || flags and URL_SAFE != 0)
            return raw.toByteArray(Charsets.US_ASCII)

        // Без NO_WRAP Android переносит строку каждые 76 символов и ставит
        // перевод в конце. В нашем коде этот путь не используется, но пусть
        // ведёт себя как оригинал — чтобы разница не всплыла потом.
        val eol = if (flags and CRLF != 0) "\r\n" else "\n"
        val sb = StringBuilder(raw.length + raw.length / 76 * 2 + 2)
        var i = 0
        while (i < raw.length) {
            val end = minOf(i + 76, raw.length)
            sb.append(raw, i, end).append(eol)
            i = end
        }
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    fun decode(str: String, flags: Int): ByteArray =
        decode(str.toByteArray(Charsets.US_ASCII), flags)

    fun decode(input: ByteArray, flags: Int): ByteArray {
        // MIME-декодер молча пропускает переносы строк и лишние символы —
        // ровно как это делает Android, и в отличие от строгого обычного.
        val dec =
            if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder()
            else java.util.Base64.getMimeDecoder()
        return dec.decode(input)
    }
}
