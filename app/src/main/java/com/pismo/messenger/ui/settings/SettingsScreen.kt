package com.pismo.messenger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.pismo.messenger.data.MediaCache
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.ui.login.PismoField
import com.pismo.messenger.ui.theme.PismoColors
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
    var micGain by remember { mutableStateOf(Prefs.micGain) }
    var frontCamera by remember { mutableStateOf(Prefs.frontCamera) }
    var bgPolling by remember { mutableStateOf(Prefs.backgroundPolling) }

    var status by remember { mutableStateOf("") }
    var cacheSize by remember { mutableStateOf(0L) }

    // Обход кеша — это работа с файловой системой, в композиции ей не место.
    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) { MediaCache.sizeBytes() }
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
        Prefs.micGain = micGain
        Prefs.frontCamera = frontCamera
        Prefs.backgroundPolling = bgPolling
        scope.launch { Db.closeAll() }
        status = "Настройки сохранены."
    }

    Scaffold(
        containerColor = PismoColors.BgMain,
        topBar = {
            TopAppBar(
                title = { Text("Настройки", color = Color.White, fontSize = 18.sp) },
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
            PismoField(wsUrl, { wsUrl = it }, "WebSocket (пусто — ws://<сервер>:8080/)")
            Spacer(Modifier.height(8.dp))
            SwitchRow("Мгновенные события через WebSocket", wsEnabled) { wsEnabled = it }
            SwitchRow("Уведомления о новых сообщениях", notifications) { notifications = it }

            Spacer(Modifier.height(20.dp))
            Section("Устройства")
            Text(
                "Усиление микрофона: ${(micGain * 100).toInt()}%",
                color = PismoColors.TextSecondary, fontSize = 14.sp,
            )
            Slider(
                value = micGain,
                onValueChange = { micGain = it },
                valueRange = 0.5f..2.0f,
                steps = 14,
                colors = SliderDefaults.colors(
                    thumbColor = PismoColors.Blurple,
                    activeTrackColor = PismoColors.Blurple,
                ),
            )
            Text(
                "Применяется к голосовым сообщениям и видео-кружочкам — тот же " +
                        "множитель, что MicrophoneGain в devices.ini на ПК.",
                color = PismoColors.TextMuted, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            SwitchRow("Фронтальная камера по умолчанию", frontCamera) { frontCamera = it }
            SwitchRow("Фоновая проверка сообщений", bgPolling) { bgPolling = it }

            Spacer(Modifier.height(20.dp))
            Section("Кеш медиа")
            // Раньше здесь было деление на МБ в целых числах, и любой кеш
            // меньше мегабайта показывался как «0 МБ» — выглядело так, будто
            // ничего не сохраняется вообще.
            Text(
                "Занято: " + formatBytes(cacheSize),
                color = PismoColors.TextSecondary, fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    MediaCache.clear()
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
        color = Color.White,
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
