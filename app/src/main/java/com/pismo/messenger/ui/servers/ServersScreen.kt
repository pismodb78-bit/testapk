package com.pismo.messenger.ui.servers

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.model.ChannelType
import com.pismo.messenger.data.model.ServerChannel
import com.pismo.messenger.data.model.ServerPermissions
import com.pismo.messenger.data.model.ServerSummary
import com.pismo.messenger.data.model.VoiceParticipant
import com.pismo.messenger.data.repo.PresenceRepository
import com.pismo.messenger.data.repo.ServerRepository
import com.pismo.messenger.ui.components.LetterAvatar
import com.pismo.messenger.ui.components.UserAvatar
import com.pismo.messenger.ui.components.Pill
import com.pismo.messenger.ui.components.UnreadBadge
import com.pismo.messenger.ui.login.PismoField
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Серверы — порт ServersForm.cs и MainForm_ServerRail.cs.
 *
 * Слева вертикальный рельс серверов (как в Discord и на ПК), справа —
 * каналы выбранного сервера с бейджами непрочитанного, упоминаний и
 * списком тех, кто сейчас в голосовом канале.
 */
@Composable
fun ServersScreen(
    onOpenChannel: (serverId: Int, channelId: Int, channelName: String) -> Unit,
    onJoinVoice: (channelId: Int, channelName: String) -> Unit,
    onOpenMembers: (serverId: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var servers by remember { mutableStateOf<List<ServerSummary>>(emptyList()) }
    var selected by remember { mutableStateOf<ServerSummary?>(null) }
    var channels by remember { mutableStateOf<List<ServerChannel>>(emptyList()) }
    var perms by remember { mutableStateOf(ServerPermissions()) }
    var voice by remember { mutableStateOf<Map<Int, List<VoiceParticipant>>>(emptyMap()) }
    var badges by remember { mutableStateOf<List<ServerRepository.Badge>>(emptyList()) }

    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var showAddChannel by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    suspend fun reloadServers() {
        runCatching {
            servers = ServerRepository.myServers()
            if (selected == null) selected = servers.firstOrNull()
            badges = ServerRepository.badges(UserSession.userName)
        }.onFailure { error = it.message.orEmpty() }
    }

    suspend fun reloadChannels() {
        val s = selected ?: return
        runCatching {
            channels = ServerRepository.channels(s.id)
            perms = ServerRepository.permissions(s.id)
            voice = PresenceRepository.voiceForServer(s.id)
        }.onFailure { error = it.message.orEmpty() }
    }

    LaunchedEffect(Unit) { reloadServers() }
    LaunchedEffect(selected?.id) { reloadChannels() }

    // Присутствие в голосовых и бейджи обновляются часто — как на ПК.
    LaunchedEffect(selected?.id) {
        while (isActive) {
            delay(5000)
            runCatching {
                selected?.let { voice = PresenceRepository.voiceForServer(it.id) }
                badges = ServerRepository.badges(UserSession.userName)
            }
        }
    }

    Row(Modifier.fillMaxSize().background(PismoColors.BgSidebar)) {

        // ── Рельс серверов ────────────────────────────────────────────
        Column(
            Modifier
                .width(64.dp)
                .fillMaxHeight()
                .background(PismoColors.BgDarkest)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LazyColumn(horizontalAlignment = Alignment.CenterHorizontally) {
                items(servers, key = { it.id }) { s ->
                    val unread = badges.filter { it.serverId == s.id && !it.muted }
                    val mentions = unread.sumOf { it.mentions }
                    val isSelected = selected?.id == s.id

                    Box(Modifier.padding(vertical = 6.dp)) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(if (isSelected) RoundedCornerShape(16.dp) else CircleShape)
                                .background(
                                    if (isSelected) PismoColors.Blurple else PismoColors.BgSidebar
                                )
                                .clickable { selected = s },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                s.name.take(2).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                            )
                        }
                        if (mentions > 0) {
                            UnreadBadge(mentions, Modifier.align(Alignment.TopEnd))
                        } else if (unread.sumOf { it.unread } > 0) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                        }
                    }
                }

                item {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(PismoColors.BgSidebar)
                            .clickable { showCreate = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Add, "Создать сервер", tint = PismoColors.Green)
                    }
                }

                item {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(PismoColors.BgSidebar)
                            .clickable { showJoin = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("#", color = PismoColors.Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── Каналы выбранного сервера ─────────────────────────────────
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val s = selected
            if (s == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (error.isNotEmpty()) "Ошибка: $error"
                        else "Серверов пока нет.\nСоздайте свой или присоединитесь по ID.",
                        color = PismoColors.TextMuted,
                        fontSize = 14.sp,
                    )
                }
                return@Column
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(PismoColors.BgDarkest)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        s.name,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("ID: ${s.id}", color = PismoColors.TextMuted, fontSize = 11.sp)
                }
                if (perms.mutedNotifications) {
                    Icon(
                        Icons.Default.NotificationsOff, "Уведомления заглушены",
                        tint = PismoColors.TextMuted, modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { onOpenMembers(s.id) }) {
                    Icon(Icons.Default.Group, "Участники", tint = PismoColors.TextSecondary)
                }
                if (perms.isAdminLike) {
                    IconButton(onClick = { showAddChannel = true }) {
                        Icon(Icons.Default.Add, "Новый канал", tint = PismoColors.TextSecondary)
                    }
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                val text = channels.filter { it.type == ChannelType.TEXT }
                val voiceChannels = channels.filter { it.type == ChannelType.VOICE }

                if (text.isNotEmpty()) {
                    item { CategoryLabel("ТЕКСТОВЫЕ КАНАЛЫ") }
                    items(text, key = { "t${it.id}" }) { ch ->
                        val b = badges.firstOrNull { it.channelId == ch.id }
                        ChannelRow(
                            channel = ch,
                            unread = b?.unread ?: 0,
                            mentions = b?.mentions ?: 0,
                            muted = b?.muted == true,
                            onClick = { onOpenChannel(s.id, ch.id, ch.name) },
                        )
                    }
                }

                if (voiceChannels.isNotEmpty()) {
                    item { CategoryLabel("ГОЛОСОВЫЕ КАНАЛЫ") }
                    items(voiceChannels, key = { "v${it.id}" }) { ch ->
                        val here = voice[ch.id].orEmpty()
                        VoiceChannelRow(
                            channel = ch,
                            participants = here,
                            onJoin = {
                                // Лимит вместимости (миграция 14): 0 — без ограничения.
                                scope.launch {
                                    val already = PresenceRepository.amIInChannel(ch.id)
                                    val count = PresenceRepository.voiceCount(ch.id)
                                    if (ch.userLimit > 0 && !already && count >= ch.userLimit) {
                                        error = "Канал заполнен: $count из ${ch.userLimit}"
                                    } else {
                                        onJoinVoice(ch.id, ch.name)
                                    }
                                }
                            },
                        )
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showCreate) {
        NameDialog(
            title = "Новый сервер",
            label = "Название сервера",
            confirm = "Создать",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                scope.launch {
                    runCatching {
                        val id = ServerRepository.createServer(name)
                        reloadServers()
                        selected = servers.firstOrNull { it.id == id } ?: selected
                    }.onFailure { error = it.message.orEmpty() }
                    showCreate = false
                }
            },
        )
    }

    if (showJoin) {
        NameDialog(
            title = "Присоединиться к серверу",
            label = "ID сервера",
            confirm = "Войти",
            onDismiss = { showJoin = false },
            onConfirm = { value ->
                val id = value.trim().toIntOrNull()
                scope.launch {
                    if (id == null) {
                        error = "ID должен быть числом."
                    } else {
                        when (val r = ServerRepository.joinServer(id)) {
                            is ServerRepository.JoinResult.Ok -> {
                                reloadServers()
                                selected = servers.firstOrNull { it.id == id }
                                error = ""
                            }
                            ServerRepository.JoinResult.Banned ->
                                error = "Вы забанены на этом сервере."
                            ServerRepository.JoinResult.NotFound ->
                                error = "Сервер с таким ID не найден."
                        }
                    }
                    showJoin = false
                }
            },
        )
    }

    if (showAddChannel) {
        ChannelDialog(
            onDismiss = { showAddChannel = false },
            onConfirm = { name, type ->
                scope.launch {
                    selected?.let {
                        runCatching { ServerRepository.createChannel(it.id, name, type) }
                        reloadChannels()
                    }
                    showAddChannel = false
                }
            },
        )
    }
}

@Composable
private fun CategoryLabel(text: String) {
    Text(
        text,
        color = PismoColors.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChannelRow(
    channel: ServerChannel,
    unread: Int,
    mentions: Int,
    muted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Tag, null,
            tint = PismoColors.TextMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            channel.name,
            color = if (unread > 0 && !muted) Color.White else PismoColors.TextSecondary,
            fontSize = 15.sp,
            fontWeight = if (unread > 0 && !muted) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (mentions > 0) UnreadBadge(mentions)
        else if (unread > 0 && !muted) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun VoiceChannelRow(
    channel: ServerChannel,
    participants: List<VoiceParticipant>,
    onJoin: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onJoin)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.VolumeUp, null,
                tint = PismoColors.TextMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                channel.name,
                color = PismoColors.TextSecondary,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (channel.userLimit > 0) {
                Pill("${participants.size}/${channel.userLimit}")
            } else if (participants.isNotEmpty()) {
                Pill("${participants.size}")
            }
        }

        participants.forEach { p ->
            Row(
                Modifier.padding(start = 44.dp, end = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserAvatar(p.userId, p.name, 22.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    p.name,
                    color = PismoColors.TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (p.streaming) {
                    Pill("В ЭФИРЕ", PismoColors.Red, Color.White)
                    Spacer(Modifier.width(4.dp))
                }
                if (p.micMuted) {
                    Icon(
                        Icons.Default.MicOff, "Микрофон выключен",
                        tint = PismoColors.Red, modifier = Modifier.size(14.dp),
                    )
                }
                if (p.deafened) {
                    Icon(
                        Icons.Default.HeadsetOff, "Звук выключен",
                        tint = PismoColors.Red, modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = { Text(title, color = Color.White) },
        text = { PismoField(value, { value = it }, label) },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) {
                Text(confirm, color = PismoColors.Blurple)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = PismoColors.TextMuted) }
        },
    )
}

@Composable
private fun ChannelDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, ChannelType) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ChannelType.TEXT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = { Text("Новый канал", color = Color.White) },
        text = {
            Column {
                PismoField(name, { name = it }, "Название канала")
                Spacer(Modifier.height(12.dp))
                Row {
                    TextButton(onClick = { type = ChannelType.TEXT }) {
                        Text(
                            "# Текстовый",
                            color = if (type == ChannelType.TEXT) PismoColors.Blurple
                            else PismoColors.TextMuted,
                        )
                    }
                    TextButton(onClick = { type = ChannelType.VOICE }) {
                        Text(
                            "🔊 Голосовой",
                            color = if (type == ChannelType.VOICE) PismoColors.Blurple
                            else PismoColors.TextMuted,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), type) }) {
                Text("Создать", color = PismoColors.Blurple)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = PismoColors.TextMuted) }
        },
    )
}
