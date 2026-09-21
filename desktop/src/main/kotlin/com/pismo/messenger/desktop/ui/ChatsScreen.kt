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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.model.Conversation
import com.pismo.messenger.data.model.GroupSummary
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
 * Главное окно: слева список — группы и личные диалоги, как на телефоне, —
 * справа переписка. Данные целиком из общих репозиториев; здесь только
 * раскладка и ввод.
 */
@Composable
fun ChatsScreen(onLogout: () -> Unit) {

    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var groups by remember { mutableStateOf<List<GroupSummary>>(emptyList()) }
    var pins by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selected by remember { mutableStateOf<ChatTarget?>(null) }
    var filter by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val ui = rememberCoroutineScope()

    suspend fun reload() {
        withContext(Dispatchers.IO) {
            runCatching { ChatRepository.loadConversations() }.getOrNull()?.let { conversations = it }
            runCatching { ChatRepository.loadGroups() }.getOrNull()?.let { groups = it }
            runCatching { ChatRepository.loadChatPins() }.getOrNull()?.let { pins = it }
        }
        loading = false
    }

    // Список обновляем сам: своего бэкенда нет, о новом никто не сообщает,
    // узнать можно только спросив. Закрепы входят в ту же проверку — их
    // меняют и на другом устройстве.
    LaunchedEffect(Unit) {
        reload()
        while (isActive) {
            delay(2_500)
            reload()
        }
    }

    val f = filter.trim().lowercase()
    val shownGroups = groups.filter { f.isEmpty() || it.name.lowercase().contains(f) }
    val shownChats = conversations
        .filter { f.isEmpty() || it.name.lowercase().contains(f) || it.login.lowercase().contains(f) }
        // Закреплённые — наверх. Закрепы лежат в базе и общие с телефоном и ПК.
        .sortedWith(
            compareByDescending<Conversation> { it.userId in pins }
                .thenByDescending { it.lastTimeMs ?: 0L }
        )

    Row(Modifier.fillMaxSize()) {

        Column(Modifier.width(300.dp).fillMaxHeight().background(PismoPalette.Surface)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    UserSession.effectiveName.ifBlank { "PISMO" },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { ui.launch { reload() } }) {
                    Icon(Icons.Default.Refresh, "Обновить")
                }
                IconButton(onClick = onLogout) { Icon(Icons.Default.Logout, "Выйти") }
            }
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Поиск", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(8.dp))

            if (loading && conversations.isEmpty() && groups.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                if (shownGroups.isNotEmpty()) {
                    item("hdr-groups") { SectionHeader("ГРУППЫ") }
                    items(count = shownGroups.size, key = { "g" + shownGroups[it].id }) { i ->
                        val g = shownGroups[i]
                        ListRow(
                            title = g.name,
                            preview = g.lastMessage,
                            timeMs = g.lastTimeMs,
                            unread = g.unread,
                            colorSeed = g.id,
                            isGroup = true,
                            pinned = false,
                            active = selected?.isGroup == true && selected?.id == g.id,
                            onClick = { selected = ChatTarget(g.id, g.name, isGroup = true) },
                            onTogglePin = null,
                        )
                    }
                    item("hdr-chats") { SectionHeader("ЛИЧНЫЕ СООБЩЕНИЯ") }
                }
                items(count = shownChats.size, key = { "d" + shownChats[it].userId }) { i ->
                    val c = shownChats[i]
                    ListRow(
                        title = c.name,
                        preview = c.lastMessage,
                        timeMs = c.lastTimeMs,
                        unread = c.unread,
                        colorSeed = c.userId,
                        isGroup = false,
                        pinned = c.userId in pins,
                        active = selected?.isGroup == false && selected?.id == c.userId,
                        onClick = {
                            selected = ChatTarget(c.userId, c.name, isGroup = false)
                            ui.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { ChatRepository.markAsRead(c.userId) }
                                }
                            }
                        },
                        onTogglePin = {
                            val now = c.userId !in pins
                            pins = if (now) pins + c.userId else pins - c.userId
                            ui.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { ChatRepository.setChatPin(c.userId, now) }
                                }
                            }
                        },
                    )
                }
            }
        }

        Divider(Modifier.fillMaxHeight().width(1.dp), color = PismoPalette.Divider)

        Box(Modifier.weight(1f).fillMaxHeight().background(PismoPalette.Background)) {
            val t = selected
            if (t == null) {
                Text("Выберите диалог", color = PismoPalette.OnMuted, modifier = Modifier.align(Alignment.Center))
            } else {
                ChatPane(t)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        color = PismoPalette.OnMuted,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun ListRow(
    title: String,
    preview: String,
    timeMs: Long?,
    unread: Int,
    colorSeed: Int,
    isGroup: Boolean,
    pinned: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    onTogglePin: (() -> Unit)?,
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
            Modifier.size(36.dp).clip(CircleShape).background(avatarColor(colorSeed)),
            contentAlignment = Alignment.Center,
        ) {
            if (isGroup) Icon(Icons.Default.Groups, null, tint = Color.White, modifier = Modifier.size(18.dp))
            else Text(
                title.trim().take(1).uppercase().ifEmpty { "?" },
                color = Color.White, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    Icon(Icons.Default.PushPin, null, tint = PismoPalette.OnMuted,
                         modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(title, maxLines = 1,
                     fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal)
            }
            Text(preview, maxLines = 1, fontSize = 12.sp, color = PismoPalette.OnMuted)
        }
        Column(horizontalAlignment = Alignment.End) {
            timeMs?.let { Text(shortTime(it), fontSize = 11.sp, color = PismoPalette.OnMuted) }
            if (unread > 0) {
                Spacer(Modifier.height(2.dp))
                Badge { Text(unread.coerceAtMost(99).toString()) }
            }
        }
        if (onTogglePin != null) {
            IconButton(onClick = onTogglePin, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.PushPin,
                    if (pinned) "Открепить" else "Закрепить",
                    tint = if (pinned) MaterialTheme.colorScheme.primary else PismoPalette.Divider,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** Цвет кружка — стабильный для собеседника, как на телефоне. */
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
