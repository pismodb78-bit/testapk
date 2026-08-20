package com.pismo.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.media.WavPlayer
import com.pismo.messenger.ui.theme.PismoColors

/**
 * Полоса записанного, но ещё не отправленного голосового.
 *
 * Раньше остановка записи означала отправку: голосовое улетало собеседнику в
 * тот же миг, и услышать себя со стороны было негде. Теперь между записью и
 * отправкой есть шаг — вот эта полоса: послушать, перезаписать (старое
 * стирается, запись начинается сразу) или выбросить. Отправляет обычная
 * кнопка отправки, поэтому к голосовому можно ещё и подпись набрать.
 *
 * Та же полоса стоит и в личных чатах, и в каналах серверов, и такая же —
 * в ПК-версии (MainForm_VoiceNote), чтобы привычка работала везде одинаково.
 */
@Composable
fun VoiceDraftBar(
    wav: ByteArray,
    onRerecord: () -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playingId by WavPlayer.playingId.collectAsState()
    val positionMs by WavPlayer.positionMs.collectAsState()
    val durationMs by WavPlayer.durationMs.collectAsState()

    val playing = playingId == DRAFT_ID
    // Длительность считаем по самому WAV: у неотправленного сообщения нет ни
    // id, ни строки в базе, спросить её больше негде.
    val totalSeconds = remember(wav) { WavPlayer.durationSecondsOf(wav) }

    /**
     * Куда перемотали, пока запись НЕ играет.
     *
     * Проигрыватель умеет перематывать только то, что играет прямо сейчас, —
     * иначе перематывать просто нечего. Поэтому позицию, выбранную в покое,
     * держим здесь и применяем сразу после старта.
     */
    var pendingSeekMs by remember(wav) { mutableIntStateOf(0) }

    LaunchedEffect(playing, durationMs) {
        if (playing && durationMs > 0 && pendingSeekMs > 0) {
            WavPlayer.seekTo(DRAFT_ID, pendingSeekMs)
            pendingSeekMs = 0
        }
    }

    val totalMs = if (playing && durationMs > 0) durationMs else totalSeconds * 1000
    val shownMs = if (playing) positionMs else pendingSeekMs

    Column(
        modifier
            .fillMaxWidth()
            .background(PismoColors.BgDarkest)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { WavPlayer.toggle(DRAFT_ID, wav) }) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "Пауза" else "Прослушать",
                    tint = PismoColors.Blurple,
                )
            }

            Column(Modifier.weight(1f)) {
                Text(
                    "Голосовое ${mmss(shownMs / 1000)} / ${mmss(totalMs / 1000)}",
                    color = PismoColors.TextPrimary, fontSize = 13.sp,
                )
                Text(
                    "«Отправить» — отправить, ⟳ — записать заново",
                    color = PismoColors.TextMuted, fontSize = 11.sp,
                )
            }

            IconButton(onClick = { WavPlayer.stop(); onRerecord() }) {
                Icon(Icons.Default.Refresh, "Записать заново", tint = PismoColors.TextMuted)
            }
            IconButton(onClick = { WavPlayer.stop(); onDrop() }) {
                Icon(Icons.Default.Delete, "Удалить запись", tint = PismoColors.Red)
            }
        }

        // Ползунок перемотки. Показываем всегда, а не только во время
        // проигрывания: отмотать к нужному месту хочется и до нажатия
        // «играть» — тогда оттуда и начнётся.
        if (totalMs > 0) {
            Slider(
                value = shownMs.coerceIn(0, totalMs).toFloat(),
                onValueChange = { v ->
                    val ms = v.toInt().coerceIn(0, totalMs)
                    if (playing) WavPlayer.seekTo(DRAFT_ID, ms) else pendingSeekMs = ms
                },
                valueRange = 0f..totalMs.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = PismoColors.Blurple,
                    activeTrackColor = PismoColors.Blurple,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(24.dp),
            )
        }
    }
}

/**
 * Номер «сообщения» для проигрывателя. Настоящие id положительные, поэтому
 * отрицательный не столкнётся ни с одним из них — и обязан отличаться от
 * WavPlayer.NONE, которым помечено «ничего не играет».
 */
private const val DRAFT_ID = -999

private fun mmss(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
