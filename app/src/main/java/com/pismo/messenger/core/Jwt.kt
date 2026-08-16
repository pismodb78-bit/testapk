package com.pismo.messenger.core

import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Сборка JWT (HS256) вручную — как это делают PISMO/JwtAuth.cs и
 * PISMO/LiveKitSettings.cs на ПК: без внешних библиотек, Base64Url без
 * паддинга ('=' обрезается, '+' → '-', '/' → '_').
 */
object Jwt {

    private const val HEADER = """{"alg":"HS256","typ":"JWT"}"""

    /** Подписывает готовый payload-JSON указанным секретом. */
    fun sign(payloadJson: String, secret: String): String {
        val header = b64Url(HEADER.toByteArray(Charsets.UTF_8))
        val payload = b64Url(payloadJson.toByteArray(Charsets.UTF_8))
        val signingInput = "$header.$payload"

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        // signingInput — чистый ASCII (Base64Url), поэтому US_ASCII и UTF-8
        // дают одинаковые байты; ПК-версия использует ASCII.
        val sig = mac.doFinal(signingInput.toByteArray(Charsets.US_ASCII))

        return "$signingInput.${b64Url(sig)}"
    }

    fun b64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)

    /** Экранирование строки для ручной сборки JSON. */
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}

/**
 * JWT для ws-сервера сигналинга (порт PISMO/JwtAuth.cs).
 * Секрет должен совпадать с JWT_SECRET на ws-server.
 */
object JwtAuth {

    /**
     * Дефолт совпадает с фолбэком ws-server/server.js — как в AppConfig.cs
     * ПК-версии, чтобы подпись проходила проверку «из коробки».
     */
    const val DEFAULT_SECRET =
        "uc5KT2e+qYwa6tb0HUXnLZwsC55VuB93szkSpkucr8i1BFjKA6RXbyIrjk0+ign9"

    fun create(uid: Int, login: String, ttlDays: Int = 30, secret: String = DEFAULT_SECRET): String {
        val now = System.currentTimeMillis() / 1000
        val exp = now + ttlDays.toLong() * 86400
        val payload = """{"uid":$uid,"login":"${Jwt.esc(login)}","iat":$now,"exp":$exp}"""
        return Jwt.sign(payload, secret)
    }
}
