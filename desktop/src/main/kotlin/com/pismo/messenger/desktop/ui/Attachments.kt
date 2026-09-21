package com.pismo.messenger.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.desktop.Media
import com.pismo.messenger.desktop.PismoPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Вложения в пузыре.
 *
 * Байты тянет общий ChatRepository — тот же, что на телефоне, — поэтому
 * здесь только показ: картинка сразу в ленте, голосовое кнопкой, файл
 * карточкой с сохранением в «Загрузки».
 */
@Composable
fun Attachment(m: ChatMessage, scope: Scope, mine: Boolean) {
    when {
        m.hasImage -> InlineImage(m, scope)
        m.hasAudio -> VoiceNote(m, scope, mine)
        // Видеокружок настольная версия пока не проигрывает: показываем
        // карточкой, по щелчку он уходит в системный проигрыватель.
        m.hasVideo -> FileCard(m, scope, mine, label = "Видеосообщение", videoCircle = true)
        m.hasFile -> FileCard(m, scope, mine, label = m.fileName ?: "Файл")
    }
}

@Composable
private fun InlineImage(m: ChatMessage, scope: Scope) {
    var bytes by remember(m.id) { mutableStateOf<ByteArray?>(null) }
    var failed by remember(m.id) { mutableStateOf(false) }

    LaunchedEffect(m.id) {
        val b = withContext(Dispatchers.IO) {
            runCatching { ChatRepository.loadImage(m.id, scope, m.fileName) }.getOrNull()
        }
        if (b == null) failed = true else bytes = b
    }

    val img = Media.decode(m.id, bytes)
    Box(
        Modifier.padding(top = 4.dp).widthIn(max = 420.dp).heightIn(min = 60.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            img != null -> Image(
                bitmap = img,
                contentDescription = m.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .heightIn(max = 380.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { Media.saveAndOpen(m.fileName ?: "image_${m.id}.jpg", bytes) },
            )
            failed -> Text("изображение не загрузилось", fontSize = 12.sp, color = PismoPalette.OnMuted)
            else -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun VoiceNote(m: ChatMessage, scope: Scope, mine: Boolean) {
    val ui = rememberCoroutineScope()
    var busy by remember(m.id) { mutableStateOf(false) }
    var playing by remember(m.id) { mutableStateOf(false) }
    var note by remember(m.id) { mutableStateOf("") }

    Row(
        Modifier
            .padding(top = 4.dp)
            .background(chipColor(mine), RoundedCornerShape(8.dp))
            .clickable(enabled = !busy) {
                if (playing) { Media.stopVoice(); playing = false; return@clickable }
                busy = true
                ui.launch {
                    val b = withContext(Dispatchers.IO) {
                        runCatching { ChatRepository.loadAudio(m.id, scope) }.getOrNull()
                    }
                    busy = false
                    playing = Media.playVoice(m.id, b) { playing = false }
                    if (!playing) note = if (b == null) "не загрузилось" else "формат не поддержан"
                }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        else Icon(
            if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = if (playing) "Остановить" else "Слушать",
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(if (note.isNotEmpty()) note else "Голосовое сообщение", fontSize = 13.sp)
    }
}

@Composable
private fun FileCard(
    m: ChatMessage,
    scope: Scope,
    mine: Boolean,
    label: String,
    videoCircle: Boolean = false,
) {
    val ui = rememberCoroutineScope()
    var state by remember(m.id) { mutableStateOf("") }
    var size by remember(m.id) { mutableStateOf(-1L) }

    LaunchedEffect(m.id) {
        size = withContext(Dispatchers.IO) {
            runCatching { ChatRepository.fileSize(m.id, scope) }.getOrDefault(-1L)
        }
    }

    Row(
        Modifier
            .padding(top = 4.dp)
            .widthIn(max = 360.dp)
            .background(chipColor(mine), RoundedCornerShape(8.dp))
            .clickable(enabled = state != "качаю…") {
                state = "качаю…"
                ui.launch {
                    val b = withContext(Dispatchers.IO) {
                        runCatching {
                            if (videoCircle) ChatRepository.loadVideoCircle(m.id, scope)
                            else ChatRepository.loadFile(m.id, scope, m.fileName)
                        }.getOrNull()
                    }
                    val name = m.fileName ?: if (videoCircle) "circle_${m.id}.mp4" else "file_${m.id}"
                    val saved = withContext(Dispatchers.IO) { Media.saveAndOpen(name, b) }
                    state = if (saved != null) "сохранено в ${saved.parentFile?.name}" else "не удалось"
                }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, fontSize = 13.sp, maxLines = 1)
            val sub = when {
                state.isNotEmpty() -> state
                size >= 0 -> Media.humanSize(size)
                else -> "щёлкните, чтобы открыть"
            }
            Text(sub, fontSize = 11.sp, color = if (mine) Color(0xCCFFFFFF) else PismoPalette.OnMuted)
        }
    }
}

private fun chipColor(mine: Boolean) =
    if (mine) Color(0x33FFFFFF) else Color(0x22FFFFFF)
