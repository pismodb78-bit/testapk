package com.pismo.messenger.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.model.Conversation
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
 * Главное окно: слева список диалогов, справа переписка.
 *
 * Данные целиком из общих репозиториев — тех же, что у телефона. Здесь
 * только раскладка и ввод.
 */
@Composable
fun ChatsScreen(onLogout: () -> Unit) {

    val conversations = remember { mutableStateListOf<Conversation>() }
    var pins by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selected by remember { mutableStateOf<Conversation?>(null) }
    var filter by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        val list = withContext(Dispatchers.IO) { runCatching { ChatRepository.loadConversations() }.getOrNull() }
        val p = withContext(Dispatchers.IO) { runCatching { ChatRepository.loadChatPins() }.getOrNull() }
        if (list != null) { conversations.clear(); conversations.addAll(list) }
        if (p != null) pins = p
        loading = false
    }

    // Список обновляем сам, как на телефоне: своего бэкенда нет, о новых
    // сообщениях никто не сообщает, узнать о них можно только спросив.
    LaunchedEffect(Unit) {
        refresh()
        while (isActive) {
            delay(5_000)
            refresh()
        }
    }

    val shown = remember(conversations.toList(), pins, filter) {
        val f = filter.trim().lowercase()
        conversations
            .filter { f.isEmpty() || it.name.lowercase().contains(f) || it.login.lowercase().contains(f) }
            // Закреплённые — наверх. Закрепления лежат в базе, поэтому
            // порядок здесь тот же, что на телефоне и на ПК.
            .sortedWith(
                compareByDescending<Conversation> { it.userId in pins }
                    .thenByDescending { it.lastTimeMs ?: 0L }
            )
    }

    Row(Modifier.fillMaxSize()) {

        // ── Список диалогов ──────────────────────────────────────────────
        Column(
            Modifier.width(300.dp).fillMaxHeight().background(PismoPalette.Surface)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    UserSession.effectiveName.ifBlank { "PISMO" },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onLogout) {
                    Icon(Icons.Default.Logout, contentDescription = "Выйти")
                }
            }
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Поиск", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(8.dp))

            if (loading && conversations.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.userId }) { c ->
                    ConversationRow(
                        c = c,
                        pinned = c.userId in pins,
                        active = selected?.userId == c.userId,
                        onClick = {
                            selected = c
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { ChatRepository.markAsRead(c.userId) }
                                }
                            }
                        },
                        onTogglePin = {
                            val nowPinned = c.userId !in pins
                            pins = if (nowPinned) pins + c.userId else pins - c.userId
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { ChatRepository.setChatPin(c.userId, nowPinned) }
                                }
                            }
                        },
                    )
                }
            }
        }

        Divider(
            Modifier.fillMaxHeight().width(1.dp),
            color = PismoPalette.Divider,
        )

        // ── Переписка ────────────────────────────────────────────────────
        Box(Modifier.weight(1f).fillMaxHeight().background(PismoPalette.Background)) {
            val partner = selected
            if (partner == null) {
                Text(
                    "Выберите диалог",
                    color = PismoPalette.OnMuted,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                ChatPane(partner)
            }
        }
    }
}

@Composable
private fun ConversationRow(
    c: Conversation,
    pinned: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (active) Color(0x22FFFFFF) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(avatarColor(c.userId)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                c.name.trim().take(1).uppercase().ifEmpty { "?" },
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = null,
                        tint = PismoPalette.OnMuted,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    c.name,
                    maxLines = 1,
                    fontWeight = if (c.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            Text(
                c.lastMessage,
                maxLines = 1,
                fontSize = 12.sp,
                color = PismoPalette.OnMuted,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            c.lastTimeMs?.let {
                Text(shortTime(it), fontSize = 11.sp, color = PismoPalette.OnMuted)
            }
            if (c.unread > 0) {
                Spacer(Modifier.height(2.dp))
                Badge { Text(c.unread.coerceAtMost(99).toString()) }
            }
        }
        IconButton(onClick = onTogglePin, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Default.PushPin,
                contentDescription = if (pinned) "Открепить" else "Закрепить",
                tint = if (pinned) MaterialTheme.colorScheme.primary else PismoPalette.Divider,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Цвет кружка с буквой — стабильный для человека, как на телефоне. */
internal fun avatarColor(id: Int): Color {
    val palette = listOf(
        Color(0xFF5865F2), Color(0xFF3BA55D), Color(0xFFFAA61A),
        Color(0xFFED4245), Color(0xFF9B59B6), Color(0xFF1ABC9C),
    )
    return palette[(id % palette.size + palette.size) % palette.size]
}

private val hhmm = SimpleDateFormat("HH:mm", Locale("ru"))
private val dayMonth = SimpleDateFormat("d MMM", Locale("ru"))

internal fun shortTime(ms: Long): String {
    val now = System.currentTimeMillis()
    return if (now - ms < 20 * 60 * 60 * 1000L) hhmm.format(Date(ms)) else dayMonth.format(Date(ms))
}
