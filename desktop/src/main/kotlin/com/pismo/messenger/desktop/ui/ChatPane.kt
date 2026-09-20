package com.pismo.messenger.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.Crypto
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.Conversation
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.desktop.PismoPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Переписка с одним человеком.
 *
 * Страница вверх устроена так же, как на телефоне: следующая порция берётся
 * ПО КУРСОРУ (`beforeId`) и дописывается в начало списка, а не
 * перечитывается вся лента заново. LazyColumn при этом сам удерживает
 * положение — элементы выше просто появляются, а тот, на который человек
 * смотрит, остаётся на месте.
 */
@Composable
fun ChatPane(partner: Conversation) {

    val messages = remember(partner.userId) { mutableStateListOf<ChatMessage>() }
    var draft by remember(partner.userId) { mutableStateOf("") }
    var loading by remember(partner.userId) { mutableStateOf(true) }
    var loadingOlder by remember(partner.userId) { mutableStateOf(false) }
    var hasMore by remember(partner.userId) { mutableStateOf(true) }
    var sending by remember(partner.userId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Первая страница и обновление ленты.
    LaunchedEffect(partner.userId) {
        val first = withContext(Dispatchers.IO) {
            runCatching { ChatRepository.loadDirectMessages(partner.userId) }.getOrNull()
        }
        if (first != null) {
            messages.clear(); messages.addAll(first)
            hasMore = first.size >= PAGE
        }
        loading = false
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)

        while (isActive) {
            delay(2_500)
            val newest = messages.lastOrNull()?.id ?: 0
            val fresh = withContext(Dispatchers.IO) {
                runCatching { ChatRepository.loadDirectMessages(partner.userId) }.getOrNull()
            } ?: continue
            // Дописываем только то, чего ещё нет: полная замена списка
            // дёргала бы прокрутку на каждом обновлении.
            val tail = fresh.filter { it.id > newest }
            if (tail.isNotEmpty()) {
                val atBottom = listState.firstVisibleItemIndex + 3 >= messages.lastIndex
                messages.addAll(tail)
                if (atBottom) listState.animateScrollToItem(messages.lastIndex)
                withContext(Dispatchers.IO) {
                    runCatching { ChatRepository.markAsRead(partner.userId) }
                }
            }
        }
    }

    // Догрузка вверх — когда до начала списка остаётся меньше экрана.
    //
    // Слушаем прокрутку потоком, а не ключами LaunchedEffect. С ключами
    // получалось так: эффект сам поднимает флаг «гружу», флаг входит в
    // ключи — и эффект перезапускается, отменяя собственный незаконченный
    // запрос. Поток же привязан только к собеседнику и живёт, пока открыт
    // чат; `collect` при этом обрабатывает сдвиги по одному, так что
    // вторая порция не уйдёт, пока не приедет первая.
    LaunchedEffect(partner.userId) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            if (idx > 3 || !hasMore || loading || loadingOlder) return@collect
            val oldest = messages.firstOrNull()?.id ?: return@collect
            loadingOlder = true
            val older = withContext(Dispatchers.IO) {
                runCatching {
                    ChatRepository.loadDirectMessages(partner.userId, beforeId = oldest)
                }.getOrNull()
            }
            if (older != null) {
                hasMore = older.size >= PAGE
                // Дописываем В НАЧАЛО — лента не пересобирается, и LazyColumn
                // сам удерживает то сообщение, на которое человек смотрит.
                if (older.isNotEmpty()) messages.addAll(0, older)
            }
            loadingOlder = false
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        sending = true
        draft = ""
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ChatRepository.sendMessage(Scope.DM, partner.userId, text) }
            }
            val fresh = withContext(Dispatchers.IO) {
                runCatching { ChatRepository.loadDirectMessages(partner.userId) }.getOrNull()
            }
            if (fresh != null) {
                val newest = messages.lastOrNull()?.id ?: 0
                messages.addAll(fresh.filter { it.id > newest })
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
            }
            sending = false
        }
    }

    Column(Modifier.fillMaxSize()) {

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(partner.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            if (loadingOlder) {
                Text("загружаю более ранние…", fontSize = 12.sp, color = PismoPalette.OnMuted)
            }
        }
        Divider(color = PismoPalette.Divider)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexedStable(messages) { prev, m ->
                        val day = dayOf(m.createdAtMs)
                        if (prev == null || dayOf(prev.createdAtMs) != day) DaySeparator(day)
                        Bubble(m)
                    }
                }
            }
        }

        Divider(color = PismoPalette.Divider)
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { /* вложения — следующим шагом */ }, enabled = false) {
                Icon(Icons.Default.AttachFile, contentDescription = "Файл", tint = PismoPalette.OnMuted)
            }
            TextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение") },
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(10.dp),
            )
            IconButton(onClick = { send() }, enabled = draft.isNotBlank() && !sending) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Отправить",
                    tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else PismoPalette.OnMuted,
                )
            }
        }
    }
}

private const val PAGE = 40

/**
 * Обёртка над items, которая отдаёт и предыдущий элемент: разделитель дат
 * ставится там, где день сменился, и без соседа этого не узнать.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedStable(
    list: List<ChatMessage>,
    row: @Composable (prev: ChatMessage?, m: ChatMessage) -> Unit,
) {
    items(count = list.size, key = { list[it].id }) { i ->
        row(if (i == 0) null else list[i - 1], list[i])
    }
}

@Composable
private fun DaySeparator(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, fontSize = 11.sp, color = PismoPalette.OnMuted)
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val mine = m.isMine
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .background(
                    if (mine) PismoPalette.Accent else PismoPalette.Bubble,
                    RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!mine) {
                Text(
                    m.senderName.ifBlank { "Пользователь #${m.senderId}" },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = avatarColor(m.senderId),
                )
                Spacer(Modifier.height(2.dp))
            }

            val body = when {
                m.isDeleted -> "сообщение удалено"
                // Текст в базе зашифрован — расшифровка та же, что на
                // телефоне и на ПК, иначе клиенты читали бы разное.
                else -> runCatching { Crypto.dec(m.text) }.getOrDefault(m.text)
            }
            if (body.isNotBlank()) Text(body, fontSize = 14.sp)

            if (m.hasAnyMedia) {
                Spacer(Modifier.height(4.dp))
                Text(
                    attachmentLabel(m),
                    fontSize = 12.sp,
                    color = if (mine) Color(0xCCFFFFFF) else PismoPalette.OnMuted,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    (if (m.isEdited) "изм. " else "") + hhmmOf(m.createdAtMs),
                    fontSize = 10.sp,
                    color = if (mine) Color(0xCCFFFFFF) else PismoPalette.OnMuted,
                )
            }
        }
    }
}

private fun attachmentLabel(m: ChatMessage): String = when {
    m.hasImage -> "🖼 изображение"
    m.hasAudio -> "🎤 голосовое"
    m.hasVideo -> "⏺ видеокружок"
    m.hasFile -> "📎 " + (m.fileName ?: "файл")
    else -> ""
}

private val hhmmFmt = SimpleDateFormat("HH:mm", Locale("ru"))
private val dayFmt = SimpleDateFormat("d MMMM yyyy", Locale("ru"))

private fun hhmmOf(ms: Long) = hhmmFmt.format(Date(ms))
private fun dayOf(ms: Long) = dayFmt.format(Date(ms))
