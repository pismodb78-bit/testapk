package com.pismo.messenger.ui.call

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.call.ActiveCall
import com.pismo.messenger.core.formatDuration
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Полоска активного звонка над нижней навигацией — порт MainForm_VoiceDock.cs.
 *
 * На ПК док висит в углу и позволяет вернуться в окно звонка, не теряя
 * его из виду. Здесь то же самое: пока идёт звонок, по любому разделу
 * приложения видно, где идёт разговор, и одним тапом можно вернуться.
 */
@Composable
fun VoiceDock() {
    val context = LocalContext.current
    val call by ActiveCall.current.collectAsState()
    val micMuted by ActiveCall.micMuted.collectAsState()
    val deafened by ActiveCall.deafened.collectAsState()
    val connected by ActiveCall.connected.collectAsState()

    var elapsed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(call?.startedAtMs) {
        while (isActive && call != null) {
            elapsed = call?.elapsedSeconds ?: 0L
            delay(1000)
        }
    }

    AnimatedVisibility(
        visible = call != null,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        val info = call ?: return@AnimatedVisibility

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (connected) PismoColors.Green.copy(alpha = 0.18f) else PismoColors.BgElevated)
                .clickable { reopenCall(context, info) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.VolumeUp,
                contentDescription = null,
                tint = if (connected) PismoColors.Green else PismoColors.TextMuted,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                // Верхняя строка — ГДЕ идёт разговор, как subtitle дока на ПК
                // («с кем/где звонок»). Раньше здесь было «В эфире», и это
                // сбивало: тем же словом подписана демонстрация экрана, а
                // куда именно вернёт кнопка, понять было нельзя.
                Text(
                    when {
                        info.isVoiceChannel -> "Голосовой канал · ${info.title.ifBlank { "канал" }}"
                        info.groupId > 0 -> "Групповой звонок · ${info.title.ifBlank { "группа" }}"
                        else -> "Звонок · ${info.title.ifBlank { "собеседник" }}"
                    },
                    color = PismoColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (connected) "Голосовая связь подключена · ${formatDuration(elapsed)}"
                    else "Подключение…",
                    color = if (connected) PismoColors.Green else PismoColors.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Микрофон, «наушники» и трубка прямо в доке — порт кнопок
            // футера с ПК (ToggleMicGlobal/ToggleDeafenGlobal). Смысл именно
            // в том, чтобы не открывать окно звонка ради одного щелчка: с
            // телефона мьютятся обычно на ходу, посреди переписки.
            DockButton(
                icon = if (micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                description = if (micMuted) "Включить микрофон" else "Выключить микрофон",
                tint = if (micMuted) PismoColors.Red else PismoColors.TextSecondary,
                onClick = { ActiveCall.toggleMic() },
            )

            DockButton(
                icon = if (deafened) Icons.Default.HeadsetOff else Icons.Default.Headset,
                description = if (deafened) "Включить звук" else "Выключить звук",
                tint = if (deafened) PismoColors.Red else PismoColors.TextSecondary,
                onClick = { ActiveCall.toggleDeafen() },
            )

            // Трубка кладёт трубку. Раньше этой же красной трубкой открывался
            // звонок — иконка обещала одно, делала другое. Открыть окно можно
            // тапом по самой полоске.
            DockButton(
                icon = Icons.Default.CallEnd,
                description = "Завершить звонок",
                tint = PismoColors.Red,
                onClick = { ActiveCall.hangUp() },
            )
        }
    }
}

/**
 * Кнопка в доке: касание не должно проваливаться в полоску под ней —
 * иначе мьют микрофона попутно открывал бы окно звонка.
 */
@Composable
private fun DockButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(19.dp))
    }
}

/**
 * Возврат в окно звонка. CallActivity объявлена singleTop, поэтому
 * повторный запуск не создаёт вторую комнату — просто поднимает
 * существующий экран.
 */
private fun reopenCall(context: android.content.Context, info: ActiveCall.Info) {
    val intent = Intent(context, CallActivity::class.java).apply {
        putExtra(CallActivity.EXTRA_SESSION_ID, info.sessionId)
        putExtra(CallActivity.EXTRA_CHANNEL_ID, info.channelId)
        putExtra(CallActivity.EXTRA_PEER_ID, info.peerId)
        putExtra(CallActivity.EXTRA_GROUP_ID, info.groupId)
        putExtra(CallActivity.EXTRA_PEER_NAME, info.title)
        putExtra(CallActivity.EXTRA_WITH_VIDEO, info.withVideo)
        putExtra(CallActivity.EXTRA_IS_CALLER, false)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    context.startActivity(intent)
}
