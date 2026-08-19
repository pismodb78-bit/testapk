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

    /**
     * Сырая строка индивидуальных настроек звука по собеседникам — её
     * разбирает UserAudioPrefs. На ПК это отдельный user_audio.json; здесь
     * заводить файл ради двух чисел на человека незачем.
     */
    var userAudio: String
        get() = sp.getString("user_audio", "")!!
        set(v) = sp.edit().putString("user_audio", v).apply()

    /**
     * Короткие звуки событий: микрофон, «наушники», камера, демонстрация,
     * вход и выход участников, новое сообщение. Порт Sounds.Enabled.
     *
     * Нужны не для красоты: половина кнопок в звонке меняет то, чего на
     * экране не видно, и без отклика непонятно, сработало нажатие или нет.
     */
    var soundsEnabled: Boolean
        get() = sp.getBoolean("sounds_enabled", true)
        set(v) = sp.edit().putBoolean("sounds_enabled", v).apply()

    /** Фоновый опрос новых сообщений, когда приложение свёрнуто. */
    var backgroundPolling: Boolean
        get() = sp.getBoolean("bg_polling", true)
        set(v) = sp.edit().putBoolean("bg_polling", v).apply()

    // ── Отметки «о чём уже сообщали» ───────────────────────────────────
    //
    // Опрос уведомлений сравнивает текущее состояние с предыдущим, и
    // предыдущее раньше жило только в памяти сервиса. Из-за этого первый
    // проход после запуска ЛЮБОЕ накопившееся считал «уже известным»:
    // написанное, пока приложение было закрыто, не давало уведомлений
    // вообще — а это как раз тот случай, ради которого они и нужны.
    // Теперь отметки переживают перезапуск процесса.
    //
    // Формат простой: «id:значение,id:значение». Заводить ради четырёх
    // счётчиков базу или сериализацию незачем.

    var notifyBaselineDm: String
        get() = sp.getString("notify_base_dm", "")!!
        set(v) = sp.edit().putString("notify_base_dm", v).apply()

    var notifyBaselineGroup: String
        get() = sp.getString("notify_base_group", "")!!
        set(v) = sp.edit().putString("notify_base_group", v).apply()

    var notifyBaselineChannel: String
        get() = sp.getString("notify_base_channel", "")!!
        set(v) = sp.edit().putString("notify_base_channel", v).apply()

    var notifyBaselineFriends: String
        get() = sp.getString("notify_base_friends", "")!!
        set(v) = sp.edit().putString("notify_base_friends", v).apply()

    /** Сброс отметок — при смене пользователя чужие счётчики не наши. */
    fun clearNotifyBaselines() {
        sp.edit()
            .remove("notify_base_dm")
            .remove("notify_base_group")
            .remove("notify_base_channel")
            .remove("notify_base_friends")
            .apply()
    }

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
     * Сила шумодава, 0..1 — тот же ползунок, что NoiseSuppressionStrength
     * (0..100 %) на ПК, и по умолчанию так же на максимуме.
     *
     * Раньше здесь стояло 0.5, потому что старый алгоритм на большой силе
     * рвал голос: «сила» задирала пороги гейта. Теперь под ползунком
     * винеровский фильтр, который давит фон по частотам, а не по громкости, —
     * его максимум звучит ровно, и занижать значение больше незачем.
     */
    var denoiseStrength: Float
        get() = sp.getFloat("denoise_strength", 1.0f)
        set(v) = sp.edit().putFloat("denoise_strength", v).apply()

    /**
     * Автоопределение чувствительности микрофона (как в Discord) — порт
     * VoiceAutoSensitivity. true = звук передаётся всегда, порог не действует.
     */
    var voiceAutoSensitivity: Boolean
        get() = sp.getBoolean("voice_auto_sensitivity", false)
        set(v) = sp.edit().putBoolean("voice_auto_sensitivity", v).apply()

    /**
     * Ручной порог активации голоса в дБ (−60..0) — порт VoiceThreshold.
     * Звук тише порога не передаётся; именно им отрезаются тихие шумы и
     * разговоры из другой комнаты. Действует при voiceAutoSensitivity = false.
     */
    var voiceThresholdDb: Int
        get() = sp.getInt("voice_threshold_db", -40)
        set(v) = sp.edit().putInt("voice_threshold_db", v.coerceIn(-60, 0)).apply()

    /**
     * Усиление голоса на выходе цепи обработки, 0..300 % — порт
     * VoiceOutputGain. Шумодав приглушает голос, этим добираем громкость.
     */
    var voiceOutputGain: Int
        get() = sp.getInt("voice_output_gain", 100)
        set(v) = sp.edit().putInt("voice_output_gain", v.coerceIn(0, 300)).apply()

    /** Эхоподавление (WebRTC AEC) — без него собеседник слышит сам себя. */
    var echoCancellation: Boolean
        get() = sp.getBoolean("audio_aec", true)
        set(v) = sp.edit().putBoolean("audio_aec", v).apply()

    /** Автоусиление (WebRTC AGC). */
    var autoGainControl: Boolean
        get() = sp.getBoolean("audio_agc", true)
        set(v) = sp.edit().putBoolean("audio_agc", v).apply()

    /**
     * Качество демонстрации экрана: частота кадров 15 / 30 / 60.
     *
     * По умолчанию 15: у демонстрации почти всегда показывают интерфейс и
     * текст, и там важнее разрешение, чем частота кадров. WebRTC при нехватке
     * канала для демонстрации сам жертвует кадрами, а не чёткостью
     * (degradationPreference = MAINTAIN_RESOLUTION), так что низкая частота
     * не портит картинку — она просто честно отражает, сколько влезло.
     *
     * 60 кадров имеет смысл только для игр и видео и только на хорошей сети:
     * канал под них нужен вдвое шире, а телефонный кодировщик на родном
     * разрешении экрана столько кадров может и не вытянуть.
     */
    var screenShareQuality: Int
        get() = sp.getInt("screen_share_quality", 0)
        set(v) = sp.edit().putInt("screen_share_quality", v.coerceIn(0, 2)).apply()

    /**
     * Кодек исходящей демонстрации: "h264" или "vp8".
     *
     * По умолчанию H.264, и это не косметика. Аппаратный кодировщик H.264
     * есть в любом телефоне, а VP8 в железе почти ни у кого — его кодируют
     * процессором, и на экране в полтора-два мегапикселя это упирается в
     * потолок: кадры пропускаются, битрейт режется, картинка расплывается.
     * Ровно на это и была жалоба про качество демок.
     *
     * ПК декодирует H.264 — это один из двух кодеков, которые он и сам
     * предлагает для своей демонстрации, так что совместимость не страдает.
     *
     * AV1 и VP9 сюда намеренно не попали: SDK на SVC-кодеках принудительно
     * включает dynacast, а он у нас однажды уже сломал демонстрацию.
     *
     * Если у какого-то телефона H.264-кодировщик окажется кривым, VP8
     * переключается в настройках, без пересборки. А если сервер кодек не
     * разрешит, SDK сам откатится на разрешённый.
     */
    var screenShareCodec: String
        get() = sp.getString("screen_share_codec", "h264")!!
        set(v) = sp.edit().putString("screen_share_codec", v).apply()

    /** Частота кадров демонстрации, выведенная из качества. */
    val screenShareFps: Int
        get() = when (screenShareQuality) {
            2 -> 60
            1 -> 30
            else -> 15
        }

    /**
     * Потолок битрейта демонстрации.
     *
     * Значение по умолчанию у SDK — 7 Мбит/с, и на мобильном канале это
     * фикция: оценщик всё равно упрётся в реальную пропускную способность.
     * Числа взяты под вертикальный экран телефона (это заметно больше
     * пикселей, чем 16:9 той же высоты) с запасом на статичную картинку.
     */
    val screenShareBitrate: Int
        get() = when (screenShareQuality) {
            // Битрейт растёт медленнее, чем кадры: при вдвое большей частоте
            // соседние кадры отличаются вдвое меньше, и вдвое больше бит им
            // не нужно. А вот запас нужен — на 60 кадрах любое движение
            // выедает полосу мгновенно.
            2 -> 8_000_000
            1 -> 6_000_000
            else -> 4_000_000
        }

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
