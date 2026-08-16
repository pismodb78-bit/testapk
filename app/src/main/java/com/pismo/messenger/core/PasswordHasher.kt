package com.pismo.messenger.core

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Порт PISMO/PasswordHasher.cs.
 *
 * Формат хранения: pbkdf2$<iterations>$<base64 salt>$<base64 hash>
 * (PBKDF2-HMAC-SHA256, 100 000 итераций, соль 16 байт, ключ 32 байта).
 *
 * Пароли, лежащие в БД открытым текстом (легаси), проверяются прямым
 * сравнением и перехешируются при первом успешном входе — ровно так же,
 * как это делает LoginForm на ПК.
 *
 * PBKDF2 считается вручную через Mac("HmacSHA256"), а не через
 * SecretKeyFactory: алгоритм "PBKDF2WithHmacSHA256" появился только в API 26,
 * а minSdk здесь 24.
 */
object PasswordHasher {

    private const val PREFIX = "pbkdf2$"
    private const val ITERATIONS = 100_000
    private const val SALT_SIZE = 16
    private const val KEY_SIZE = 32

    fun hash(password: String?): String {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val key = pbkdf2(password ?: "", salt, ITERATIONS, KEY_SIZE)
        return "$PREFIX$ITERATIONS$${b64(salt)}$${b64(key)}"
    }

    /**
     * Проверяет пароль. Наш PBKDF2 — сверяет хеш; открытый текст (легаси) —
     * прямое сравнение. bcrypt из веб-версии ($2…) проверить нельзя → false.
     */
    fun verify(password: String?, stored: String?): Boolean {
        if (stored.isNullOrEmpty()) return false

        if (stored.startsWith(PREFIX)) {
            return try {
                val p = stored.split('$')      // [pbkdf2, iter, salt, key]
                if (p.size != 4) return false
                val iter = p[1].toInt()
                val salt = Base64.decode(p[2], Base64.NO_WRAP)
                val key = Base64.decode(p[3], Base64.NO_WRAP)
                val test = pbkdf2(password ?: "", salt, iter, key.size)
                fixedTimeEquals(test, key)
            } catch (_: Exception) {
                false
            }
        }

        if (stored.startsWith("\$2")) return false   // bcrypt — не поддерживается

        return (password ?: "") == stored
    }

    /** Нужно ли перехешировать (всё, что не наш PBKDF2-формат). */
    fun needsUpgrade(stored: String?): Boolean =
        stored.isNullOrEmpty() || !stored.startsWith(PREFIX)

    // ── PBKDF2-HMAC-SHA256 (RFC 2898) ─────────────────────────────────
    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))

        val hLen = mac.macLength
        val blocks = (keyLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)

        for (i in 1..blocks) {
            // U1 = PRF(P, S || INT_32_BE(i))
            mac.update(salt)
            mac.update(byteArrayOf(
                (i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()
            ))
            var u = mac.doFinal()
            val t = u.copyOf()

            for (round in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return out.copyOf(keyLen)
    }

    private fun fixedTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun b64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
}
