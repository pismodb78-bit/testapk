package com.pismo.messenger.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.core.formatListTime
import com.pismo.messenger.core.parseHexColor
import com.pismo.messenger.data.model.Conversation
import com.pismo.messenger.data.model.Presence
import com.pismo.messenger.data.model.GroupSummary
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.PresenceRepository
import com.pismo.messenger.data.repo.ProfileRepository
import com.pismo.messenger.net.SignalingClient
import com.pismo.messenger.ui.components.LetterAvatar
import com.pismo.messenger.ui.components.GroupAvatar
import com.pismo.messenger.ui.components.UserAvatar
import com.pismo.messenger.ui.group.CreateGroupDialog
import com.pismo.messenger.ui.group.GroupMembersDialog
import com.pismo.messenger.ui.components.UnreadBadge
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onOpenChat: (Int, String) -> Unit,
    onOpenGroup: (Int, String) -> Unit,
    onSettings: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    // Статусы собеседников для точек на аватарках. Обновляются чаще списка:
    // сам список — тяжёлый запрос, а присутствие меняется каждые несколько
    // секунд, и точка обязана за ним поспевать.
    var presence by remember { mutableStateOf<Map<Int, Presence>>(emptyMap()) }
    var groups by remember { mutableStateOf<List<GroupSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var showCreateGroup by remember { mutableStateOf(false) }
    var manageGroup by remember { mutableStateOf<GroupSummary?>(null) }
    var userActions by remember { mutableStateOf<Conversation?>(null) }

    suspend fun reload() {
        runCatching {
            groups = ChatRepository.loadGroups()
            conversations = if (UserSession.isAdminRootView) {
                ChatRepository.loadAllUsers()
            } else {
                ChatRepository.loadConversations()
            }
            // Аватарки списка тянем одним запросом, а не по одной на строку.
            ProfileRepository.prefetchAvatars(conversations.map { it.userId })
            error = ""
        }.onFailure { error = it.message ?: "ошибка загрузки" }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    // Присутствие обновляем отдельным лёгким запросом с тем же периодом, что
    // и ПК (6 с). Держать его в общей перезагрузке списка нельзя: тот идёт
    // только при изменении непрочитанного, и точки бы застывали.
    LaunchedEffect(Unit) {
        while (isActive) {
            val ids = conversations.map { it.userId }
            if (ids.isNotEmpty()) {
                runCatching { presence = PresenceRepository.presenceFor(ids) }
            }
            delay(6000)
        }
    }

    // Опрос как на ПК (2.5 с) + мгновенное обновление по событию ws-сервера.
    //
    // Полный список диалогов тянет тяжёлый запрос с подзапросами на каждого
    // пользователя, поэтому по таймеру спрашиваем только дешёвую сводку
    // непрочитанного и максимальные id по группам — и перезагружаем список
    // целиком, лишь когда сводка изменилась. ПК устроен так же: PollTick
    // обновляет бейджи, а не пересобирает сайдбар каждый тик.
    var lastSignature by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(2500)
            runCatching {
                val unread = ChatRepository.unreadBySender()
                val groupMax = ChatRepository.groupMaxIncoming()
                val signature = unread.toSortedMap().toString() +
                        groupMax.mapValues { it.value.first }.toSortedMap().toString()
                if (signature != lastSignature) {
                    lastSignature = signature
                    reload()
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val listener: (String, Int, Int, String) -> Unit = { type, _, _, _ ->
            if (type == "new_message") scope.launch { reload() }
        }
        SignalingClient.addListener(listener)
        onDispose { SignalingClient.removeListener(listener) }
    }

    Scaffold(
        containerColor = PismoColors.BgSidebar,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (UserSession.isImpersonating) "За: ${UserSession.effectiveName}"
                            else "PISMO",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        Text(
                            UserSession.effectiveName,
                            color = PismoColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { reload() } }) {
                        Icon(Icons.Default.Refresh, "Обновить", tint = PismoColors.TextSecondary)
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, "Настройки", tint = PismoColors.TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PismoColors.BgDarkest,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateGroup = true },
                containerColor = PismoColors.Blurple,
            ) {
                Icon(Icons.Default.GroupAdd, "Новая группа", tint = Color.White)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (loading && conversations.isEmpty() && groups.isEmpty()) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center),
                    color = PismoColors.Blurple,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (error.isNotEmpty()) {
                        item {
                            Text(
                                "Ошибка: $error\nПроверьте настройки подключения к базе.",
                                color = PismoColors.Red,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }

                    if (groups.isNotEmpty()) {
                        item { SectionHeader("ГРУППЫ") }
                        items(groups, key = { "g${it.id}" }) { g ->
                            GroupRow(
                                g = g,
                                onClick = { onOpenGroup(g.id, g.name) },
                                onLongClick = { manageGroup = g },
                            )
                        }
                    }

                    item {
                        SectionHeader(
                            if (UserSession.isAdminRootView) "ВСЕ ПОЛЬЗОВАТЕЛИ"
                            else "ЛИЧНЫЕ СООБЩЕНИЯ"
                        )
                    }
                    items(conversations, key = { "u${it.userId}" }) { c ->
                        ConversationRow(
                            c = c,
                            presence = presence[c.userId],
                            onClick = { onOpenChat(c.userId, c.name) },
                            onLongClick = { userActions = c },
                        )
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (showCreateGroup) {
        CreateGroupDialog(
            onDismiss = { showCreateGroup = false },
            onCreated = { id, name ->
                showCreateGroup = false
                scope.launch { reload() }
                onOpenGroup(id, name)
            },
        )
    }

    userActions?.let { c ->
        UserActionsDialog(
            conversation = c,
            onDismiss = { userActions = null },
            onChanged = { userActions = null; scope.launch { reload() } },
            onOpenChat = { userActions = null; onOpenChat(c.userId, c.name) },
        )
    }

    manageGroup?.let { g ->
        GroupMembersDialog(
            groupId = g.id,
            groupName = g.name,
            onDismiss = { manageGroup = null },
            onLeft = {
                manageGroup = null
                scope.launch { reload() }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = PismoColors.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    c: Conversation,
    presence: Presence?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(c.userId, c.name, 44.dp, presence = presence)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                c.name,
                color = PismoColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (c.lastMessage.isNotBlank()) {
                Text(
                    c.lastMessage,
                    color = PismoColors.TextMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatListTime(c.lastTimeMs),
                color = PismoColors.TextMuted,
                fontSize = 11.sp,
            )
            if (c.unread > 0) {
                Spacer(Modifier.height(4.dp))
                UnreadBadge(c.unread)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(g: GroupSummary, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupAvatar(g.id, g.name, g.avatarColorHex, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "👥 ${g.name}",
                color = PismoColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                g.lastMessage.ifBlank { "${g.memberCount} участник(ов)" },
                color = PismoColors.TextMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            formatListTime(g.lastTimeMs),
            color = PismoColors.TextMuted,
            fontSize = 11.sp,
        )
    }
}
