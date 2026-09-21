package com.pismo.messenger.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.pismo.messenger.core.MediaKinds
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.ReplyQuote
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.PinsRepository
import com.pismo.messenger.desktop.Media
import com.pismo.messenger.desktop.PismoPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PAGE = 40

/**
 * Переписка — и личная, и групповая: различия собраны в ChatTarget.
 *
 * Страница вверх идёт ПО КУРСОРУ и дописывается в начало, как на телефоне:
 * лента не пересобирается, и LazyColumn сам удерживает то сообщение, на
 * которое человек смотрит.
 */
@Composable
fun ChatPane(target: ChatTarget) {

    val key = target.id to target.isGroup
    val messages = remember(key) { mutableStateListOf<ChatMessage>() }
    var pinned by remember(key) { mutableStateOf<Set<Int>>(emptySet()) }
    var draft by remember(key) { mutableStateOf("") }
    var replyTo by remember(key) { mutableStateOf<ChatMessage?>(null) }
    var loading by remember(key) { mutableStateOf(true) }
    var loadingOlder by remember(key) { mutableStateOf(false) }
    var hasMore by remember(key) { mutableStateOf(true) }
    var sending by remember(key) { mutableStateOf(false) }
    var progress by remember(key) { mutableStateOf(-1f) }
    val listState = rememberLazyListState()
    val ui = rememberCoroutineScope()

    suspend fun page(beforeId: Int = 0): List<ChatMessage> = withContext(Dispatchers.IO) {
        runCatching {
            if (target.isGroup) ChatRepository.loadGroupMessages(target.id, PAGE, beforeId)
            else ChatRepository.loadDirectMessages(target.id, PAGE, beforeId)
        }.getOrDefault(emptyList())
    }

    suspend fun readPins(): Set<Int> = withContext(Dispatchers.IO) {
        runCatching { PinsRepository.pinnedIds(target.scope) }.getOrDefault(emptySet())
    }

    // Первая страница и обновление ленты.
    LaunchedEffect(key) {
        val first = page()
        messages.clear(); messages.addAll(first)
        hasMore = first.size >= PAGE
        pinned = readPins()
        loading = false
        withContext(Dispatchers.IO) { runCatching { ChatRepository.prefetchPageMedia(first, target.scope) } }
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
        if (!target.isGroup) withContext(Dispatchers.IO) {
            runCatching { ChatRepository.markAsRead(target.id) }
        }

        var lastPins: String? = null
        while (isActive) {
            delay(2_500)
            val fresh = page()
            if (fresh.isNotEmpty()) {
                val newest = messages.lastOrNull()?.id ?: 0
                val tail = fresh.filter { it.id > newest }
                if (tail.isNotEmpty()) {
                    val atBottom = listState.firstVisibleItemIndex + 3 >= messages.lastIndex
                    messages.addAll(tail)
                    withContext(Dispatchers.IO) {
                        runCatching { ChatRepository.prefetchPageMedia(tail, target.scope) }
                        if (!target.isGroup) runCatching { ChatRepository.markAsRead(target.id) }
                    }
                    if (atBottom) listState.animateScrollToItem(messages.lastIndex)
                }
            }
            // Закрепы отдельно: они в таблице сообщений не лежат, и по ленте
            // их изменения не видно.
            val fp = withContext(Dispatchers.IO) {
                runCatching { PinsRepository.fingerprint() }.getOrDefault("")
            }
            if (fp.isNotEmpty() && lastPins != null && fp != lastPins) pinned = readPins()
            if (fp.isNotEmpty()) lastPins = fp
        }
    }

    // Догрузка вверх — потоком, а не ключами эффекта: с ключами эффект
    // перезапускался бы от собственного же флага и отменял свой запрос.
    LaunchedEffect(key) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            if (idx > 3 || !hasMore || loading || loadingOlder) return@collect
            val oldest = messages.firstOrNull()?.id ?: return@collect
            loadingOlder = true
            val older = page(oldest)
            hasMore = older.size >= PAGE
            if (older.isNotEmpty()) {
                messages.addAll(0, older)
                withContext(Dispatchers.IO) {
                    runCatching { ChatRepository.prefetchPageMedia(older, target.scope) }
                }
            }
            loadingOlder = false
        }
    }

    fun refreshTail() {
        ui.launch {
            val fresh = page()
            val newest = messages.lastOrNull()?.id ?: 0
            messages.addAll(fresh.filter { it.id > newest })
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun sendText() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        sending = true
        draft = ""
        val reply = replyTo?.id ?: 0
        replyTo = null
        ui.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    ChatRepository.sendMessage(target.scope, target.id, text, replyToId = reply)
                }
            }
            sending = false
            refreshTail()
        }
    }

    fun sendFile(file: File) {
        if (sending) return
        sending = true
        progress = 0f
        val reply = replyTo?.id ?: 0
        replyTo = null
        ui.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = file.readBytes()
                    val name = file.name
                    // Картинку шлём картинкой, чтобы она встала прямо в ленту,
                    // как с телефона; всё прочее — вложением.
                    val isImage = MediaKinds.extOf(name) in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
                    ChatRepository.sendMessage(
                        target.scope, target.id, "",
                        image = if (isImage) bytes else null,
                        file = if (isImage) null else bytes,
                        fileName = name,
                        replyToId = reply,
                        onProgress = { p -> progress = p },
                    )
                }
            }
            sending = false
            progress = -1f
            refreshTail()
        }
    }

    Column(Modifier.fillMaxSize()) {

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(target.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (target.isGroup) {
                Spacer(Modifier.width(8.dp))
                Text("группа", fontSize = 11.sp, color = PismoPalette.OnMuted)
            }
            Spacer(Modifier.weight(1f))
            if (loadingOlder) Text("загружаю более ранние…", fontSize = 12.sp, color = PismoPalette.OnMuted)
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
                    items(count = messages.size, key = { messages[it].id }) { i ->
                        val m = messages[i]
                        val prev = if (i == 0) null else messages[i - 1]
                        val day = dayOf(m.createdAtMs)
                        if (prev == null || dayOf(prev.createdAtMs) != day) DaySeparator(day)
                        Bubble(
                            m = m,
                            target = target,
                            isPinned = m.id in pinned,
                            onReply = { replyTo = m },
                            onTogglePin = {
                                ui.launch {
                                    withContext(Dispatchers.IO) {
                                        runCatching { PinsRepository.toggle(m.id, target.scope) }
                                    }
                                    pinned = readPins()
                                }
                            },
                        )
                    }
                }
            }
        }

        if (progress in 0f..1f) LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )

        replyTo?.let { r ->
            Divider(color = PismoPalette.Divider)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Reply, null, tint = PismoPalette.OnMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.senderName.ifBlank { "Сообщение" }, fontSize = 11.sp,
                         color = MaterialTheme.colorScheme.primary)
                    Text(bodyOf(r), fontSize = 12.sp, maxLines = 1, color = PismoPalette.OnMuted)
                }
                IconButton(onClick = { replyTo = null }, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.Default.Close, "Отменить ответ", tint = PismoPalette.OnMuted,
                         modifier = Modifier.size(15.dp))
                }
            }
        }

        Divider(color = PismoPalette.Divider)
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { FilePick.choose()?.let { sendFile(it) } }, enabled = !sending) {
                Icon(Icons.Default.AttachFile, "Файл",
                     tint = if (sending) PismoPalette.OnMuted else MaterialTheme.colorScheme.primary)
            }
            TextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение") },
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendText() }),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(10.dp),
            )
            IconButton(onClick = { sendText() }, enabled = draft.isNotBlank() && !sending) {
                Icon(Icons.Default.Send, "Отправить",
                     tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else PismoPalette.OnMuted)
            }
        }
    }
}

@Composable
private fun DaySeparator(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
        Text(text, fontSize = 11.sp, color = PismoPalette.OnMuted)
    }
}

@Composable
private fun Bubble(
    m: ChatMessage,
    target: ChatTarget,
    isPinned: Boolean,
    onReply: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val mine = m.isMine
    var quote by remember(m.id) { mutableStateOf<ReplyQuote?>(null) }
    var hovered by remember(m.id) { mutableStateOf(false) }

    LaunchedEffect(m.id) {
        if (m.replyToId > 0 && !m.isDeleted) {
            quote = withContext(Dispatchers.IO) {
                runCatching { ChatRepository.loadReplyQuote(m.replyToId, target.scope) }.getOrNull()
            }
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mine) BubbleActions(onReply, onTogglePin, isPinned)
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .background(if (mine) PismoPalette.Accent else PismoPalette.Bubble, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!mine) {
                Text(
                    m.senderName.ifBlank { "Пользователь #${m.senderId}" },
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = avatarColor(m.senderId),
                )
                Spacer(Modifier.height(2.dp))
            }

            quote?.let { q ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x22000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(q.sender, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(q.text, fontSize = 11.sp, maxLines = 2, color = Color(0xCCFFFFFF))
                }
                Spacer(Modifier.height(4.dp))
            }

            val body = bodyOf(m)
            if (body.isNotBlank()) Text(body, fontSize = 14.sp)
            if (!m.isDeleted) Attachment(m, target.scope, mine)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                if (isPinned) {
                    Icon(Icons.Default.PushPin, "Закреплено",
                         tint = if (mine) Color(0xCCFFFFFF) else PismoPalette.OnMuted,
                         modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    (if (m.isEdited) "изм. " else "") + hhmmOf(m.createdAtMs),
                    fontSize = 10.sp,
                    color = if (mine) Color(0xCCFFFFFF) else PismoPalette.OnMuted,
                )
            }
        }
        if (!mine) BubbleActions(onReply, onTogglePin, isPinned)
    }
}

@Composable
private fun BubbleActions(onReply: () -> Unit, onTogglePin: () -> Unit, isPinned: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onReply, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Default.Reply, "Ответить", tint = PismoPalette.OnMuted, modifier = Modifier.size(14.dp))
        }
        IconButton(onClick = onTogglePin, modifier = Modifier.size(26.dp)) {
            Icon(
                Icons.Default.PushPin,
                if (isPinned) "Открепить" else "Закрепить",
                tint = if (isPinned) MaterialTheme.colorScheme.primary else PismoPalette.Divider,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Текст сообщения: в базе он зашифрован тем же кодом, что на телефоне и ПК. */
private fun bodyOf(m: ChatMessage): String =
    if (m.isDeleted) "сообщение удалено"
    else runCatching { Crypto.dec(m.text) }.getOrDefault(m.text)


private val hhmmFmt = SimpleDateFormat("HH:mm", Locale("ru"))
private val dayFmt = SimpleDateFormat("d MMMM yyyy", Locale("ru"))

private fun hhmmOf(ms: Long) = hhmmFmt.format(Date(ms))
private fun dayOf(ms: Long) = dayFmt.format(Date(ms))
