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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Gif
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.pismo.messenger.data.MessageMemory
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.ReactionSummary
import com.pismo.messenger.data.model.Presence
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.model.headerText
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.PresenceRepository
import com.pismo.messenger.data.repo.ReactionsRepository
import com.pismo.messenger.media.Sounds
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

/** Насколько расширяется страница за одну догрузку старых сообщений. */
private const val PAGE_STEP = 40

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

    // Лента берётся из памяти СИНХРОННО, до первого кадра. Раньше открытие
    // чата всегда начиналось с пустого экрана и кружка, даже если из него
    // вышли секунду назад: экран уходит из композиции целиком, и всё
    // грузилось заново. На ПК окно чата не уничтожается вовсе, поэтому там
    // такого нет.
    val remembered = remember(targetId, isGroup) {
        MessageMemory.peek(if (isGroup) Scope.GROUP else Scope.DM, targetId)
    }
    var messages by remember(targetId) {
        mutableStateOf(remembered?.first ?: emptyList())
    }
    var reactions by remember(targetId) {
        mutableStateOf(remembered?.second ?: emptyMap<Int, List<ReactionSummary>>())
    }
    var input by remember { mutableStateOf("") }
    // Кружок нужен, только когда показать нечего.
    var loading by remember(targetId) { mutableStateOf(remembered == null) }
    var sending by remember { mutableStateOf(false) }
    var lastCount by remember { mutableStateOf(0) }

    // Режимы ответа и редактирования — как панель над строкой ввода на ПК.
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }

    // Блокировки: чат переходит в режим «только чтение».
    var iBlocked by remember { mutableStateOf(false) }
    var theyBlocked by remember { mutableStateOf(false) }

    // Лента открывается СРАЗУ на последнем сообщении. Раньше стартовая
    // позиция была нулевой, и чат на мгновение (а с запомненной лентой —
    // надолго) показывал самое старое сообщение, пока прокрутка вниз не
    // догоняла. Начальный индекс задаётся до первой компоновки, поэтому
    // прыжка не видно вовсе.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (remembered?.first?.size ?: 1) - 1
    )
    val recorder = remember { WavRecorder(scope) }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableStateOf(0) }
    var showCircleRecorder by remember { mutableStateOf(false) }
    var showGifPicker by remember { mutableStateOf(false) }
    // Режим множественного выделения — порт «Выбрано: N» с ПК.
    var selectMode by remember(targetId) { mutableStateOf(false) }
    var selectedIds by remember(targetId) { mutableStateOf(setOf<Int>()) }
    var forwardBatch by remember(targetId) { mutableStateOf<List<ForwardItem>>(emptyList()) }
    // Полноэкранное видео живёт на уровне экрана — переживает и прокрутку,
    // и поворот, при которых пузырь уничтожается.
    var fullscreenVideo by remember(targetId) { mutableStateOf<FullscreenVideo?>(null) }
    var showPins by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    // Статус собеседника в шапке — «в сети» / «бездействует N» / «был в сети N».
    // Стартуем с того, что уже знает общая память: список чатов опросил
    // статусы секунду назад, и заново ждать ответа базы, показывая пустой
    // подзаголовок, незачем.
    var peerPresence by remember(targetId) {
        mutableStateOf(PresenceRepository.cached(targetId))
    }
    var jumpNote by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    // Прикреплённый, но ещё не отправленный файл — аналог «подготовки к
    // отправке» на ПК.
    var pending by remember(targetId) { mutableStateOf<PendingFile?>(null) }

    // Сколько сообщений тянуть. Переход к дате расширяет страницу ровно так
    // же, как _dmLimit на ПК: лента грузится с конца, и без расширения
    // прыжок в прошлый месяц упирался бы в незагруженную историю.
    var pageLimit by remember(targetId) { mutableStateOf(0) }
    // Догрузка старых сообщений порциями — как постраничная лента на ПК.
    // 0 в pageLimit означает «размер по умолчанию», поэтому первую порцию
    // считаем от него же.
    var loadingOlder by remember(targetId) { mutableStateOf(false) }
    var noMoreOlder by remember(targetId) { mutableStateOf(false) }

    /**
     * Кто сейчас печатает в этом чате и когда об этом сообщили. Порт
     * индикатора «печатает…» с ПК: собственный статус уходит по вебсокету
     * не чаще раза в две секунды, чужой держится на экране четыре.
     */
    var typingName by remember(targetId) { mutableStateOf("") }
    var typingAt by remember(targetId) { mutableLongStateOf(0L) }
    var typingSentAt by remember(targetId) { mutableLongStateOf(0L) }

    /**
     * Самое свежее ЧУЖОЕ сообщение, о котором уже отзвучали. −1 — ленту ещё
     * не видели: на первой загрузке звучать нечему, иначе открытие любого
     * чата встречало бы «плипом».
     */
    var lastHeardId by remember(targetId) { mutableIntStateOf(-1) }

    // force = «прокрути вниз обязательно». Обычный scrollToEnd уважает
    // положение пользователя в истории (чтобы чужое сообщение не выдёргивало
    // из середины), но СВОЮ отправку показать нужно всегда.
    suspend fun reload(scrollToEnd: Boolean = false, force: Boolean = false) {
        runCatching {
            val loaded = if (isGroup) {
                if (pageLimit > 0) ChatRepository.loadGroupMessages(targetId, pageLimit)
                else ChatRepository.loadGroupMessages(targetId)
            } else {
                if (pageLimit > 0) ChatRepository.loadDirectMessages(targetId, pageLimit)
                else ChatRepository.loadDirectMessages(targetId)
            }
            // Присваиваем ТОЛЬКО при реальном изменении: одинаковый по
            // содержимому список всё равно заставил бы перекомпоновать все
            // пузыри, а это заметный рывок посреди прокрутки.
            if (loaded.size != messages.size ||
                loaded.zip(messages).any { (a, b) -> a != b }
            ) {
                messages = loaded
            }
            lastCount = loaded.size

            // «Плип» на чужое сообщение в открытом чате — как на ПК. Системное
            // уведомление сюда не доходит: приложение на экране, и звук —
            // единственный признак, что пришло что-то новое, если человек
            // смотрит в другой конец переписки.
            val newestIncoming = loaded.filterNot { it.isMine }.maxOfOrNull { it.id } ?: -1
            if (lastHeardId >= 0 && newestIncoming > lastHeardId) Sounds.message()
            if (newestIncoming > lastHeardId) lastHeardId = newestIncoming

            reactions = ReactionsRepository.forMessages(loaded.map { it.id }, scopeKind)
            MessageMemory.put(scopeKind, targetId, loaded, reactions)
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
        // И НЕ ТРОГАЕМ ленту, пока её листает пользователь. scrollToItem
        // мгновенно обрывает инерцию, а опрос приходит раз в две с половиной
        // секунды — попасть им в разгон пальца проще простого. Отсюда и
        // «скролл может застопиться» на пути от старых сообщений к новым.
        val userScrolling = listState.isScrollInProgress
        if (messages.isNotEmpty() && !userScrolling && (force || (scrollToEnd && atBottom))) {
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
            reload(scrollToEnd = true, force = true)
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

    // Счётчик переподключений монотонный, поэтому «был ли это реконнект»
    // определяется сравнением с запомненным значением, а не проверкой > 0:
    // иначе после первого же обрыва связи кружок появлялся бы навсегда.
    var seenReconnects by remember(targetId) { mutableStateOf(reconnects) }

    LaunchedEffect(targetId, reconnects) {
        val isReconnect = reconnects != seenReconnects
        seenReconnects = reconnects
        // Кружок ставим, только если показать нечего: с запомненной лентой
        // он перекрыл бы уже нарисованные сообщения ради того же результата.
        // После переподключения — ставим всегда, лента могла устареть.
        if (remembered == null || isReconnect) loading = true
        reload(scrollToEnd = true)
    }

    // Поочерёдная догрузка старых сообщений — как постраничная лента на ПК.
    // Дотянув прокрутку до верха, пользователь получает следующую порцию, а
    // не всю историю разом: тянуть тысячу расшифровок ради одного взгляда
    // назад незачем.
    LaunchedEffect(targetId) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { first ->
            if (first > 2 || loading || loadingOlder || noMoreOlder) return@collect
            if (messages.isEmpty()) return@collect

            loadingOlder = true
            val before = messages.size
            pageLimit = (if (pageLimit > 0) pageLimit else before) + PAGE_STEP
            reload()
            // Ничего не добавилось — история кончилась, больше не дёргаем.
            if (messages.size <= before) noMoreOlder = true
            // Поправлять позицию руками НЕ НУЖНО и вредно: LazyColumn держит
            // якорь на первом видимом элементе по его ключу, поэтому порция,
            // легшая сверху, сама сдвигает содержимое, не трогая взгляд. А
            // ручной scrollToItem поверх этого обрывал бы инерцию — палец в
            // этот момент как раз ведёт ленту вверх.
            loadingOlder = false
        }
    }

    // Индикатор набора гаснет сам: сигнал «перестал печатать» протокол не
    // предусматривает ни здесь, ни на ПК — там ровно тот же таймер на 4 с.
    LaunchedEffect(typingAt) {
        if (typingAt == 0L) return@LaunchedEffect
        delay(4000)
        if (System.currentTimeMillis() - typingAt >= 4000) typingName = ""
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
        val listener: (String, Int, Int, String) -> Unit = { type, sender, session, payload ->
            if (type == "new_message") scope.launch { reload(scrollToEnd = true) }

            // «печатает…». Раскладка полей взята с ПК один в один, иначе
            // индикатор не совпадёт между телефоном и компьютером: в группе
            // адресат 0, а в sessionId едет id группы; в личке наоборот —
            // адресат это собеседник, в sessionId id отправителя.
            if (type == "typing" && sender != UserSession.effectiveId) {
                val mine = if (payload == "group") isGroup && session == targetId
                else !isGroup && (sender == targetId || session == targetId)
                if (mine) {
                    typingName = messages.lastOrNull { it.senderId == sender }?.senderName
                        ?.takeIf { it.isNotBlank() } ?: "Собеседник"
                    typingAt = System.currentTimeMillis()
                }
            }
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

                val isImage = com.pismo.messenger.core.isImageName(name) ||
                        com.pismo.messenger.core.isGifName(name)

                // Файл НЕ отправляем сразу. На ПК вложение сначала
                // прикрепляется, к нему можно дописать текст, и уходит всё
                // одним сообщением; здесь же получалось два — сначала файл,
                // потом отдельно подпись.
                pending = PendingFile(bytes = bytes, fileName = name, isImage = isImage)
            }
        }
    }

    fun send() {
        val text = input.trim()
        val attach = pending
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
        // Без вложения пустой текст отправлять нечего; с вложением —
        // наоборот, подпись необязательна.
        if ((text.isEmpty() && attach == null) || sending) return

        sending = true
        scope.launch {
            runCatching {
                ChatRepository.sendMessage(
                    scope = scopeKind,
                    target = targetId,
                    text = text,
                    image = attach?.takeIf { it.isImage }?.bytes,
                    file = attach?.takeIf { !it.isImage }?.bytes,
                    // Имя файла — ТОЛЬКО для не-картинок. Пока оно писалось
                    // и для изображений, собеседник на ПК видел под фото
                    // вторую строку — карточку «нажмите для загрузки», хотя
                    // это одно и то же вложение. ПК при отправке картинки
                    // тоже передаёт file_name как null.
                    fileName = attach?.takeIf { !it.isImage }?.fileName,
                    replyToId = replyTo?.id ?: 0,
                )
                notifyPeers(isGroup, targetId)
            }
            input = ""
            replyTo = null
            pending = null
            sending = false
            reload(scrollToEnd = true, force = true)
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
                                color = PismoColors.TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            // Пока собеседник печатает, вторая строка шапки
                            // занята им — как на ПК. Статус присутствия
                            // никуда не денется через четыре секунды, а
                            // «печатает…» ценно ровно сейчас.
                            if (typingName.isNotEmpty()) {
                                Text(
                                    if (isGroup) "✍ $typingName печатает…" else "✍ печатает…",
                                    color = PismoColors.Green,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
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
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад", tint = PismoColors.TextSecondary)
                    }
                },
                actions = {
                    // Поиск, дата и закреплённые уехали в меню: пять кнопок
                    // в ряд не оставляли шапке места, и имя со статусом
                    // обрезались на середине слова.
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "Ещё", tint = PismoColors.TextSecondary)
                        }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Поиск по переписке") },
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
            if (selectMode) {
                SelectionBar(
                    count = selectedIds.size,
                    // Чужие сообщения удаляет только админ — то же правило,
                    // что и в меню одиночного сообщения.
                    canDelete = com.pismo.messenger.core.UserSession.isAdmin ||
                        messages.filter { it.id in selectedIds }.all { it.isMine },
                    onCancel = { selectMode = false; selectedIds = emptySet() },
                    onForward = {
                        forwardBatch = messages
                            .filter { it.id in selectedIds }
                            .map { ForwardItem(it.id, it.text, it.senderName) }
                    },
                    onDelete = {
                        val ids = selectedIds.toList()
                        selectMode = false
                        selectedIds = emptySet()
                        scope.launch {
                            ids.forEach { id ->
                                val m = messages.firstOrNull { it.id == id }
                                runCatching {
                                    ChatRepository.deleteMessage(scopeKind, id, m?.fileName)
                                }
                            }
                            reload()
                        }
                    },
                )
            }

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
                        // Полоска сверху, пока едет следующая порция истории.
                        if (loadingOlder) {
                            item {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        Modifier.size(20.dp),
                                        color = PismoColors.Blurple,
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }

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
                                onOpenVideo = { file, name, id ->
                                    fullscreenVideo = FullscreenVideo(file, name, id)
                                },
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
                                    // Сняли последнее — режим больше не нужен,
                                    // как выход из выделения на ПК.
                                    if (selectedIds.isEmpty()) selectMode = false
                                },
                            )
                        }
                    }
                }
            }

            // Панель прикреплённого файла: он ждёт отправки вместе с текстом.
            pending?.let { att ->
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
                            .background(PismoColors.Green)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (att.isImage) "Изображение прикреплено" else "Файл прикреплён",
                            color = PismoColors.Green,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            att.fileName + " · " + formatBytesShort(att.bytes.size.toLong()),
                            color = PismoColors.TextMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = { pending = null }) {
                        Icon(Icons.Default.Close, "Убрать вложение", tint = PismoColors.TextMuted)
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
                            .clip(CircleShape)
                            .background(PismoColors.Red)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        formatDuration(recordSeconds.toLong()),
                        color = PismoColors.TextPrimary, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(12.dp))
                    // «максимум 3» без единицы читалось как угодно — от секунд
                    // до часов. Пишем единицу прямо в строке и не даём ей
                    // ужиматься: weight(1f) с maxLines обрезал хвост.
                    Text(
                        "из ${VOICE_MAX_SECONDS / 60} мин",
                        color = PismoColors.TextMuted, fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
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

                    // Кнопка GIF — как на ПК (и как в Discord). Гифка
                    // прикрепляется вложением, а не улетает сразу: к ней
                    // можно дописать подпись.
                    IconButton(onClick = { showGifPicker = true }, enabled = !sending) {
                        Icon(Icons.Default.Gif, "Гифки", tint = PismoColors.TextMuted)
                    }

                    TextField(
                        value = input,
                        onValueChange = {
                            input = it
                            // Не чаще раза в две секунды и не при
                            // редактировании — как на ПК: иначе каждая буква
                            // становилась бы пакетом в сокет.
                            val now = System.currentTimeMillis()
                            if (it.isNotEmpty() && editing == null && now - typingSentAt > 2000) {
                                typingSentAt = now
                                if (isGroup) {
                                    SignalingClient.send("typing", 0, targetId, "group")
                                } else {
                                    SignalingClient.send(
                                        "typing", targetId, UserSession.effectiveId, "direct"
                                    )
                                }
                            }
                        },
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

                    if (input.isBlank() && editing == null && pending == null) {
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
            title = { Text("PISMO", color = PismoColors.TextPrimary) },
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

    FullscreenVideoHost(fullscreenVideo) { fullscreenVideo = null }

    if (forwardBatch.isNotEmpty()) {
        ForwardDialog(
            srcScope = scopeKind,
            items = forwardBatch,
            onDismiss = { forwardBatch = emptyList() },
            onDone = {
                forwardBatch = emptyList()
                selectMode = false
                selectedIds = emptySet()
            },
        )
    }

    if (showGifPicker) {
        GifPickerDialog(
            onPicked = { bytes ->
                pending = PendingFile(bytes = bytes, fileName = "giphy.gif", isImage = true)
            },
            onDismiss = { showGifPicker = false },
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

/**
 * Прикреплённый, но ещё не отправленный файл.
 *
 * На ПК вложение сначала «готовится к отправке», к нему дописывается текст,
 * и всё уходит ОДНИМ сообщением. Здесь файл улетал сразу при выборе, а
 * подпись потом отдельной строкой — получалось два сообщения вместо одного.
 */
private data class PendingFile(
    val bytes: ByteArray,
    val fileName: String,
    val isImage: Boolean,
) {
    // ByteArray сравнивается по ссылке, поэтому equals/hashCode пишем руками:
    // без них Compose считал бы одинаковые вложения разными и лишний раз
    // пересобирал панель.
    override fun equals(other: Any?): Boolean =
        other is PendingFile && fileName == other.fileName &&
                isImage == other.isImage && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        31 * (31 * bytes.contentHashCode() + fileName.hashCode()) + isImage.hashCode()
}

/** «482 КБ» / «12,4 МБ» — для подписи под прикреплённым файлом. */
internal fun formatBytesShort(bytes: Long): String = when {
    bytes < 1024L -> "$bytes Б"
    bytes < 1024L * 1024L -> "${bytes / 1024L} КБ"
    else -> String.format(java.util.Locale.getDefault(), "%.1f МБ", bytes / 1024.0 / 1024.0)
}
