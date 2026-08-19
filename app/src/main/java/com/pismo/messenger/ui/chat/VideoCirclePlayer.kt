package com.pismo.messenger.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.media.CircleExporter
import com.pismo.messenger.media.MediaSaver
import com.pismo.messenger.media.VideoCircleCodec
import com.pismo.messenger.media.WavPlayer
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Воспроизведение видео-кружочка — порт VideoCirclePlayer.cs.
 *
 * Контейнер PSMOVID1 не является настоящим видеоформатом, поэтому системный
 * плеер его не откроет: кадры перелистываются вручную по таймеру с частотой
 * из заголовка, а звуковая дорожка (обычный WAV) играет параллельно.
 */
@Composable
fun VideoCirclePlayerBubble(
    messageId: Int,
    scopeKind: Scope,
    size: Int = 180,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var decoded by remember(messageId) { mutableStateOf<VideoCircleCodec.DecodedVideo?>(null) }
    var loading by remember(messageId) { mutableStateOf(false) }
    var playing by remember(messageId) { mutableStateOf(false) }
    var frameIndex by remember(messageId) { mutableIntStateOf(0) }
    var saveStatus by remember(messageId) { mutableStateOf("") }
    var saving by remember(messageId) { mutableStateOf(false) }

    DisposableEffect(messageId) {
        onDispose {
            decoded?.frames?.forEach { runCatching { it.recycle() } }
            if (WavPlayer.currentId == messageId) WavPlayer.stop()
        }
    }

    LaunchedEffect(playing, decoded) {
        val video = decoded ?: return@LaunchedEffect
        if (!playing || video.frames.isEmpty()) return@LaunchedEffect

        if (video.audioWav.isNotEmpty()) WavPlayer.toggle(messageId, video.audioWav)

        val delayMs = (1000L / video.fps.coerceAtLeast(1))
        while (isActive && playing) {
            delay(delayMs)
            frameIndex = (frameIndex + 1) % video.frames.size
            if (frameIndex == 0) {
                // Дошли до конца — останавливаемся, как это делает ПК.
                playing = false
                if (WavPlayer.currentId == messageId) WavPlayer.stop()
            }
        }
    }

    Column {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(PismoColors.BgDarkest)
                .clickable {
                    if (decoded == null && !loading) {
                        loading = true
                    } else if (decoded != null) {
                        playing = !playing
                        if (!playing && WavPlayer.currentId == messageId) WavPlayer.stop()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val video = decoded
            if (video != null && video.frames.isNotEmpty()) {
                val bitmap = video.frames[frameIndex.coerceIn(0, video.frames.lastIndex)]
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Видео-кружочек",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size.dp).clip(CircleShape),
                )
                if (!playing) {
                    Icon(
                        Icons.Default.PlayArrow, "Воспроизвести",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(48.dp),
                    )
                }
            } else if (loading) {
                CircularProgressIndicator(color = PismoColors.Blurple)
            } else {
                Icon(
                    Icons.Default.PlayArrow, "Загрузить кружочек",
                    tint = Color.White, modifier = Modifier.size(48.dp),
                )
            }
        }

        Row(
            Modifier.width(size.dp).padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    if (saving) return@IconButton
                    scope.launch {
                        saving = true
                        saveStatus = "Сохранение…"
                        val error = saveCircle(context, messageId, scopeKind, decoded)
                        saveStatus = error ?: "Сохранено в галерею"
                        saving = false
                    }
                },
                modifier = Modifier.size(28.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        color = PismoColors.TextSecondary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Сохранить кружочек в галерею",
                        tint = PismoColors.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Text(
                saveStatus.ifBlank { "Кружочек" },
                color = PismoColors.TextMuted,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }

    LaunchedEffect(loading) {
        if (!loading || decoded != null) return@LaunchedEffect
        val bytes = runCatching { ChatRepository.loadVideoCircle(messageId, scopeKind) }.getOrNull()
        decoded = bytes?.let { runCatching { VideoCircleCodec.decode(it) }.getOrNull() }
        loading = false
        if (decoded != null) playing = true
    }
}

/**
 * Сохраняет кружочек в галерею как обычный MP4.
 *
 * Класть на диск сам PSMOVID1 бессмысленно — его не откроет ничего, кроме
 * PISMO, — поэтому кадры и звук пересобираются системными кодеками. Возвращает
 * текст ошибки или null, если всё получилось.
 */
private suspend fun saveCircle(
    context: android.content.Context,
    messageId: Int,
    scopeKind: Scope,
    alreadyDecoded: VideoCircleCodec.DecodedVideo?,
): String? {
    // Уже раскодированный кружочек не перечитываем: его кадры лежат в памяти.
    val video = alreadyDecoded ?: run {
        val bytes = runCatching { ChatRepository.loadVideoCircle(messageId, scopeKind) }.getOrNull()
            ?: return "Не удалось загрузить"
        runCatching { VideoCircleCodec.decode(bytes) }.getOrNull()
            ?: return "Повреждённый кружочек"
    }

    val tmp = withContext(Dispatchers.IO) {
        File(context.cacheDir, "circle_$messageId.mp4").also { runCatching { it.delete() } }
    }
    if (!CircleExporter.toMp4(video, tmp)) return "Устройство не смогло собрать видео"

    val bytes = withContext(Dispatchers.IO) { runCatching { tmp.readBytes() }.getOrNull() }
        ?: return "Не удалось прочитать видео"
    val ok = MediaSaver.saveVideo(context, "pismo_circle_$messageId.mp4", bytes)
    withContext(Dispatchers.IO) { runCatching { tmp.delete() } }
    return if (ok) null else "Не удалось сохранить"
}
