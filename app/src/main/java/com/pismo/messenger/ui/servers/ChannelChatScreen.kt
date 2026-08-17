package com.pismo.messenger.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.ellipsize
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.ReactionSummary
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.model.ServerPermissions
import com.pismo.messenger.data.repo.ReactionsRepository
import com.pismo.messenger.data.repo.ServerRepository
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.core.formatDateSeparator
import com.pismo.messenger.net.SignalingClient
import com.pismo.messenger.ui.chat.MessageBubble
import com.pismo.messenger.ui.components.DateSeparator
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Переписка в текстовом канале сервера — порт чата ServersForm.cs.
 *
 * Отличия от личных чатов, унаследованные от ПК: сообщения удаляются
 * жёстко (is_deleted в схеме server_messages нет), а чужое сообщение
 * может удалить только модератор.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelChatScreen(
    serverId: Int,
    channelId: Int,
    channelName: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var reactions by remember { mutableStateOf<Map<Int, List<ReactionSummary>>>(emptyMap()) }
    var perms by remember { mutableStateOf(ServerPermissions()) }
    var input by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var lastCount by remember { mutableStateOf(0) }

    val listState = rememberLazyListState()

    suspend fun reload(scrollToEnd: Boolean = false) {
        runCatching {
            val loaded = ServerRepository.channelMessages(channelId)
            messages = loaded
            lastCount = loaded.size
            reactions = ReactionsRepository.forMessages(loaded.map { it.id }, Scope.SERVER)
            ServerRepository.prefetchChannelMedia(loaded)
            perms = ServerRepository.permissions(serverId)
            ServerRepository.markChannelRead(channelId)
        }
        loading = false
        if (scrollToEnd && messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    LaunchedEffect(channelId) {
        loading = true
        reload(scrollToEnd = true)
    }

    LaunchedEffect(channelId) {
        while (isActive) {
            delay(2500)
            runCatching {
                val count = ServerRepository.channelMessageCount(channelId)
                if (count != lastCount) reload(scrollToEnd = true)
            }
        }
    }

    DisposableEffect(channelId) {
        val listener: (String, Int, Int, String) -> Unit = { type, _, _, _ ->
            if (type == "new_message") scope.launch { reload(scrollToEnd = true) }
        }
        SignalingClient.addListener(listener)
        onDispose { SignalingClient.removeListener(listener) }
    }

    fun send() {
        val text = input.trim()
        val edit = editing
        if (edit != null) {
            if (text.isEmpty()) return
            scope.launch {
                runCatching { ServerRepository.editChannelMessage(edit.id, text) }
                editing = null; input = ""
                reload()
            }
            return
        }
        if (text.isEmpty()) return
        scope.launch {
            runCatching {
                ServerRepository.sendChannelMessage(channelId, text, replyTo?.id ?: 0)
                SignalingClient.send("new_message", 0, channelId, "server")
            }
            input = ""; replyTo = null
            reload(scrollToEnd = true)
        }
    }

    Scaffold(
        containerColor = PismoColors.BgMain,
        topBar = {
            TopAppBar(
                title = {
                    Text("# $channelName", color = Color.White, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад", tint = PismoColors.TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PismoColors.BgDarkest),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f)) {
                if (loading) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center), color = PismoColors.Blurple,
                    )
                } else if (messages.isEmpty()) {
                    Text(
                        "Здесь пока пусто. Напишите первое сообщение.",
                        color = PismoColors.TextMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 10.dp, vertical = 8.dp
                        ),
                    ) {
                        // Ключ по id: без него обновление списка сбрасывает
                        // состояние всех пузырей, и картинки грузятся заново.
                        items(messages.size, key = { messages[it].id }) { index ->
                            val msg = messages[index]
                            val prev = messages.getOrNull(index - 1)
                            val showDate = prev == null ||
                                    formatDateSeparator(prev.createdAtMs) !=
                                    formatDateSeparator(msg.createdAtMs)
                            if (showDate) DateSeparator(formatDateSeparator(msg.createdAtMs))

                            MessageBubble(
                                msg = msg,
                                isGroup = true,          // в канале всегда показываем автора
                                reactions = reactions[msg.id].orEmpty(),
                                scopeKind = Scope.SERVER,
                                onReply = { replyTo = msg },
                                onEdit = { editing = msg; input = msg.text },
                                onDelete = {
                                    scope.launch {
                                        val mine = msg.senderId == UserSession.effectiveId
                                        runCatching {
                                            ServerRepository.deleteChannelMessage(
                                                msg.id,
                                                asModerator = !mine && perms.isAdminLike,
                                            )
                                        }
                                        reload()
                                    }
                                },
                                onReact = { emoji ->
                                    scope.launch {
                                        ReactionsRepository.toggle(msg.id, Scope.SERVER, emoji)
                                        reactions = ReactionsRepository.forMessages(
                                            messages.map { it.id }, Scope.SERVER
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            val banner = replyTo ?: editing
            if (banner != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(PismoColors.BgElevated)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(32.dp)
                            .background(if (editing != null) PismoColors.Yellow else PismoColors.Cyan)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (editing != null) "Редактирование"
                            else "Ответ для ${banner.senderName}",
                            color = if (editing != null) PismoColors.Yellow else PismoColors.Cyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            banner.text.ellipsize(60),
                            color = PismoColors.TextMuted, fontSize = 12.sp, maxLines = 1,
                        )
                    }
                    IconButton(onClick = { replyTo = null; editing = null; input = "" }) {
                        Icon(Icons.Default.Close, "Отменить", tint = PismoColors.TextMuted)
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(PismoColors.BgDarkest)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("Сообщение в #$channelName", color = PismoColors.TextMuted, fontSize = 14.sp)
                    },
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = PismoColors.TextPrimary,
                        unfocusedTextColor = PismoColors.TextPrimary,
                        cursorColor = PismoColors.Blurple,
                    ),
                )
                IconButton(onClick = { send() }) {
                    Icon(Icons.Default.Send, "Отправить", tint = PismoColors.Blurple)
                }
            }
        }
    }
}
