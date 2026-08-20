package com.pismo.messenger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.Updater
import com.pismo.messenger.data.ChatDiskCache
import com.pismo.messenger.data.ChatListMemory
import com.pismo.messenger.data.MediaCache
import com.pismo.messenger.data.MessageMemory
import com.pismo.messenger.data.ServerMemory
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.media.MicLevelMonitor
import com.pismo.messenger.media.Sounds
import com.pismo.messenger.ui.components.UpdateDialogHost
import com.pismo.messenger.ui.login.PismoField
import com.pismo.messenger.ui.theme.PismoColors
import com.pismo.messenger.ui.theme.ThemeMode
import com.pismo.messenger.ui.theme.themeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(Prefs.dbHost) }
    var port by remember { mutableStateOf(Prefs.dbPort.toString()) }
    var dbName by remember { mutableStateOf(Prefs.dbName) }
    var dbUser by remember { mutableStateOf(Prefs.dbUser) }
    var dbPass by remember { mutableStateOf(Prefs.dbPassword) }

    var lkUrl by remember { mutableStateOf(Prefs.liveKitUrl) }
    var lkKey by remember { mutableStateOf(Prefs.liveKitApiKey) }
    var lkSecret by remember { mutableStateOf(Prefs.liveKitApiSecret) }

    var wsUrl by remember { mutableStateOf(Prefs.wsUrlOverride) }
    var wsEnabled by remember { mutableStateOf(Prefs.wsEnabled) }
    var notifications by remember { mutableStateOf(Prefs.notificationsEnabled) }
    var sounds by remember { mutableStateOf(Prefs.soundsEnabled) }
    var micGain by remember { mutableStateOf(Prefs.micGain) }
    var checkUpdates by remember { mutableStateOf(Prefs.checkUpdatesOnStart) }
    var frontCamera by remember { mutableStateOf(Prefs.frontCamera) }
    var bgPolling by remember { mutableStateOf(Prefs.backgroundPolling) }
    var noiseSuppression by remember { mutableStateOf(Prefs.noiseSuppression) }
    var echoCancellation by remember { mutableStateOf(Prefs.echoCancellation) }
    var autoGain by remember { mutableStateOf(Prefs.autoGainControl) }
    var screenGain by remember { mutableStateOf(Prefs.screenAudioGain) }
    var screenQuality by remember { mutableStateOf(Prefs.screenShareQuality) }
    var screenCodec by remember { mutableStateOf(Prefs.screenShareCodec) }
    var screenSmooth by remember { mutableStateOf(Prefs.screenShareSmooth) }
    var denoiseStrength by remember { mutableStateOf(Prefs.denoiseStrength) }
    var voiceAuto by remember { mutableStateOf(Prefs.voiceAutoSensitivity) }
    var voiceThreshold by remember { mutableStateOf(Prefs.voiceThresholdDb.toFloat()) }
    var voiceOutGain by remember { mutableStateOf(Prefs.voiceOutputGain.toFloat()) }

    // Настройки обработки микрофона применяются к ИДУЩЕМУ разговору сразу.
    // Это наш обработчик, а не цепочка WebRTC, поэтому пересобирать комнату
    // не нужно — и ради этого звонок теперь можно свернуть, а не бросить.
    fun applyLive() {
        val engine = com.pismo.messenger.call.ActiveCall.engine ?: return
        engine.setNoiseSuppression(noiseSuppression)
        engine.setDenoiseStrength(denoiseStrength)
        engine.setVoiceGate(voiceAuto, voiceThreshold.toInt())
        engine.setVoiceOutputGain(voiceOutGain.toInt())
    }
    // Тема применяется сразу по нажатию, а не по кнопке «Сохранить»: иначе
    // непонятно, что именно выбрал — палитра-то не поменялась.
    var theme by remember { mutableStateOf(themeMode) }

    var status by remember { mutableStateOf("") }
    var cacheSize by remember { mutableStateOf(0L) }

    // Обход кеша — это работа с файловой системой, в композиции ей не место.
    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) {
            MediaCache.sizeBytes() + ChatDiskCache.sizeBytes() + ChatListMemory.sizeBytes()
        }
    }

    fun save() {
        Prefs.dbHost = host.trim()
        Prefs.dbPort = port.toIntOrNull() ?: 3306
        Prefs.dbName = dbName.trim()
        Prefs.dbUser = dbUser.trim()
        Prefs.dbPassword = dbPass
        Prefs.liveKitUrl = lkUrl.trim()
        Prefs.liveKitApiKey = lkKey.trim()
        Prefs.liveKitApiSecret = lkSecret.trim()
        Prefs.wsUrlOverride = wsUrl.trim()
        Prefs.wsEnabled = wsEnabled
        Prefs.notificationsEnabled = notifications
        Prefs.soundsEnabled = sounds
        Prefs.micGain = micGain
        Prefs.checkUpdatesOnStart = checkUpdates
        Prefs.frontCamera = frontCamera
        Prefs.backgroundPolling = bgPolling
        Prefs.noiseSuppression = noiseSuppression
        Prefs.echoCancellation = echoCancellation
        Prefs.autoGainControl = autoGain
        Prefs.screenAudioGain = screenGain
        Prefs.screenShareQuality = screenQuality
        Prefs.screenShareCodec = screenCodec
        Prefs.screenShareSmooth = screenSmooth
        Prefs.denoiseStrength = denoiseStrength
        Prefs.voiceAutoSensitivity = voiceAuto
        Prefs.voiceThresholdDb = voiceThreshold.toInt()
        Prefs.voiceOutputGain = voiceOutGain.toInt()
        scope.launch { Db.closeAll() }
        status = "Настройки сохранены."
    }

    Scaffold(
        containerColor = PismoColors.BgMain,
        // Полоска активного звонка и здесь: настройки шумодава крутят как раз
        // во время разговора, и возвращаться в него нужно одним нажатием, а
        // не через два экрана назад.
        bottomBar = { com.pismo.messenger.ui.call.VoiceDock() },
        topBar = {
            TopAppBar(
                title = { Text("Настройки", color = PismoColors.TextPrimary, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад", tint = PismoColors.TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PismoColors.BgDarkest),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Section("Подключение к базе данных")
            Text(
                "Те же параметры, что в ip.txt на ПК.",
                color = PismoColors.TextMuted, fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            PismoField(host, { host = it }, "Сервер")
            Spacer(Modifier.height(8.dp))
            PismoField(port, { port = it }, "Порт", keyboardType = KeyboardType.Number)
            Spacer(Modifier.height(8.dp))
            PismoField(dbName, { dbName = it }, "База данных")
            Spacer(Modifier.height(8.dp))
            PismoField(dbUser, { dbUser = it }, "Пользователь")
            Spacer(Modifier.height(8.dp))
            PismoField(dbPass, { dbPass = it }, "Пароль")

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    save()
                    scope.launch {
                        status = "Проверка…"
                        val r = Db.testConnection()
                        status = r.fold(
                            onSuccess = { "✓ Подключение успешно. MySQL $it" },
                            onFailure = { "✗ ${it.message}" },
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PismoColors.Blurple),
                shape = RoundedCornerShape(8.dp),
            ) { Text("Проверить подключение", color = Color.White) }

            Spacer(Modifier.height(20.dp))
            Section("Звонки (LiveKit)")
            Text(
                "Ключ и секрет обязаны совпадать с livekitsettings.json на ПК — " +
                        "иначе клиенты попадут в разные комнаты.",
                color = PismoColors.TextMuted, fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            PismoField(lkUrl, { lkUrl = it }, "URL сервера LiveKit")
            Text(
                "Схема ws:// — это норма: сигналинг LiveKit идёт по WebSocket, " +
                        "и на ПК стоит тот же ws://5.181.23.167:7880. TLS нет, потому " +
                        "что сервер живёт на голом IP без домена; медиа при этом всё " +
                        "равно шифруется (SRTP), а вот сигналинг и токен комнаты " +
                        "ходят открытым текстом.",
                color = PismoColors.TextMuted, fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            PismoField(lkKey, { lkKey = it }, "API key")
            Spacer(Modifier.height(8.dp))
            PismoField(lkSecret, { lkSecret = it }, "API secret")
            Spacer(Modifier.height(6.dp))
            Text(
                "⚠ Значения по умолчанию взяты из публичного репозитория ПК-версии " +
                        "и считаются скомпрометированными: выпустить токен в вашу комнату " +
                        "может кто угодно. Ключи стоит ротировать на сервере LiveKit " +
                        "и вписать сюда новые.",
                color = PismoColors.Yellow, fontSize = 12.sp,
            )

            Spacer(Modifier.height(20.dp))
            Section("Сигналинг и уведомления")
            PismoField(wsUrl, { wsUrl = it }, "WebSocket (пусто — собрать по адресу БД)")
            Spacer(Modifier.height(4.dp))
            // Показываем, что получится на самом деле. Пустое поле — это не
            // «не настроено», а «взять хост от базы и порт 8080», ровно как
            // GetWebSocketUrl на ПК берёт хост из ip.txt. Но по пустой
            // строке этого не видно, и выглядит как забытая настройка.
            Text(
                "Используется: " + wsUrl.trim().ifBlank { "ws://${host.trim()}:8080/" },
                color = PismoColors.Cyan, fontSize = 12.sp,
            )
            Text(
                "Своё значение нужно, только если сигналинг живёт отдельно от " +
                        "базы. ПК берёт адрес так же — из ip.txt, порт 8080.",
                color = PismoColors.TextMuted, fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            SwitchRow("Мгновенные события через WebSocket", wsEnabled) { wsEnabled = it }
            SwitchRow("Уведомления о новых сообщениях", notifications) { notifications = it }

            // Звук применяется сразу, без «Сохранить»: иначе непонятно, что
            // именно переключили — проверить-то можно только на слух.
            SwitchRow("Звуки событий", sounds) {
                sounds = it
                Prefs.soundsEnabled = it
                if (it) Sounds.micOn()
            }
            Text(
                "Короткие сигналы на микрофон, «наушники», камеру, демонстрацию, " +
                        "вход и выход собеседников и новое сообщение. Половина кнопок " +
                        "в звонке меняет то, чего на экране не видно, — по звуку " +
                        "понятно, что нажатие сработало.",
                color = PismoColors.TextMuted, fontSize = 11.sp,
            )

            Spacer(Modifier.height(20.dp))
            Section("Устройства")
            Text(
                "Усиление микрофона: ${(micGain * 100).toInt()}%",
                color = PismoColors.TextSecondary, fontSize = 14.sp,
            )
            Slider(
                value = micGain,
                onValueChange = {
                    micGain = it
                    com.pismo.messenger.call.ActiveCall.engine?.previewMicGain(it)
                },
                onValueChangeFinished = {
                    Prefs.micGain = micGain
                    com.pismo.messenger.call.ActiveCall.engine?.setMicGain(micGain)
                },
                valueRange = 0.5f..2.0f,
                steps = 14,
                colors = SliderDefaults.colors(
                    thumbColor = PismoColors.Blurple,
                    activeTrackColor = PismoColors.Blurple,
                ),
            )
            Text(
                "Тот же множитель, что MicrophoneGain в devices.ini на ПК: он " +
                        "поднимает микрофон в самом начале цепочки — до порога " +
                        "активации и шумодава, — поэтому тихий микрофон стоит " +
                        "вытягивать именно здесь, а не громкостью на выходе. " +
                        "Действует и в звонке (сразу, не выходя из разговора), и в " +
                        "голосовых сообщениях с видео-кружочками.",
                color = PismoColors.TextMuted, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            SwitchRow("Фронтальная камера по умолчанию", frontCamera) { frontCamera = it }
            SwitchRow("Фоновая проверка сообщений", bgPolling) { bgPolling = it }

            Spacer(Modifier.height(20.dp))
            Section("Оформление")
            Text(
                "У ПК-версии светлой темы нет — она целиком тёмная. Здесь " +
                        "светлая подобрана по тем же ролям цветов; фирменные " +
                        "(синий, статусы, ошибки) в обеих одинаковы.",
                color = PismoColors.TextMuted, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                ThemeChip("Системная", theme == ThemeMode.SYSTEM) {
                    theme = ThemeMode.SYSTEM
                    Prefs.themeModeName = theme.stored
                }
                Spacer(Modifier.width(8.dp))
                ThemeChip("Тёмная", theme == ThemeMode.DARK) {
                    theme = ThemeMode.DARK
                    Prefs.themeModeName = theme.stored
                }
                Spacer(Modifier.width(8.dp))
                ThemeChip("Светлая", theme == ThemeMode.LIGHT) {
                    theme = ThemeMode.LIGHT
                    Prefs.themeModeName = theme.stored
                }
            }

            Spacer(Modifier.height(20.dp))
            Section("Обработка звука в звонке")
            val inCall = com.pismo.messenger.call.ActiveCall.isActive
            if (inCall) {
                Text(
                    "Идёт разговор — эти настройки применяются сразу, на лету.",
                    color = PismoColors.Green, fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
            }
            SwitchRow("Шумоподавление", noiseSuppression) {
                noiseSuppression = it
                Prefs.noiseSuppression = it
                applyLive()
            }
            if (noiseSuppression) {
                Text(
                    "Сила подавления: ${(denoiseStrength * 100).toInt()}%",
                    color = PismoColors.TextSecondary, fontSize = 13.sp,
                )
                Slider(
                    value = denoiseStrength,
                    onValueChange = {
                        denoiseStrength = it
                        com.pismo.messenger.call.ActiveCall.engine
                            ?.previewDenoiseStrength(it)
                    },
                    onValueChangeFinished = {
                        Prefs.denoiseStrength = denoiseStrength
                        applyLive()
                    },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(thumbColor = PismoColors.Blurple),
                )
                Text(
                    "Частотный шумодав: постоянный фон (кулер, шипение, гул, " +
                            "дальний гомон) вырезается по частотам, а следом " +
                            "лимитер давит короткие щелчки — клавиатуру и мышь. " +
                            "Голос он не режет, поэтому держать на максимуме " +
                            "нормально. Это наш обработчик, а не галочка WebRTC, " +
                            "поэтому меняется прямо в разговоре.",
                    color = PismoColors.TextMuted, fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(10.dp))
            SwitchRow("Автоопределение чувствительности", voiceAuto) {
                voiceAuto = it
                Prefs.voiceAutoSensitivity = it
                applyLive()
            }
            if (!voiceAuto) {
                Text(
                    "Порог активации голоса: ${voiceThreshold.toInt()} дБ",
                    color = PismoColors.TextSecondary, fontSize = 13.sp,
                )
                Slider(
                    value = voiceThreshold,
                    onValueChange = {
                        voiceThreshold = it
                        // Порог уезжает в идущий разговор сразу, ещё до
                        // отпускания: иначе по шкале не поймать момент, когда
                        // собственная речь начинает обрезаться.
                        com.pismo.messenger.call.ActiveCall.engine
                            ?.previewVoiceGate(voiceAuto, it.toInt())
                    },
                    onValueChangeFinished = {
                        Prefs.voiceThresholdDb = voiceThreshold.toInt()
                        applyLive()
                    },
                    valueRange = -60f..0f,
                    colors = SliderDefaults.colors(thumbColor = PismoColors.Blurple),
                )
                // Живая полоса под ползунком — как в тесте микрофона на ПК.
                // Без неё порог ставится наугад: «−31 дБ» само по себе ничего
                // не значит, надо видеть, где относительно этой черты
                // оказывается своя речь, а где фон комнаты.
                MicLevelBar(thresholdDb = voiceThreshold)
                Text(
                    "Полоса показывает текущую громкость с микрофона. Зелёное — " +
                            "громче порога, такой звук уходит собеседнику; серое " +
                            "слева от чёрточки отсекается. Говорите и двигайте " +
                            "ползунок так, чтобы речь была зелёной, а фон комнаты — нет.",
                    color = PismoColors.TextMuted, fontSize = 11.sp,
                )
            } else {
                Text(
                    "Звук передаётся всегда, без порога — как автоматический режим " +
                            "в Discord.",
                    color = PismoColors.TextMuted, fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Громкость голоса на выходе: ${voiceOutGain.toInt()}%",
                color = PismoColors.TextSecondary, fontSize = 13.sp,
            )
            Slider(
                value = voiceOutGain,
                onValueChange = {
                    voiceOutGain = it
                    com.pismo.messenger.call.ActiveCall.engine
                        ?.previewVoiceOutputGain(it.toInt())
                },
                onValueChangeFinished = {
                    Prefs.voiceOutputGain = voiceOutGain.toInt()
                    applyLive()
                },
                valueRange = 0f..300f,
                colors = SliderDefaults.colors(thumbColor = PismoColors.Blurple),
            )

            Text(
                "Шумодав неизбежно приглушает голос — здесь громкость добирается " +
                        "обратно. Усиление линейное до самого потолка и лишь на пиках " +
                        "мягко ограничивается, так что 300% это действительно втрое " +
                        "громче, без хрипа.",
                color = PismoColors.TextMuted, fontSize = 11.sp,
            )
            SwitchRow("Эхоподавление", echoCancellation) { echoCancellation = it }
            SwitchRow("Автоусиление громкости", autoGain) { autoGain = it }
            Text(
                "WebRTC собирает цепочку обработки при входе в комнату, поэтому " +
                        "изменения применятся со следующего звонка. Эхоподавление лучше " +
                        "не выключать: без него собеседник слышит собственный голос.",
                color = PismoColors.TextMuted, fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))
            Section("Демонстрация экрана")
            Text("Качество", color = PismoColors.TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                ThemeChip("15 fps", screenQuality == 0) { screenQuality = 0 }
                Spacer(Modifier.width(8.dp))
                ThemeChip("30 fps", screenQuality == 1) { screenQuality = 1 }
                Spacer(Modifier.width(8.dp))
                ThemeChip("60 fps", screenQuality == 2) { screenQuality = 2 }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when (screenQuality) {
                    2 -> "До 14 Мбит/с — для игр и видео. Канал нужен вдвое шире, " +
                            "и кодировщик телефона на родном разрешении экрана " +
                            "столько кадров может и не вытянуть."
                    1 -> "До 10 Мбит/с — плавно, годится и для видео."
                    else -> "До 6 Мбит/с. Экран почти всегда показывают ради " +
                            "интерфейса и текста, а там важнее чёткость, чем " +
                            "частота кадров."
                },
                color = PismoColors.TextMuted, fontSize = 11.sp,
            )
            Spacer(Modifier.height(12.dp))
            SwitchRow("Плавность важнее чёткости", screenSmooth) { screenSmooth = it }
            Text(
                if (screenSmooth)
                    "При нехватке канала WebRTC понижает разрешение, но держит " +
                            "частоту кадров — движение остаётся плавным. Так и " +
                            "надо для игр, видео и вообще всего, что движется."
                else
                    "При нехватке канала WebRTC бережёт чёткость и роняет кадры — " +
                            "картинка идёт рывками, зато мелкий текст читается. " +
                            "Разумно, когда показывают код или документ.",
                color = PismoColors.TextMuted, fontSize = 11.sp,
            )

            Spacer(Modifier.height(12.dp))
            Text("Кодек", color = PismoColors.TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                ThemeChip("H.264", screenCodec == "h264") { screenCodec = "h264" }
                Spacer(Modifier.width(8.dp))
                ThemeChip("AV1", screenCodec == "av1") { screenCodec = "av1" }
                Spacer(Modifier.width(8.dp))
                ThemeChip("VP8", screenCodec == "vp8") { screenCodec = "vp8" }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when (screenCodec) {
                    "h264" -> "H.264 кодируется железом на любом телефоне — отсюда и " +
                            "чёткость на родном разрешении. ПК его понимает: это " +
                            "один из двух кодеков, которые он предлагает и для " +
                            "своей демонстрации."
                    "av1" -> "AV1 — то, чем показывает экран ПК. Но кодировать его " +
                            "телефону почти наверняка придётся процессором: " +
                            "аппаратный кодировщик AV1 есть лишь у единиц самых " +
                            "свежих чипов, и на родном разрешении экрана это " +
                            "означает единицы кадров в секунду. Пробуйте, но если " +
                            "демонстрация станет рваной или не начнётся вовсе — " +
                            "возвращайтесь на H.264."
                    else -> "VP8 почти нигде не кодируется железом — его считает " +
                            "процессор, и на экране в полтора мегапикселя это " +
                            "упирается в потолок: кадры пропускаются, картинка " +
                            "расплывается. Оставляйте, только если на H.264 " +
                            "демонстрация не идёт вовсе."
                },
                color = PismoColors.TextMuted, fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Разрешение всегда родное, без обрезки: экран телефона " +
                        "вертикальный, и подгонка под 16:9 срезала бы верх и низ. " +
                        "При нехватке канала WebRTC жертвует кадрами, а не " +
                        "чёткостью. Применяется со следующего звонка.",
                color = PismoColors.TextMuted, fontSize = 11.sp,
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "Громкость системного звука в демонстрации: ${(screenGain * 100).toInt()}%",
                color = PismoColors.TextSecondary, fontSize = 13.sp,
            )
            Slider(
                value = screenGain,
                onValueChange = { screenGain = it },
                valueRange = 0f..2f,
                colors = SliderDefaults.colors(thumbColor = PismoColors.Blurple),
            )
            Text(
                "Звук демонстрации подмешивается в микрофонную дорожку — на Android " +
                        "вторую аудиодорожку опубликовать нельзя. На 100% он заглушает голос, " +
                        "поэтому по умолчанию 60%.",
                color = PismoColors.TextMuted, fontSize = 12.sp,
            )

            Spacer(Modifier.height(20.dp))
            Section("О приложении")
            Text(
                "PISMO для Android, версия ${com.pismo.messenger.BuildConfig.VERSION_NAME} " +
                        "(сборка ${com.pismo.messenger.BuildConfig.VERSION_CODE})",
                color = PismoColors.TextSecondary, fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { scope.launch { Updater.check(manual = true) } },
                colors = ButtonDefaults.buttonColors(containerColor = PismoColors.BgElevated),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Проверить обновления", color = PismoColors.TextPrimary)
            }
            SwitchRow("Проверять при запуске", checkUpdates) { checkUpdates = it }
            Text(
                "Приложение берёт новую версию со страницы релизов на GitHub и " +
                        "отдаёт её системному установщику — как на ПК, где оно " +
                        "распаковывает архив поверх себя. Android поставит " +
                        "обновление, только если оно подписано тем же ключом, " +
                        "поэтому обновляются сборки из GitHub Actions; APK, " +
                        "собранный вручную, придётся один раз заменить.",
                color = PismoColors.TextMuted, fontSize = 11.sp,
            )
            // Тот же диалог, что и на главном экране: одновременно составлен
            // ровно один из этих экранов, так что двух окон не будет.
            UpdateDialogHost()

            Spacer(Modifier.height(20.dp))
            Section("Кеш")
            // Раньше здесь было деление на МБ в целых числах, и любой кеш
            // меньше мегабайта показывался как «0 МБ» — выглядело так, будто
            // ничего не сохраняется вообще.
            Text(
                "Занято: " + formatBytes(cacheSize),
                color = PismoColors.TextSecondary, fontSize = 13.sp,
            )
            Text(
                "Вложения и переписки хранятся на устройстве и переживают " +
                        "закрытие приложения — поэтому чаты открываются сразу, " +
                        "не дожидаясь ответа базы. Текст сообщений лежит " +
                        "зашифрованным, тем же ключом, что и в базе.",
                color = PismoColors.TextMuted, fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    MediaCache.clear()
                    MessageMemory.clear()
                    ServerMemory.clear()
                    ChatListMemory.clear()
                    cacheSize = 0
                    status = "Кеш очищен."
                },
                colors = ButtonDefaults.buttonColors(containerColor = PismoColors.BgElevated),
                shape = RoundedCornerShape(8.dp),
            ) { Text("Очистить кеш", color = PismoColors.TextPrimary) }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { save() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PismoColors.Blurple),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Сохранить", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            if (status.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    status,
                    color = if (status.startsWith("✗")) PismoColors.Red else PismoColors.Green,
                    fontSize = 13.sp,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = PismoColors.TextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = PismoColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = PismoColors.Blurple),
        )
    }
}

/** «12 КБ» / «3,4 МБ» — целые мегабайты врали про пустой кеш. */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "пусто"
    bytes < 1024L -> "$bytes Б"
    bytes < 1024L * 1024L -> "${bytes / 1024L} КБ"
    else -> String.format(java.util.Locale.getDefault(), "%.1f МБ", bytes / 1024.0 / 1024.0)
}

/** Кнопка выбора темы. Выбранная подсвечивается фирменным синим. */
@Composable
private fun RowScope.ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) PismoColors.Blurple else PismoColors.BgElevated)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color.White else PismoColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}


/**
 * Индикатор уровня микрофона под ползунком порога — порт полосы из
 * MicTestForm ПК-версии.
 *
 * Шкала совпадает со шкалой ползунка (−60…0 дБ), поэтому положение
 * чёрточки-порога и высота уровня сравниваются напрямую. Захват держится
 * только пока индикатор на экране: микрофон — общий ресурс, и оставлять его
 * занятым после ухода из настроек нельзя.
 */
@Composable
private fun MicLevelBar(thresholdDb: Float) {
    val scope = rememberCoroutineScope()
    val level by MicLevelMonitor.levelDb.collectAsState()

    // В звонке уровень берётся прямо из цепочки разговора, а не из своего
    // микрофона: у той величины и порог, и звук — общие.
    val inCall = com.pismo.messenger.call.ActiveCall.engine != null

    DisposableEffect(Unit) {
        MicLevelMonitor.acquire(scope)
        onDispose { MicLevelMonitor.release() }
    }

    val floor = MicLevelMonitor.FLOOR_DB
    val fraction = ((level - floor) / -floor).coerceIn(0f, 1f)
    val thresholdFraction = ((thresholdDb - floor) / -floor).coerceIn(0f, 1f)
    val open = level >= thresholdDb

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(PismoColors.BgDarkest)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (open) PismoColors.Green else PismoColors.TextMuted)
            )
            // Чёрточка порога: всё, что левее неё, в эфир не уходит.
            Box(
                Modifier
                    .fillMaxWidth(thresholdFraction)
                    .height(10.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(14.dp)
                        .background(PismoColors.TextPrimary)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            when {
                level <= floor + 0.5f -> if (inCall) "Тишина (уровень из звонка)" else "Тишина"
                inCall -> "${level.toInt()} дБ — уровень из идущего разговора"
                // Вне звонка меряется сырой микрофон, а порог сравнивается с
                // сигналом ПОСЛЕ автоусиления WebRTC. Разницу компенсируем
                // такой же автоматикой, но это оценка — точная цифра в звонке.
                else -> "${level.toInt()} дБ — оценка, точный уровень виден в звонке"
            },
            color = if (inCall) PismoColors.Green else PismoColors.TextMuted,
            fontSize = 10.sp,
        )
    }
}
