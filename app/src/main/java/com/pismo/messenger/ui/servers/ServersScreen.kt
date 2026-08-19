package com.pismo.messenger.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Settings
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
import com.pismo.messenger.data.ServerMemory
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServersScreen(
    onOpenChannel: (serverId: Int, channelId: Int, channelName: String) -> Unit,
    onJoinVoice: (channelId: Int, channelName: String) -> Unit,
    onOpenMembers: (serverId: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Стартуем с того, что показывали в прошлый раз: дерево серверов едет из
    // удалённой базы четырьмя запросами, и без этого вкладка каждый раз
    // открывалась пустым рельсом. Свежее приезжает следом и подменяет.
    val rememberedServers = remember { ServerMemory.peekServers() }
    val rememberedSelected = remember { ServerMemory.peekSelected(rememberedServers) }
    val rememberedChannels = remember {
        rememberedSelected?.let { ServerMemory.peekChannels(it.id) }
    }

    var servers by remember { mutableStateOf(rememberedServers) }
    var selected by remember { mutableStateOf(rememberedSelected) }
    var channels by remember { mutableStateOf(rememberedChannels?.first ?: emptyList()) }
    var perms by remember { mutableStateOf(rememberedChannels?.second ?: ServerPermissions()) }
    var voice by remember { mutableStateOf<Map<Int, List<VoiceParticipant>>>(emptyMap()) }
    var badges by remember { mutableStateOf(ServerMemory.peekBadges()) }

    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var showAddChannel by remember { mutableStateOf(false) }
    var showServerSettings by remember { mutableStateOf(false) }
    var editChannel by remember { mutableStateOf<ServerChannel?>(null) }
    var error by remember { mutableStateOf("") }

    suspend fun reloadServers() {
        runCatching {
            servers = ServerRepository.myServers()
            ServerMemory.putServers(servers)
            // Выбор восстанавливаем по запомненному id, а не «первый в
            // списке»: возврат на вкладку должен попадать туда же, откуда
            // ушли. Если сервера в списке уже нет — берём первый.
            if (selected == null || servers.none { it.id == selected?.id }) {
                selected = ServerMemory.peekSelected(servers)
            }
            badges = ServerRepository.badges()
            ServerMemory.putBadges(badges)
        }.onFailure { error = it.message.orEmpty() }
    }

    suspend fun reloadChannels() {
        val s = selected ?: return
        ServerMemory.putSelected(s.id)

        // Пока едут свежие каналы, показываем запомненные — но именно ЭТОГО
        // сервера. Если про него ничего не помним, список надо очистить:
        // оставить на экране каналы предыдущего сервера — это показать
        // человеку чужое дерево и дать по нему кликнуть.
        val cached = ServerMemory.peekChannels(s.id)
        if (cached != null) {
            channels = cached.first
            perms = cached.second
        } else {
            channels = emptyList()
            perms = ServerPermissions()
            voice = emptyMap()
        }

        runCatching {
            channels = ServerRepository.channels(s.id)
            perms = ServerRepository.permissions(s.id)
            ServerMemory.putChannels(s.id, channels, perms)
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
                badges = ServerRepository.badges()
                ServerMemory.putBadges(badges)
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
                                color = if (isSelected) Color.White
                                else PismoColors.TextPrimary,
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
                        color = PismoColors.TextPrimary,
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
                IconButton(onClick = { showServerSettings = true }) {
                    Icon(Icons.Default.Settings, "Настройки сервера", tint = PismoColors.TextSecondary)
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
                            onLongClick = { if (perms.isAdminLike) editChannel = ch },
                        )
                    }
                }

                if (voiceChannels.isNotEmpty()) {
                    item { CategoryLabel("ГОЛОСОВЫЕ КАНАЛЫ") }
                    items(voiceChannels, key = { "v${it.id}" }) { ch ->
                        val here = voice[ch.id].orEmpty()
                        val vb = badges.firstOrNull { it.channelId == ch.id }
                        VoiceChannelRow(
                            channel = ch,
                            participants = here,
                            unread = vb?.unread ?: 0,
                            mentions = vb?.mentions ?: 0,
                            muted = vb?.muted == true,
                            // У голосового канала свой чат — на ПК он
                            // открывается значком 💬 справа, БЕЗ входа в
                            // звонок. На телефоне его не было вовсе: нажатие
                            // по строке всегда вело в разговор.
                            onOpenChat = { onOpenChannel(s.id, ch.id, ch.name) },
                            onLongClick = { if (perms.isAdminLike) editChannel = ch },
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

    selected?.let { s ->
        if (showServerSettings) {
            ServerSettingsDialog(
                serverId = s.id,
                serverName = s.name,
                perms = perms,
                onDismiss = { showServerSettings = false },
                onChanged = { scope.launch { reloadServers(); reloadChannels() } },
                onLeft = {
                    showServerSettings = false
                    scope.launch {
                        selected = null
                        reloadServers()
                        selected = servers.firstOrNull()
                    }
                },
            )
        }
    }

    editChannel?.let { ch ->
        ChannelSettingsDialog(
            channelId = ch.id,
            channelName = ch.name,
            isVoice = ch.type == ChannelType.VOICE,
            userLimit = ch.userLimit,
            onDismiss = { editChannel = null },
            onChanged = { scope.launch { reloadChannels() } },
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: ServerChannel,
    unread: Int,
    mentions: Int,
    muted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
        // Красная цифра — упоминания, серая — просто непрочитанные.
        // Раньше на месте второй была безликая точка: было видно, что
        // «что-то есть», но не сколько и где именно.
        if (mentions > 0) UnreadBadge(mentions)
        else if (unread > 0 && !muted) UnreadBadge(unread, color = PismoColors.BgElevated)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceChannelRow(
    channel: ServerChannel,
    participants: List<VoiceParticipant>,
    unread: Int,
    mentions: Int,
    muted: Boolean,
    onJoin: () -> Unit,
    onOpenChat: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onJoin, onLongClick = onLongClick)
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
                color = if (unread > 0 && !muted) Color.White else PismoColors.TextSecondary,
                fontSize = 15.sp,
                fontWeight = if (unread > 0 && !muted) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // У голосового канала есть своя переписка, и непрочитанное в ней
            // копилось молча: счётчик стоял только у текстовых каналов, так
            // что написанное в чате голосового не было видно вообще ниоткуда.
            if (mentions > 0) UnreadBadge(mentions)
            else if (unread > 0 && !muted) UnreadBadge(unread, color = PismoColors.BgElevated)
            Spacer(Modifier.width(4.dp))
            // Значок чата справа — как 💬 в ServersForm: открывает
            // переписку канала, НЕ подключая к разговору.
            IconButton(onClick = onOpenChat, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.ChatBubbleOutline, "Чат канала",
                    tint = PismoColors.TextMuted, modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(4.dp))

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
        title = { Text(title, color = PismoColors.TextPrimary) },
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
        title = { Text("Новый канал", color = PismoColors.TextPrimary) },
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
