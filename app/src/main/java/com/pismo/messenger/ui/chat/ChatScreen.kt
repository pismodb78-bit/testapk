package com.pismo.messenger.ui.chat

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pismo.messenger.core.PresenceReporter
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.core.ellipsize
import com.pismo.messenger.core.fileBadge
import com.pismo.messenger.core.fileColor
import com.pismo.messenger.core.formatDateSeparator
import com.pismo.messenger.core.formatDuration
import com.pismo.messenger.core.formatTime
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.MediaCache
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.ReactionSummary
import com.pismo.messenger.data.model.Presence
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.model.headerText
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.PresenceRepository
import com.pismo.messenger.data.repo.ReactionsRepository
import com.pismo.messenger.media.WavPlayer
import com.pismo.messenger.media.WavRecorder
import com.pismo.messenger.net.SignalingClient
import com.pismo.messenger.ui.components.DateSeparator
import com.pismo.messenger.ui.components.FileBadge
import com.pismo.messenger.ui.components.LetterAvatar
import com.pismo.messenger.ui.components.GroupAvatar
import com.pismo.messenger.ui.components.UserAvatar
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Потолок длительности голосового — 3 минуты. Запись целиком держится в
 * памяти и уходит в БД одним BLOB'ом, поэтому верхняя граница обязательна.
 */
private const val VOICE_MAX_SECONDS = 180

/**
 * Потолок размера вложения.
 *
 * Сверху его задаёт сервер: max_allowed_packet на нашей базе — около
 * 256 МБ, больше одной строкой не примут. Снизу — сам телефон: файл
 * читается в память целиком, а JDBC и шифрование делают ещё копии, так что
 * на 250 МБ уйдёт под гигабайт кучи. Поэтому берём заведомо проходимые
 * 128 МБ и говорим об этом вслух, а не отбрасываем выбор молча, как было.
 */
private const val MAX_ATTACH_BYTES = 128L * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    targetId: Int,
    title: String,
    isGroup: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scopeKind = if (isGroup) Scope.GROUP else Scope.DM

    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var reactions by remember { mutableStateOf<Map<Int, List<ReactionSummary>>>(emptyMap()) }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var lastCount by remember { mutableStateOf(0) }

    // Режимы ответа и редактирования — как панель над строкой ввода на ПК.
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }

    // Блокировки: чат переходит в режим «только чтение».
    var iBlocked by remember { mutableStateOf(false) }
    var theyBlocked by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val recorder = remember { WavRecorder(scope) }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableStateOf(0) }
    var showCircleRecorder by remember { mutableStateOf(false) }
    var showPins by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    // Статус собеседника в шапке — «в сети» / «бездействует N» / «был в сети N».
    var peerPresence by remember(targetId) { mutableStateOf<Presence?>(null) }
    var jumpNote by remember { mutableStateOf("") }

    // Сколько сообщений тянуть. Переход к дате расширяет страницу ровно так
    // же, как _dmLimit на ПК: лента грузится с конца, и без расширения
    // прыжок в прошлый месяц упирался бы в незагруженную историю.
    var pageLimit by remember(targetId) { mutableStateOf(0) }

    suspend fun reload(scrollToEnd: Boolean = false) {
        runCatching {
            val loaded = if (isGroup) {
                if (pageLimit > 0) ChatRepository.loadGroupMessages(targetId, pageLimit)
                else ChatRepository.loadGroupMessages(targetId)
            } else {
                if (pageLimit > 0) ChatRepository.loadDirectMessages(targetId, pageLimit)
                else ChatRepository.loadDirectMessages(targetId)
            }
            messages = loaded
            lastCount = loaded.size
            reactions = ReactionsRepository.forMessages(loaded.map { it.id }, scopeKind)
            ChatRepository.prefetchPageMedia(loaded, scopeKind)
            if (!isGroup) {
                val state = ChatRepository.blockState(targetId)
                iBlocked = state.first
                theyBlocked = state.second
                // Читать за пользователя можно только когда он и правда
                // смотрит на экран. Опрос composable продолжает крутиться и
                // со свёрнутым приложением (активити остановлена, но не
                // уничтожена), и раньше он молча помечал всё прочитанным —
                // из-за этого от собеседника, чат с которым остался открыт,
                // уведомления не приходили вообще.
                if (PresenceReporter.isForeground) ChatRepository.markAsRead(targetId)
            }
        }
        loading = false
        // Прокручиваем вниз только если пользователь и так был у конца ленты:
        // иначе новое сообщение выдёргивало бы его из середины истории.
        val atBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            ?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } ?: true
        if (scrollToEnd && messages.isNotEmpty() && atBottom) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    fun sendVoice(wav: ByteArray?) {
        recording = false
        if (wav == null) return
        scope.launch {
            runCatching {
                ChatRepository.sendMessage(
                    scope = scopeKind,
                    target = targetId,
                    text = "",
                    audio = wav,
                    replyToId = replyTo?.id ?: 0,
                )
                notifyPeers(isGroup, targetId)
            }
            replyTo = null
            reload(scrollToEnd = true)
        }
    }

    // Таймер записи и жёсткий потолок в 3 минуты. Голосовое целиком лежит в
    // памяти и уходит в БД одним BLOB'ом, поэтому без верхней границы
    // забытая запись раздувает и приложение, и таблицу.
    LaunchedEffect(recording) {
        if (!recording) {
            recordSeconds = 0
            return@LaunchedEffect
        }
        recordSeconds = 0
        while (isActive && recording && recordSeconds < VOICE_MAX_SECONDS) {
            delay(1000)
            recordSeconds++
        }
        if (recording && recordSeconds >= VOICE_MAX_SECONDS) sendVoice(recorder.stop())
    }

    // reconnects в ключе: после восстановления связи с базой экран
    // перезагружается сам. Иначе первый неудачный запрос оставлял пустую
    // переписку до перезапуска приложения — опрос ниже сверяет только
    // количество и на нуле ничего не менял.
    val reconnects by Db.reconnects.collectAsState()

    LaunchedEffect(targetId, reconnects) {
        loading = true
        reload(scrollToEnd = true)
    }

    // Присутствие собеседника — отдельным лёгким запросом раз в 6 секунд,
    // тот же период, что у _presenceTimer на ПК. В общую перезагрузку его
    // класть нельзя: она идёт только при изменении числа сообщений.
    LaunchedEffect(targetId, isGroup) {
        if (isGroup) return@LaunchedEffect
        while (isActive) {
            runCatching { peerPresence = PresenceRepository.presenceOf(targetId) }
            delay(6000)
        }
    }

    // Опрос по числу сообщений — тот же приём, что PollTick на ПК.
    LaunchedEffect(targetId) {
        while (isActive) {
            delay(2500)
            runCatching {
                val count = if (isGroup) ChatRepository.groupMessageCount(targetId)
                else ChatRepository.directMessageCount(targetId)
                if (count != lastCount) reload(scrollToEnd = true)
            }
        }
    }

    DisposableEffect(targetId) {
        val listener: (String, Int, Int, String) -> Unit = { type, _, _, _ ->
            if (type == "new_message") scope.launch { reload(scrollToEnd = true) }
        }
        SignalingClient.addListener(listener)
        onDispose {
            SignalingClient.removeListener(listener)
            WavPlayer.stop()
            recorder.cancel()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val name = queryFileName(context, uri)

                // Размер узнаём ДО чтения: файл на 300 МБ иначе успел бы
                // положить приложение ещё до проверки.
                val declared = fileSizeOf(context, uri)
                if (declared > MAX_ATTACH_BYTES) {
                    jumpNote = "Файл слишком большой: " +
                            "${declared / 1024 / 1024} МБ при пределе " +
                            "${MAX_ATTACH_BYTES / 1024 / 1024} МБ."
                    return@runCatching
                }

                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@runCatching
                if (bytes.size > MAX_ATTACH_BYTES) {
                    jumpNote = "Файл слишком большой: " +
                            "${bytes.size / 1024 / 1024} МБ при пределе " +
                            "${MAX_ATTACH_BYTES / 1024 / 1024} МБ."
                    return@runCatching
                }

                sending = true
                val isImage = com.pismo.messenger.core.isImageName(name) ||
                        com.pismo.messenger.core.isGifName(name)
                ChatRepository.sendMessage(
                    scope = scopeKind,
                    target = targetId,
                    text = "",
                    image = if (isImage) bytes else null,
                    file = if (isImage) null else bytes,
                    fileName = name,
                    replyToId = replyTo?.id ?: 0,
                )
                replyTo = null
                notifyPeers(isGroup, targetId)
                reload(scrollToEnd = true)
            }
            sending = false
        }
    }

    fun send() {
        val text = input.trim()
        val edit = editing
        if (edit != null) {
            if (text.isEmpty()) return
            scope.launch {
                runCatching { ChatRepository.editMessage(scopeKind, edit.id, text) }
                editing = null
                input = ""
                reload()
            }
            return
        }
        if (text.isEmpty() || sending) return

        sending = true
        scope.launch {
            runCatching {
                ChatRepository.sendMessage(
                    scope = scopeKind,
                    target = targetId,
                    text = text,
                    replyToId = replyTo?.id ?: 0,
                )
                notifyPeers(isGroup, targetId)
            }
            input = ""
            replyTo = null
            sending = false
            reload(scrollToEnd = true)
        }
    }

    val readOnly = !isGroup && (iBlocked || theyBlocked)

    Scaffold(
        containerColor = PismoColors.BgMain,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isGroup) GroupAvatar(targetId, title, "#5865F2", 32.dp)
                        else UserAvatar(targetId, title, 32.dp, presence = peerPresence)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                if (isGroup) "👥 $title" else title,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            peerPresence?.takeIf { !isGroup }?.let { p ->
                                Text(
                                    p.headerText(),
                                    color = when {
                                        !p.isOnline -> PismoColors.TextMuted
                                        p.isIdle -> PismoColors.Yellow
                                        else -> PismoColors.Green
                                    },
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад", tint = PismoColors.TextSecondary)
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = true }) {
                        Icon(Icons.Default.Search, "Поиск", tint = PismoColors.TextSecondary)
                    }
                    IconButton(onClick = { showCalendar = true }) {
                        Icon(
                            Icons.Default.CalendarMonth, "Перейти к дате",
                            tint = PismoColors.TextSecondary,
                        )
                    }
                    IconButton(onClick = { showPins = true }) {
                        Icon(Icons.Default.PushPin, "Закреплённые", tint = PismoColors.TextSecondary)
                    }
                    IconButton(onClick = { startCall(context, targetId, title, isGroup, false) }) {
                        Icon(Icons.Default.Call, "Позвонить", tint = PismoColors.Green)
                    }
                    IconButton(onClick = { startCall(context, targetId, title, isGroup, true) }) {
                        Icon(Icons.Default.Videocam, "Видеозвонок", tint = PismoColors.Blurple)
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
                // Без imePadding клавиатура наезжает на строку ввода, и не
                // видно, что набираешь. adjustResize в манифесте сам по себе
                // Compose не помогает: он двигает окно, а не разметку.
                .imePadding()
        ) {
            if (readOnly) {
                Text(
                    if (iBlocked) "Вы заблокировали этого пользователя — входящие сообщения скрыты."
                    else "Этот пользователь заблокировал вас — входящие сообщения скрыты.",
                    color = PismoColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PismoColors.BgElevated)
                        .padding(12.dp),
                )
            }

            // fillMaxWidth обязателен: в Column ширину задаёт самый широкий
            // ребёнок, и на время загрузки им был сам индикатор — коробка
            // схлопывалась по нему, а align(Center) центрировал кружок
            // внутри него же, то есть выбрасывал к левому краю экрана.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (loading) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center),
                        color = PismoColors.Blurple,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 10.dp, vertical = 8.dp
                        ),
                    ) {
                        val visible = messages.filter { !readOnly || it.isMine }
                        itemsIndexedCompat(visible) { index, msg ->
                            val prev = visible.getOrNull(index - 1)
                            val showDate = prev == null ||
                                    formatDateSeparator(prev.createdAtMs) !=
                                    formatDateSeparator(msg.createdAtMs)
                            if (showDate) DateSeparator(formatDateSeparator(msg.createdAtMs))

                            MessageBubble(
                                msg = msg,
                                isGroup = isGroup,
                                reactions = reactions[msg.id].orEmpty(),
                                onReply = { replyTo = msg },
                                onEdit = { editing = msg; input = msg.text },
                                onDelete = {
                                    scope.launch {
                                        runCatching {
                                            ChatRepository.deleteMessage(scopeKind, msg.id, msg.fileName)
                                        }
                                        reload()
                                    }
                                },
                                onReact = { emoji ->
                                    scope.launch {
                                        ReactionsRepository.toggle(msg.id, scopeKind, emoji)
                                        reactions = ReactionsRepository
                                            .forMessages(messages.map { it.id }, scopeKind)
                                    }
                                },
                                scopeKind = scopeKind,
                            )
                        }
                    }
                }
            }

            // Панель ответа/редактирования над строкой ввода.
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
                            color = PismoColors.TextMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = { replyTo = null; editing = null; input = "" }) {
                        Icon(Icons.Default.Close, "Отменить", tint = PismoColors.TextMuted)
                    }
                }
            }

            // Во время записи строку ввода подменяет панель с таймером и
            // явной кнопкой отмены. Скрытый свайп-жест здесь не подходит:
            // запись включается тапом, а не удержанием, и «смахнуть» просто
            // нечего — отменить надо чем-то видимым.
            if (recording) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(PismoColors.BgDarkest)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(PismoColors.Red)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        formatDuration(recordSeconds.toLong()),
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Запись голосового · максимум ${VOICE_MAX_SECONDS / 60} мин",
                        color = PismoColors.TextMuted, fontSize = 12.sp,
                        modifier = Modifier.weight(1f), maxLines = 1,
                    )
                    androidx.compose.material3.TextButton(onClick = {
                        // cancel(), а не stop(): записанное надо выбросить,
                        // а не отправить.
                        recorder.cancel()
                        recording = false
                    }) { Text("Отмена", color = PismoColors.Red, fontSize = 13.sp) }
                    IconButton(onClick = { sendVoice(recorder.stop()) }) {
                        Icon(Icons.Default.Send, "Отправить", tint = PismoColors.Blurple)
                    }
                }
            }

            if (!readOnly && !recording) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(PismoColors.BgDarkest)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !sending) {
                        Icon(Icons.Default.AttachFile, "Прикрепить", tint = PismoColors.TextMuted)
                    }

                    IconButton(onClick = { showCircleRecorder = true }, enabled = !sending) {
                        Icon(Icons.Default.Videocam, "Видео-кружочек", tint = PismoColors.TextMuted)
                    }

                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("Сообщение…", color = PismoColors.TextMuted, fontSize = 14.sp)
                        },
                        maxLines = 5,
                        // ImeAction.None даёт на клавиатуре обычный Enter,
                        // который переводит строку. С действием по умолчанию
                        // там оказывается «Готово», и перенос набрать нечем.
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

                    if (input.isBlank() && editing == null) {
                        // Удержание — запись голосового, как кнопка 🎤 на ПК.
                        IconButton(
                            onClick = {
                                if (recording) sendVoice(recorder.stop())
                                else recording = recorder.start()
                            },
                        ) {
                            Icon(
                                if (recording) Icons.Default.Stop else Icons.Default.Mic,
                                if (recording) "Остановить запись" else "Голосовое",
                                tint = if (recording) PismoColors.Red else PismoColors.TextMuted,
                            )
                        }
                    } else {
                        IconButton(onClick = { send() }, enabled = !sending) {
                            Icon(Icons.Default.Send, "Отправить", tint = PismoColors.Blurple)
                        }
                    }
                }
            }
        }
    }

    if (showSearch) {
        ChatSearchDialog(
            scopeKind = scopeKind,
            targetId = targetId,
            onDismiss = { showSearch = false },
            onJump = { found ->
                showSearch = false
                // Прыгаем к сообщению, если оно есть на текущей странице;
                // иначе просто закрываем — подгрузка вглубь истории пока
                // не реализована.
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
                    val need = ChatRepository.countSince(scopeKind, targetId, dayStartMs)
                    if (need <= 0) {
                        jumpNote = "За выбранную дату и позже сообщений нет."
                        return@launch
                    }
                    // Тянем на пяток больше нужного — так же, как need + 5
                    // в JumpToDate: иначе целевое сообщение окажется первым
                    // в ленте, без единой строки контекста над ним.
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

    if (jumpNote.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { jumpNote = "" },
            containerColor = PismoColors.BgSidebar,
            title = { Text("PISMO", color = Color.White) },
            text = { Text(jumpNote, color = PismoColors.TextSecondary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { jumpNote = "" }) {
                    Text("Ок", color = PismoColors.Cyan)
                }
            },
        )
    }

    if (showPins) {
        PinnedMessagesDialog(
            scopeKind = scopeKind,
            targetId = targetId,
            onDismiss = { showPins = false },
        )
    }

    CircleRecorderHost(
        visible = showCircleRecorder,
        onDismiss = { showCircleRecorder = false },
        onRecorded = { data ->
            showCircleRecorder = false
            scope.launch {
                runCatching {
                    ChatRepository.sendMessage(
                        scope = scopeKind,
                        target = targetId,
                        text = "",
                        video = data,
                        replyToId = replyTo?.id ?: 0,
                    )
                    notifyPeers(isGroup, targetId)
                }
                replyTo = null
                reload(scrollToEnd = true)
            }
        },
    )
}

@Composable
private fun CircleRecorderHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onRecorded: (ByteArray) -> Unit,
) {
    if (visible) VideoCircleRecorderDialog(onDismiss = onDismiss, onRecorded = onRecorded)
}

/** Сообщаем собеседнику о новом сообщении — событие ws-сервера, как на ПК. */
private fun notifyPeers(isGroup: Boolean, target: Int) {
    if (isGroup) SignalingClient.send("new_message", 0, target, "group")
    else SignalingClient.send("new_message", target, 0, "direct")
}

private fun startCall(
    context: android.content.Context,
    targetId: Int,
    title: String,
    isGroup: Boolean,
    withVideo: Boolean,
) {
    val intent = Intent(context, com.pismo.messenger.ui.call.CallActivity::class.java).apply {
        putExtra(com.pismo.messenger.ui.call.CallActivity.EXTRA_PEER_ID, if (isGroup) -1 else targetId)
        putExtra(com.pismo.messenger.ui.call.CallActivity.EXTRA_GROUP_ID, if (isGroup) targetId else -1)
        putExtra(com.pismo.messenger.ui.call.CallActivity.EXTRA_PEER_NAME, title)
        putExtra(com.pismo.messenger.ui.call.CallActivity.EXTRA_WITH_VIDEO, withVideo)
        putExtra(com.pismo.messenger.ui.call.CallActivity.EXTRA_IS_CALLER, true)
    }
    context.startActivity(intent)
}

private fun queryFileName(context: android.content.Context, uri: android.net.Uri): String {
    var name = "file"
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx) ?: name
        }
    }
    return name
}

/** itemsIndexed для LazyColumn без импорта экспериментального API. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedCompat(
    items: List<ChatMessage>,
    itemContent: @Composable (Int, ChatMessage) -> Unit,
) {
    // Ключ по id сообщения обязателен. Без него Compose сопоставляет
    // элементы по порядковому номеру, и любое обновление списка (опрос идёт
    // каждые 2.5 с) считается заменой всех элементов сразу: состояние
    // пузырей сбрасывается, уже показанные картинки грузятся заново.
    items(items.size, key = { index -> items[index].id }) { index ->
        itemContent(index, items[index])
    }
}

/**
 * Размер файла по content-URI без его чтения.
 *
 * OpenableColumns.SIZE отдают не все провайдеры (облачные часто молчат),
 * поэтому при неудаче возвращаем 0 — «неизвестно», и решение принимается
 * уже по фактически прочитанным байтам.
 */
private fun fileSizeOf(context: android.content.Context, uri: android.net.Uri): Long =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else 0L
        } ?: 0L
    }.getOrDefault(0L)
