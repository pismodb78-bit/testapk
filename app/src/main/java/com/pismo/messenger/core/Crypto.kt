package com.pismo.messenger.core

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Симметричное шифрование текста сообщений — побайтовый порт PISMO/Crypto.cs.
 *
 * Формат записи (как на ПК): "enc:v2:" + base64(nonce(12) || tag(16) || ciphertext),
 * AES-256-GCM. Старый "enc:v1:" (AES-256-CBC, iv(16) || ciphertext) читается для
 * совместимости. Текст без префикса возвращается как есть.
 *
 * ВНИМАНИЕ ПРО ПОРЯДОК БАЙТ: .NET AesGcm отдаёт шифртекст и тег ОТДЕЛЬНО, и ПК
 * складывает их как nonce||tag||ct. Javax.crypto, наоборот, склеивает ct||tag.
 * Поэтому при шифровании тег переносится вперёд, а при расшифровке —
 * возвращается в хвост. Без этой перестановки расшифровка молча падает на
 * проверке тега, и сообщения ПК выглядели бы как «enc:v2:...».
 *
 * Ключ общий и выводится из той же фразы, что на ПК. Менять её нельзя —
 * иначе перестанут читаться все ранее сохранённые сообщения.
 */
object Crypto {

    private const val PREFIX_V1 = "enc:v1:"
    private const val PREFIX_V2 = "enc:v2:"
    private const val NONCE_LEN = 12
    private const val TAG_LEN = 16

    private val key: SecretKeySpec by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("PISMO::message::secret::v1::do-not-change".toByteArray(Charsets.UTF_8))
        SecretKeySpec(digest, "AES")
    }

    private val rng = SecureRandom()

    /** Шифрует текст в формат ПК-версии. Пустая строка не трогается. */
    fun enc(plain: String?): String {
        if (plain.isNullOrEmpty()) return plain ?: ""
        return try {
            val nonce = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LEN * 8, nonce))

            // Java отдаёт ct||tag — разделяем и пересобираем в порядке .NET.
            val ctWithTag = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val ctLen = ctWithTag.size - TAG_LEN
            if (ctLen < 0) return plain

            val combined = ByteArray(NONCE_LEN + TAG_LEN + ctLen)
            System.arraycopy(nonce, 0, combined, 0, NONCE_LEN)
            System.arraycopy(ctWithTag, ctLen, combined, NONCE_LEN, TAG_LEN)          // tag
            System.arraycopy(ctWithTag, 0, combined, NONCE_LEN + TAG_LEN, ctLen)      // ciphertext

            PREFIX_V2 + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Exception) {
            plain
        }
    }

    /** Расшифровывает, если текст зашифрован; иначе возвращает как есть. */
    fun dec(stored: String?): String {
        if (stored.isNullOrEmpty()) return stored ?: ""

        if (stored.startsWith(PREFIX_V2)) {
            return try {
                val data = Base64.decode(stored.substring(PREFIX_V2.length), Base64.NO_WRAP)
                if (data.size < NONCE_LEN + TAG_LEN) return stored

                val nonce = data.copyOfRange(0, NONCE_LEN)
                val tag = data.copyOfRange(NONCE_LEN, NONCE_LEN + TAG_LEN)
                val ct = data.copyOfRange(NONCE_LEN + TAG_LEN, data.size)

                // Возвращаем тег в хвост — так его ждёт javax.crypto.
                val ctWithTag = ByteArray(ct.size + TAG_LEN)
                System.arraycopy(ct, 0, ctWithTag, 0, ct.size)
                System.arraycopy(tag, 0, ctWithTag, ct.size, TAG_LEN)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN * 8, nonce))
                String(cipher.doFinal(ctWithTag), Charsets.UTF_8)
            } catch (_: Exception) {
                stored   // подмена или порча — не падаем, показываем как есть
            }
        }

        if (stored.startsWith(PREFIX_V1)) {
            return try {
                val data = Base64.decode(stored.substring(PREFIX_V1.length), Base64.NO_WRAP)
                if (data.size <= 16) return stored
                val iv = data.copyOfRange(0, 16)
                val ct = data.copyOfRange(16, data.size)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
                String(cipher.doFinal(ct), Charsets.UTF_8)
            } catch (_: Exception) {
                stored
            }
        }

        return stored
    }
}
