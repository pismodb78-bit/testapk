package com.pismo.messenger.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.ellipsize
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.ReactionSummary
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.model.ServerPermissions
import com.pismo.messenger.data.MessageMemory
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.ReactionsRepository
import com.pismo.messenger.data.repo.ServerRepository
import com.pismo.messenger.media.Sounds
import com.pismo.messenger.core.PresenceReporter
import com.pismo.messenger.core.Mentions
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.core.formatDateSeparator
import com.pismo.messenger.net.SignalingClient
import com.pismo.messenger.ui.chat.DateJumpDialog
import com.pismo.messenger.ui.chat.MessageBubble
import com.pismo.messenger.ui.chat.PinnedMessagesDialog
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

    // То же, что в личных чатах: запомненная лента показывается сразу,
    // свежая подтягивается фоном. Объявление обязано стоять ПЕРЕД messages —
    // из него берётся начальное значение.
    val remembered = remember(channelId) { MessageMemory.peek(Scope.SERVER, channelId) }
    var messages by remember(channelId) {
        mutableStateOf(remembered?.first ?: emptyList())
    }
    var reactions by remember(channelId) {
        mutableStateOf(remembered?.second ?: emptyMap<Int, List<ReactionSummary>>())
    }
    var perms by remember { mutableStateOf(ServerPermissions()) }
    // TextFieldValue, а не String: подсказка @упоминаний обязана знать, где
    // стоит курсор, иначе непонятно, какое из «@» в строке дополняется.
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    var loading by remember(channelId) { mutableStateOf(remembered == null) }
    var lastCount by remember { mutableStateOf(0) }
    // Логин и роль — для подсветки сообщений, где упомянули меня.
    var mentionMe by remember(channelId) { mutableStateOf("" to "") }
    // Множественное выделение — то же, что в личных чатах.
    var selectMode by remember(channelId) { mutableStateOf(false) }
    var selectedIds by remember(channelId) { mutableStateOf(setOf<Int>()) }
    var forwardBatch by remember(channelId) {
        mutableStateOf<List<com.pismo.messenger.ui.chat.ForwardItem>>(emptyList())
    }
    var fullscreenVideo by remember(channelId) {
        mutableStateOf<com.pismo.messenger.ui.chat.FullscreenVideo?>(null)
    }

    // Открываемся сразу на последнем сообщении, как в личных чатах.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (remembered?.first?.size ?: 1) - 1
    )
    var showSearch by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    var showPins by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var jumpNote by remember { mutableStateOf("") }
    // Размер загружаемой страницы. Обычно хватает сорока последних, но
    // прыжок на старую дату требует дотянуть ленту до неё.
    var pageLimit by remember(channelId) { mutableStateOf(40) }

    /** Самое свежее чужое сообщение, о котором уже отзвучали. См. ChatScreen. */
    var lastHeardId by remember(channelId) { mutableIntStateOf(-1) }

    suspend fun reload(scrollToEnd: Boolean = false) {
        runCatching {
            val loaded = ServerRepository.channelMessages(channelId, limit = pageLimit)
            // Присваиваем только при реальном изменении — иначе одинаковый
            // список каждые 2,5 секунды перекомпоновывал бы все пузыри.
            if (loaded.size != messages.size ||
                loaded.zip(messages).any { (a, b) -> a != b }
            ) {
                messages = loaded
            }
            lastCount = loaded.size

            // «Плип» на чужое сообщение в открытом канале — как на ПК.
            val newestIncoming = loaded.filterNot { it.isMine }.maxOfOrNull { it.id } ?: -1
            if (lastHeardId >= 0 && newestIncoming > lastHeardId) Sounds.message()
            if (newestIncoming > lastHeardId) lastHeardId = newestIncoming

            reactions = ReactionsRepository.forMessages(loaded.map { it.id }, Scope.SERVER)
            MessageMemory.put(Scope.SERVER, channelId, loaded, reactions)
            ServerRepository.prefetchChannelMedia(loaded)
            perms = ServerRepository.permissions(serverId)
            // То же, что в личных чатах: свёрнутое приложение не должно
            // «прочитывать» канал за пользователя и глушить уведомления.
            if (PresenceReporter.isForeground) ServerRepository.markChannelRead(channelId)
        }
        loading = false
        // Прокручиваем вниз, только если пользователь и так был у конца
        // ленты: иначе новое сообщение выдёргивало бы его из середины
        // истории, которую он читает.
        val atBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            ?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } ?: true
        // Пока ленту листает пользователь — не трогаем: scrollToItem
        // мгновенно обрывает инерцию, а опрос приходит раз в две с половиной
        // секунды и легко попадает в разгон пальца.
        if (scrollToEnd && messages.isNotEmpty() && atBottom && !listState.isScrollInProgress) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(channelId) {
        mentionMe = runCatching { ServerRepository.mentionIdentity(channelId) }
            .getOrDefault("" to "")
    }

    LaunchedEffect(channelId) {
        // Кружок ставим, только если показать нечего: с запомненной лентой
        // он перекрыл бы уже нарисованные сообщения ради того же результата.
        if (remembered == null) loading = true
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
        val text = input.text.trim()
        val edit = editing
        if (edit != null) {
            if (text.isEmpty()) return
            scope.launch {
                runCatching { ServerRepository.editChannelMessage(edit.id, text) }
                editing = null; input = TextFieldValue("")
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
            input = TextFieldValue(""); replyTo = null
            reload(scrollToEnd = true)
        }
    }

    Scaffold(
        containerColor = PismoColors.BgMain,
        topBar = {
            TopAppBar(
                title = {
                    Text("# $channelName", color = PismoColors.TextPrimary, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад", tint = PismoColors.TextSecondary)
                    }
                },
                actions = {
                    // Ровно то же меню, что и в личных чатах: поиск, прыжок
                    // на дату и закреплённые. Раньше здесь висела одна лупа,
                    // и два других действия в канале были недоступны вовсе.
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "Ещё", tint = PismoColors.TextSecondary)
                        }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Поиск по каналу") },
                                onClick = { menuOpen = false; showSearch = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Перейти к дате") },
                                onClick = { menuOpen = false; showCalendar = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Закреплённые") },
                                onClick = { menuOpen = false; showPins = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PismoColors.BgDarkest),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Клавиатура наезжала на строку ввода — ровно как в ЛС.
                .imePadding()
        ) {
            if (selectMode) {
                com.pismo.messenger.ui.chat.SelectionBar(
                    count = selectedIds.size,
                    // В канале чужое удаляет модератор — то же правило, что
                    // и в меню одиночного сообщения.
                    canDelete = perms.isAdminLike ||
                        messages.filter { it.id in selectedIds }
                            .all { it.senderId == UserSession.effectiveId },
                    onCancel = { selectMode = false; selectedIds = emptySet() },
                    onForward = {
                        forwardBatch = messages
                            .filter { it.id in selectedIds }
                            .map {
                                com.pismo.messenger.ui.chat.ForwardItem(
                                    it.id, it.text, it.senderName
                                )
                            }
                    },
                    onDelete = {
                        val ids = selectedIds.toList()
                        selectMode = false
                        selectedIds = emptySet()
                        scope.launch {
                            ids.forEach { id ->
                                val mine = messages.firstOrNull { it.id == id }
                                    ?.senderId == UserSession.effectiveId
                                runCatching {
                                    ServerRepository.deleteChannelMessage(
                                        id,
                                        asModerator = !mine && perms.isAdminLike,
                                    )
                                }
                            }
                            reload()
                        }
                    },
                )
            }

            // fillMaxWidth: без него ширину задаёт самый широкий ребёнок, и
            // на время загрузки им был сам индикатор — кружок уезжал влево.
            Box(Modifier.fillMaxWidth().weight(1f)) {
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
                                onOpenVideo = { file, name, id ->
                                    fullscreenVideo =
                                        com.pismo.messenger.ui.chat.FullscreenVideo(file, name, id)
                                },
                                mentioned = msg.senderId != UserSession.effectiveId &&
                                    Mentions.mentionsMe(msg.text, mentionMe.first, mentionMe.second),
                                selectMode = selectMode,
                                selected = msg.id in selectedIds,
                                onEnterSelect = {
                                    selectMode = true
                                    selectedIds = setOf(msg.id)
                                },
                                onToggleSelect = {
                                    selectedIds =
                                        if (msg.id in selectedIds) selectedIds - msg.id
                                        else selectedIds + msg.id
                                    if (selectedIds.isEmpty()) selectMode = false
                                },
                                reactions = reactions[msg.id].orEmpty(),
                                scopeKind = Scope.SERVER,
                                onReply = { replyTo = msg },
                                onEdit = { editing = msg; input = TextFieldValue(msg.text) },
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
                    IconButton(onClick = { replyTo = null; editing = null; input = TextFieldValue("") }) {
                        Icon(Icons.Default.Close, "Отменить", tint = PismoColors.TextMuted)
                    }
                }
            }

            // Подсказка @упоминаний — прямо над строкой ввода. Позицию «@»
            // и набранный после него кусок считаем из курсора, как на ПК:
            // подсказка живёт до пробела и гаснет сама.
            val mention = mentionPrefix(input.text, input.selection.start)
            if (mention != null) {
                MentionSuggestions(
                    serverId = serverId,
                    partial = mention.second,
                    onPick = { option ->
                        val (text, cursor) = applyMention(
                            input.text, mention.first, input.selection.start, option.token
                        )
                        input = TextFieldValue(
                            text = text,
                            selection = androidx.compose.ui.text.TextRange(cursor),
                        )
                    },
                )
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
                    // ImeAction.None даёт обычный Enter, переводящий строку:
                    // с действием по умолчанию там «Готово», и переноса не набрать.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.None,
                    ),
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

    com.pismo.messenger.ui.chat.FullscreenVideoHost(fullscreenVideo) { fullscreenVideo = null }

    if (forwardBatch.isNotEmpty()) {
        com.pismo.messenger.ui.chat.ForwardDialog(
            srcScope = Scope.SERVER,
            items = forwardBatch,
            onDismiss = { forwardBatch = emptyList() },
            onDone = {
                forwardBatch = emptyList()
                selectMode = false
                selectedIds = emptySet()
            },
        )
    }

    if (showSearch) {
        ChannelSearchDialog(
            channelId = channelId,
            onDismiss = { showSearch = false },
            onJump = { found ->
                showSearch = false
                // Прыгаем к сообщению, если оно есть на загруженной странице.
                val idx = messages.indexOfFirst { it.id == found.id }
                if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
            },
        )
    }

    if (showCalendar) {
        DateJumpDialog(
            onDismiss = { showCalendar = false },
            onPick = { dayStartMs ->
                showCalendar = false
                scope.launch {
                    val need = ChatRepository.countSince(Scope.SERVER, channelId, dayStartMs)
                    if (need <= 0) {
                        jumpNote = "За выбранную дату и позже сообщений нет."
                        return@launch
                    }
                    // Тянем на пяток больше нужного: иначе целевое сообщение
                    // окажется первым в ленте, без строчки контекста над ним.
                    if (need + 5 > messages.size) {
                        pageLimit = need + 5
                        loading = true
                        reload()
                    }
                    val idx = messages.indexOfFirst { it.createdAtMs >= dayStartMs }
                    if (idx >= 0) {
                        listState.animateScrollToItem(idx)
                        jumpNote = ""
                    } else {
                        jumpNote = "Не удалось найти сообщения за эту дату."
                    }
                }
            },
        )
    }

    if (showPins) {
        PinnedMessagesDialog(
            scopeKind = Scope.SERVER,
            targetId = channelId,
            onDismiss = { showPins = false },
        )
    }

    if (jumpNote.isNotBlank()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { jumpNote = "" },
            containerColor = PismoColors.BgSidebar,
            text = { Text(jumpNote, color = PismoColors.TextPrimary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { jumpNote = "" }) {
                    Text("Понятно", color = PismoColors.Cyan)
                }
            },
        )
    }
}
