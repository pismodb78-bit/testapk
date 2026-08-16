package com.pismo.messenger.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.media.VideoCircleCodec
import com.pismo.messenger.media.WavPlayer
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
    var decoded by remember(messageId) { mutableStateOf<VideoCircleCodec.DecodedVideo?>(null) }
    var loading by remember(messageId) { mutableStateOf(false) }
    var playing by remember(messageId) { mutableStateOf(false) }
    var frameIndex by remember(messageId) { mutableIntStateOf(0) }

    DisposableEffect(messageId) {
        onDispose {
            decoded?.frames?.forEach { runCatching { it.recycle() } }
            if (WavPlayer.playingId == messageId) WavPlayer.stop()
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
                if (WavPlayer.playingId == messageId) WavPlayer.stop()
            }
        }
    }

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
                    if (!playing && WavPlayer.playingId == messageId) WavPlayer.stop()
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

    LaunchedEffect(loading) {
        if (!loading || decoded != null) return@LaunchedEffect
        val bytes = runCatching { ChatRepository.loadVideoCircle(messageId, scopeKind) }.getOrNull()
        decoded = bytes?.let { runCatching { VideoCircleCodec.decode(it) }.getOrNull() }
        loading = false
        if (decoded != null) playing = true
    }
}
