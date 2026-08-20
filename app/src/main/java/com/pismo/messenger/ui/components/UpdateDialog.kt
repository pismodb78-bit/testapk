package com.pismo.messenger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.Updater
import com.pismo.messenger.ui.theme.PismoColors

/**
 * Окна обновления — одно на все экраны.
 *
 * Ставится и на главном экране (там проверка идёт сама при запуске), и в
 * настройках (там её запускают кнопкой). Одновременно составлен ровно один из
 * них — навигация держит в композиции только текущий пункт назначения, —
 * поэтому двух диалогов быть не может, а логика закачки и установки живёт в
 * одном месте.
 *
 * Само скачивание идёт в области Updater, а не этого экрана: уйти в чаты,
 * пока качается, — нормальное желание, и закачку это прерывать не должно.
 */
@Composable
fun UpdateDialogHost() {
    val state by Updater.state.collectAsState()
    val context = LocalContext.current

    when (val s = state) {
        is Updater.State.Available -> AlertDialog(
            onDismissRequest = { Updater.dismiss() },
            containerColor = PismoColors.BgElevated,
            title = { Text("Новая версия ${s.release.tag}", color = PismoColors.TextPrimary) },
            text = {
                Column {
                    Text(
                        "Установлена ${Updater.currentVersion}.",
                        color = PismoColors.TextSecondary, fontSize = 13.sp,
                    )
                    if (s.release.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            // Длинное описание релиза в диалог не влезает и
                            // выталкивает кнопки за экран.
                            s.release.notes.take(400),
                            color = PismoColors.TextMuted, fontSize = 12.sp,
                        )
                    }
                    if (s.release.sizeBytes > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Скачать ${s.release.sizeBytes / 1024 / 1024} МБ",
                            color = PismoColors.TextMuted, fontSize = 12.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { Updater.startDownload(s.release) }) {
                    Text("Обновить", color = PismoColors.Blurple)
                }
            },
            dismissButton = {
                TextButton(onClick = { Updater.dismiss() }) {
                    Text("Позже", color = PismoColors.TextMuted)
                }
            },
        )

        is Updater.State.Downloading -> AlertDialog(
            // Закрыть по нажатию мимо нельзя: закачка продолжается, и
            // исчезнувшее окно выглядело бы как «отменилось».
            onDismissRequest = {},
            containerColor = PismoColors.BgElevated,
            title = { Text("Скачиваю ${s.release.tag}", color = PismoColors.TextPrimary) },
            text = {
                Column {
                    if (s.percent >= 0) {
                        LinearProgressIndicator(
                            progress = { s.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = PismoColors.Blurple,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("${s.percent}%", color = PismoColors.TextMuted, fontSize = 12.sp)
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = PismoColors.Blurple,
                        )
                    }
                }
            },
            confirmButton = {},
        )

        is Updater.State.Ready -> AlertDialog(
            onDismissRequest = { Updater.dismiss() },
            containerColor = PismoColors.BgElevated,
            title = { Text("Готово к установке", color = PismoColors.TextPrimary) },
            text = {
                Text(
                    if (Updater.canInstall(context))
                        "Сейчас откроется системный установщик. Приложение " +
                                "закроется и запустится уже обновлённым."
                    else
                        "Android спросит разрешение ставить приложения из этого " +
                                "источника — это разрешение выдаётся каждому " +
                                "приложению отдельно. Выдайте его и нажмите " +
                                "«Установить» ещё раз.",
                    color = PismoColors.TextSecondary, fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { Updater.install(context, s.file) }) {
                    Text("Установить", color = PismoColors.Blurple)
                }
            },
            dismissButton = {
                TextButton(onClick = { Updater.dismiss() }) {
                    Text("Позже", color = PismoColors.TextMuted)
                }
            },
        )

        is Updater.State.Failed -> AlertDialog(
            onDismissRequest = { Updater.dismiss() },
            containerColor = PismoColors.BgElevated,
            title = { Text("Обновление не вышло", color = PismoColors.TextPrimary) },
            text = {
                Text(
                    s.message + "\n\nAPK всегда можно скачать вручную со страницы " +
                            "релизов на GitHub.",
                    color = PismoColors.TextSecondary, fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { Updater.dismiss() }) {
                    Text("Закрыть", color = PismoColors.Blurple)
                }
            },
        )

        Updater.State.UpToDate -> AlertDialog(
            onDismissRequest = { Updater.dismiss() },
            containerColor = PismoColors.BgElevated,
            title = { Text("Обновлений нет", color = PismoColors.TextPrimary) },
            text = {
                Text(
                    "Установлена версия ${Updater.currentVersion} — она самая свежая.",
                    color = PismoColors.TextSecondary, fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { Updater.dismiss() }) {
                    Text("Хорошо", color = PismoColors.Blurple)
                }
            },
        )

        // Idle и Checking молчат: проверка занимает доли секунды, и
        // мигающее окно «проверяю…» раздражает сильнее, чем помогает.
        else -> Unit
    }
}
