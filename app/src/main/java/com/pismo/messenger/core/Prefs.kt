package com.pismo.messenger.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

/**
 * Хранилище настроек приложения.
 *
 * Заменяет собой ip.txt, devices.ini, turnsettings.json и saved_login.dat
 * из ПК-версии — на Android всё это лежит в одном SharedPreferences.
 */
object Prefs {

    private const val FILE = "pismo_prefs"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    // ── Подключение к MySQL ────────────────────────────────────────────
    // Значения по умолчанию взяты из ip.txt ПК-версии:
    // server=85.174.248.59;port=3307;uid=user1;password=scent01;database=bdauth
    var dbHost: String
        get() = sp.getString("db_host", "85.174.248.59")!!
        set(v) = sp.edit().putString("db_host", v).apply()

    var dbPort: Int
        get() = sp.getInt("db_port", 3307)
        set(v) = sp.edit().putInt("db_port", v).apply()

    var dbName: String
        get() = sp.getString("db_name", "bdauth")!!
        set(v) = sp.edit().putString("db_name", v).apply()

    var dbUser: String
        get() = sp.getString("db_user", "user1")!!
        set(v) = sp.edit().putString("db_user", v).apply()

    var dbPassword: String
        get() = sp.getString("db_pass", "scent01")!!
        set(v) = sp.edit().putString("db_pass", v).apply()

    // ── WebSocket-сигналинг (ws-сервер ПК-версии, порт 8080) ───────────
    var wsEnabled: Boolean
        get() = sp.getBoolean("ws_enabled", true)
        set(v) = sp.edit().putBoolean("ws_enabled", v).apply()

    /** Пусто — значит собрать автоматически как ws://<dbHost>:8080/ */
    var wsUrlOverride: String
        get() = sp.getString("ws_url", "")!!
        set(v) = sp.edit().putString("ws_url", v).apply()

    val wsUrl: String
        get() = wsUrlOverride.ifBlank { "ws://$dbHost:8080/" }

    // ── LiveKit ────────────────────────────────────────────────────────
    var liveKitUrl: String
        get() = sp.getString("lk_url", "ws://5.181.23.167:7880")!!
        set(v) = sp.edit().putString("lk_url", v).apply()

    /**
     * API key и secret. Значения по умолчанию совпадают с
     * LiveKitSettingsModel из ПК-версии — иначе клиенты подпишут токены
     * разными секретами и окажутся в разных комнатах, а звонок между ПК и
     * Android не состоится.
     *
     * БЕЗОПАСНОСТЬ: эта пара лежит в публичном репозитории ПК-версии, то
     * есть уже скомпрометирована — выпустить токен в любую вашу комнату
     * может кто угодно. Из APK секрет достаётся ещё проще (unzip + strings).
     * Оба поля редактируются в настройках приложения именно затем, чтобы
     * после ротации ключей на сервере LiveKit не пересобирать клиент.
     */
    var liveKitApiKey: String
        get() = sp.getString("lk_key", "APIkey5I8EkGBDSc4jdmI5QcVC")!!
        set(v) = sp.edit().putString("lk_key", v).apply()

    var liveKitApiSecret: String
        get() = sp.getString("lk_secret", "Y3pIteGv4BxEEWSmIvE3P9YqDTBdc3nF7IzWNa51flCRS8Gx")!!
        set(v) = sp.edit().putString("lk_secret", v).apply()

    /** TTL токена в секундах. ПК-версия использует 6 часов. */
    var liveKitTokenTtl: Int
        get() = sp.getInt("lk_ttl", 21600)
        set(v) = sp.edit().putInt("lk_ttl", v).apply()

    val liveKitConfigured: Boolean
        get() = liveKitUrl.isNotBlank() &&
                liveKitApiKey.isNotBlank() &&
                liveKitApiSecret.isNotBlank()

    // ── Сохранённый вход ───────────────────────────────────────────────
    var rememberMe: Boolean
        get() = sp.getBoolean("remember", false)
        set(v) = sp.edit().putBoolean("remember", v).apply()

    var savedLogin: String
        get() = sp.getString("saved_login", "")!!
        set(v) = sp.edit().putString("saved_login", v).apply()

    /** Обфускация Base64 — ровно как EncodePassword в ПК-версии, это не шифрование. */
    var savedPassword: String
        get() {
            val raw = sp.getString("saved_pass", "") ?: ""
            if (raw.isBlank()) return ""
            return try {
                String(Base64.decode(raw, Base64.NO_WRAP), Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
        set(v) = sp.edit()
            .putString("saved_pass", Base64.encodeToString(v.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .apply()

    fun clearSavedCredentials() {
        sp.edit().remove("saved_login").remove("saved_pass").putBoolean("remember", false).apply()
    }

    // ── Устройства и медиа ─────────────────────────────────────────────
    /** Множитель усиления микрофона 0.5..2.0 (аналог MicrophoneGain из devices.ini). */
    var micGain: Float
        get() = sp.getFloat("mic_gain", 1.0f)
        set(v) = sp.edit().putFloat("mic_gain", v).apply()

    /** true — фронтальная камера по умолчанию для кружочков и видеозвонков. */
    var frontCamera: Boolean
        get() = sp.getBoolean("front_camera", true)
        set(v) = sp.edit().putBoolean("front_camera", v).apply()

    var notificationsEnabled: Boolean
        get() = sp.getBoolean("notifications", true)
        set(v) = sp.edit().putBoolean("notifications", v).apply()

    /** Фоновый опрос новых сообщений, когда приложение свёрнуто. */
    var backgroundPolling: Boolean
        get() = sp.getBoolean("bg_polling", true)
        set(v) = sp.edit().putBoolean("bg_polling", v).apply()

    // ── Обработка звука в звонке ───────────────────────────────────────
    //
    // Это те же три галочки, что и в SettingsForm ПК-версии. Значения
    // применяются при ЗАХОДЕ в комнату (RoomOptions.audioTrackCaptureDefaults),
    // на лету WebRTC их не переключает — поэтому в настройках честно сказано,
    // что изменения вступят в силу со следующего звонка.

    /** Шумоподавление (WebRTC NS). */
    var noiseSuppression: Boolean
        get() = sp.getBoolean("audio_ns", true)
        set(v) = sp.edit().putBoolean("audio_ns", v).apply()

    /**
     * Сила шумодава, 0..1. Пороги ПК рассчитаны на гарнитуру у рта; у
     * телефона микрофон всенаправленный, поэтому клики клавиатуры и голоса
     * из другой комнаты нужно давить агрессивнее — но платой идёт свой
     * тихий голос, так что значение выбирает пользователь.
     */
    var denoiseStrength: Float
        get() = sp.getFloat("denoise_strength", 0.5f)
        set(v) = sp.edit().putFloat("denoise_strength", v).apply()

    /** Эхоподавление (WebRTC AEC) — без него собеседник слышит сам себя. */
    var echoCancellation: Boolean
        get() = sp.getBoolean("audio_aec", true)
        set(v) = sp.edit().putBoolean("audio_aec", v).apply()

    /** Автоусиление (WebRTC AGC). */
    var autoGainControl: Boolean
        get() = sp.getBoolean("audio_agc", true)
        set(v) = sp.edit().putBoolean("audio_agc", v).apply()

    /**
     * Громкость системного звука в демонстрации экрана, 0..2.
     * По умолчанию 0.6: звук демки подмешивается в микрофонную дорожку, и
     * на единице он забивает голос.
     */
    var screenAudioGain: Float
        get() = sp.getFloat("screen_audio_gain", 0.6f)
        set(v) = sp.edit().putFloat("screen_audio_gain", v).apply()

    // ── Оформление ─────────────────────────────────────────────────────
    /**
     * Тема: "system" (по системной), "dark" или "light".
     * Хранится строкой, а не порядковым номером enum: перестановка
     * элементов в enum молча поменяла бы значение у всех, кто уже выбрал.
     */
    var themeModeName: String
        get() = sp.getString("theme_mode", "system") ?: "system"
        set(v) = sp.edit().putString("theme_mode", v).apply()

    /** Передавать системный звук вместе с демонстрацией экрана. */
    var shareScreenAudio: Boolean
        get() = sp.getBoolean("share_screen_audio", true)
        set(v) = sp.edit().putBoolean("share_screen_audio", v).apply()
}
