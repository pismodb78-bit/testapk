package com.pismo.messenger.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pismo.messenger.core.MediaKinds
import com.pismo.messenger.core.avatarColor
import com.pismo.messenger.core.ellipsize
import com.pismo.messenger.core.fileBadge
import com.pismo.messenger.core.fileColor
import com.pismo.messenger.core.formatTime
import com.pismo.messenger.data.MediaCache
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.ReactionSummary
import com.pismo.messenger.data.model.ReplyQuote
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.PinsRepository
import com.pismo.messenger.data.repo.ReactionsRepository
import com.pismo.messenger.media.MediaSaver
import com.pismo.messenger.media.WavPlayer
import com.pismo.messenger.ui.components.AnimatedImage
import com.pismo.messenger.ui.components.FileBadge
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.launch

/**
 * Пузырь сообщения — Android-аналог BuildBubble из MainForm.cs:
 * цитата ответа, картинка/GIF, голосовое, карточка файла, текст,
 * время с пометкой «изменено», реакции и меню по долгому нажатию.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    msg: ChatMessage,
    isGroup: Boolean,
    reactions: List<ReactionSummary>,
    scopeKind: Scope,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReact: (String) -> Unit,
    // Множественное выделение — порт режима «Выбрано: N» с ПК. Пока он
    // выключен, всё работает ровно как раньше.
    selectMode: Boolean = false,
    selected: Boolean = false,
    /**
     * Меня упомянули в этом сообщении. На ПК такой пузырь красится
     * зеленоватым (47,68,55) — заметить обращение в потоке канала иначе
     * почти невозможно.
     */
    mentioned: Boolean = false,
    onEnterSelect: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
    /**
     * Открыть видео во весь экран. Диалог принадлежит ЭКРАНУ, а не пузырю:
     * пузырь уничтожается, стоит сообщению уйти за край видимой области, и
     * вместе с ним закрывался бы просмотр.
     */
    onOpenVideo: (java.io.File, String, Int) -> Unit = { _, _, _ -> },
    /**
     * Ключ переписки для полосы передач. Скачивание идёт вне экрана, и по
     * этому ключу полоса показывается там, откуда её запустили.
     */
    transferKey: String = "",
    /** Нажатие на цитату — прыжок к тому сообщению, на которое отвечали. */
    onQuoteClick: (Int) -> Unit = {},
    /**
     * Мигнуть — сюда только что прыгнули по цитате. Без этого прыжок было
     * не заметить: лента просто оказывалась в другом месте, и какое
     * сообщение искомое, приходилось угадывать.
     */
    highlighted: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    /** Открытое окно проигрывателя для ссылки на видео. */
    var videoLink by remember { mutableStateOf<com.pismo.messenger.core.VideoLinks.Playable?>(null) }
    val isMine = msg.isMine
    var menuOpen by remember { mutableStateOf(false) }
    // Начальное значение берём из памяти СИНХРОННО. Пузырь, ушедший за край
    // экрана, LazyColumn уничтожает вместе с этим состоянием, и на обратной
    // прокрутке всё грузилось заново — выглядело как перезагрузка чата,
    // из которого ты даже не выходил. Готовое из памяти показывается сразу,
    // а LaunchedEffect ниже дочитывает только то, чего в ней нет.
    videoLink?.let { LinkVideoDialog(it) { videoLink = null } }

    var quote by remember(msg.id) { mutableStateOf(QuoteMemory.get(msg.replyToId, scopeKind)) }
    var image by remember(msg.id) { mutableStateOf(MediaCache.peek(msg.id, "img")) }
    var audio by remember(msg.id) { mutableStateOf(MediaCache.peek(msg.id, "audio")) }
    var viewerBytes by remember(msg.id) { mutableStateOf<ByteArray?>(null) }
    var showForward by remember(msg.id) { mutableStateOf(false) }
    var fileStatus by remember(msg.id) { mutableStateOf("") }
    var showEmojiPicker by remember(msg.id) { mutableStateOf(false) }
    var showHistory by remember(msg.id) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(msg.id) {
        if (quote == null && msg.replyToId > 0 && !msg.isDeleted) {
            quote = runCatching { ChatRepository.loadReplyQuote(msg.replyToId, scopeKind) }
                .getOrNull()
                ?.also { QuoteMemory.put(msg.replyToId, scopeKind, it) }
        }
        if (image == null && msg.hasImage) {
            image = runCatching { ChatRepository.loadImage(msg.id, scopeKind, msg.fileName) }.getOrNull()
        }
        if (audio == null && msg.hasAudio) {
            audio = runCatching { ChatRepository.loadAudio(msg.id, scopeKind) }.getOrNull()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (isMine && !selectMode) Arrangement.End
        else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Кружок выделения — слева от пузыря, как ○/✔ на ПК.
        if (selectMode) {
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .size(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (selected) PismoColors.Green else Color(0x33000000))
                    .clickable { onToggleSelect() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (selected) "✔" else "",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
            Box {
                // Вспышка гаснет сама: появляется быстро, уходит плавно, —
                // так глаз успевает поймать, куда прыгнули, и подсветка не
                // остаётся висеть.
                val flash by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (highlighted) 1f else 0f,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = if (highlighted) 150 else 900
                    ),
                    label = "flash",
                )
                Column(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .drawWithContent {
                            drawContent()
                            if (flash > 0f) {
                                drawRect(PismoColors.Cyan.copy(alpha = 0.30f * flash))
                            }
                        }
                        .background(
                            when {
                                // Выбранный пузырь подсвечивается, как на ПК
                                // через ControlPaint.Light — только там это
                                // осветление, а здесь наложение белым: на
                                // светлой теме осветлять уже некуда.
                                selected -> if (isMine) PismoColors.Blurple else PismoColors.BgHover
                                isMine -> PismoColors.Blurple
                                // Тот же зеленоватый, что на ПК.
                                mentioned -> PismoColors.MentionBubble
                                else -> PismoColors.BgBubbleOther
                            }
                        )
                        // canFocus = false ОБЯЗАН стоять ПЕРЕД clickable.
                        // combinedClickable делает пузырь фокусируемым, а
                        // Compose, получив фокус, подтягивает элемент в
                        // видимую область целиком. У короткого текстового
                        // пузыря это незаметно, а у высокого с видео — прыжок
                        // ленты вниз на каждое нажатие. Клавиатурная
                        // навигация по пузырям чата нам не нужна.
                        .focusProperties { canFocus = false }
                        .combinedClickable(
                            // В режиме выделения нажатие отмечает сообщение,
                            // а не открывает меню: так же ведёт себя ПК.
                            onClick = { if (selectMode) onToggleSelect() },
                            onLongClick = { if (!selectMode) menuOpen = true },
                        )
                        .padding(10.dp),
                ) {
                    if (!isMine && isGroup) {
                        Text(
                            msg.senderName,
                            color = avatarColor(msg.senderId),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(3.dp))
                    }

                    if (msg.isDeleted) {
                        Text(
                            "🚫 Сообщение удалено",
                            color = PismoColors.TextMuted,
                            fontSize = 14.sp,
                            fontStyle = FontStyle.Italic,
                        )
                    } else {
                        quote?.let { q ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0x22000000))
                                    // Нажатие ведёт к исходному сообщению:
                                    // раньше цитата была просто картинкой, и
                                    // найти, на что отвечают, приходилось
                                    // прокруткой вручную.
                                    .clickable { onQuoteClick(q.messageId) }
                                    .padding(6.dp)
                            ) {
                                Box(
                                    Modifier
                                        .width(3.dp)
                                        .height(30.dp)
                                        .background(PismoColors.Cyan)
                                )
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(
                                        q.sender,
                                        color = PismoColors.Cyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        q.text.ellipsize(50),
                                        color = PismoColors.TextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        image?.let { bytes ->
                            val mod = Modifier
                                .widthIn(max = 260.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewerBytes = bytes }
                            // GIF узнаём по содержимому, как IsGif на ПК:
                            // имя у картинок больше не хранится, да и из
                            // буфера обмена оно и не приходило.
                            if (MediaKinds.isGif(bytes)) {
                                AnimatedImage(bytes, contentDescription = null, modifier = mod)
                            } else {
                                AsyncImage(
                                    model = bytes,
                                    contentDescription = null,
                                    modifier = mod,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        if (msg.hasAudio) {
                            VoiceRow(msg.id, audio, isMine)
                            Spacer(Modifier.height(6.dp))
                        }

                        if (msg.hasVideo) {
                            VideoCirclePlayerBubble(msg.id, scopeKind)
                            Spacer(Modifier.height(6.dp))
                        }

                        // Видео и музыка играют прямо в пузыре — как на ПК,
                        // где для них создаётся InlineVideoPlayer, а не
                        // карточка «нажмите для загрузки».
                        val playableVideo = msg.hasFile && MediaKinds.isVideo(msg.fileName)
                        val playableAudio = msg.hasFile && MediaKinds.isAudio(msg.fileName)
                        if (playableVideo) {
                            InlineVideoBubble(
                                msgId = msg.id,
                                scopeKind = scopeKind,
                                fileName = msg.fileName!!,
                                isMine = isMine,
                                onFullscreen = onOpenVideo,
                            )
                            Spacer(Modifier.height(6.dp))
                        } else if (playableAudio) {
                            InlineAudioBubble(msg.id, scopeKind, msg.fileName!!, isMine)
                            Spacer(Modifier.height(6.dp))
                        }

                        // Карточку файла показываем ТОЛЬКО когда предпросмотра
                        // нет. Если картинка уже нарисована в пузыре, вторая
                        // строка «Нажмите для загрузки» — дубль того же
                        // вложения: на ПК её там нет, там просто изображение.
                        val previewShown = image != null || msg.hasAudio || msg.hasVideo ||
                            playableVideo || playableAudio
                        if (!msg.fileName.isNullOrBlank() && msg.hasFile && !previewShown) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x22000000))
                                    .clickable {
                                        // Скачивание идёт вне экрана: выход из
                                        // чата раньше обрывал его на середине.
                                        // Байты кладутся в кеш, поэтому даже
                                        // если уйти, вернувшись файл открывается
                                        // сразу. Открываем сами только когда
                                        // экран ещё жив — иначе документ
                                        // выпрыгнул бы поверх чужой переписки.
                                        fileStatus = "Загрузка с сервера…"
                                        com.pismo.messenger.data.Transfers.download(
                                            where = transferKey,
                                            msgId = msg.id,
                                            scopeKind = scopeKind,
                                            fileName = msg.fileName,
                                        ) { data ->
                                            fileStatus =
                                                if (saveAndOpenFile(context, msg.fileName!!, data)) ""
                                                else "Нет приложения для этого типа файла"
                                        }
                                    }
                                    .padding(8.dp),
                            ) {
                                FileBadge(fileBadge(msg.fileName), fileColor(msg.fileName), 36.dp)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f, fill = false)) {
                                    Text(
                                        msg.fileName,
                                        color = PismoColors.onBubble(isMine),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    Text(
                                        fileStatus.ifBlank { "Нажмите, чтобы открыть" },
                                        color = PismoColors.TextMuted,
                                        fontSize = 11.sp,
                                    )
                                }
                                // Отдельная кнопка «скачать». Раньше у обычных
                                // файлов её не было вовсе: нажатие на карточку
                                // клало файл во временную папку приложения и
                                // отдавало стороннему приложению — то есть
                                // «открыть», а не «сохранить». Файл после этого
                                // жил в кеше, который система вправе почистить,
                                // и найти его человек не мог нигде.
                                //
                                // Своё вложение скачивается так же, как чужое:
                                // отправить документ с телефона и потом не суметь
                                // его оттуда достать — странность, которой на ПК
                                // нет.
                                Spacer(Modifier.width(4.dp))
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            fileStatus = "Сохранение…"
                                            val data = runCatching {
                                                ChatRepository.loadFile(msg.id, scopeKind, msg.fileName)
                                            }.getOrNull()
                                            fileStatus = when {
                                                data == null -> "Не удалось загрузить"
                                                MediaSaver.saveFile(context, msg.fileName, data) ->
                                                    "Сохранено в загрузки"
                                                else -> "Не удалось сохранить"
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        "Сохранить в загрузки",
                                        tint = PismoColors.onBubble(isMine),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        if (msg.text.isNotBlank()) {
                            val textLinks = remember(msg.text) {
                                com.pismo.messenger.core.Links.find(msg.text)
                            }
                            var layout by remember(msg.text) {
                                mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null)
                            }
                            var linkMenu by remember(msg.id) { mutableStateOf<String?>(null) }
                            /** Ссылка под пальцем — её подсвечиваем. */
                            var pressedLink by remember(msg.text) {
                                mutableStateOf<IntRange?>(null)
                            }

                            Box {
                                Text(
                                    highlightMentions(msg.text, isMine, pressedLink),
                                    color = PismoColors.onBubble(isMine),
                                    fontSize = 15.sp,
                                    onTextLayout = { layout = it },
                                    // Оба жеста по ссылке ведём САМИ.
                                    //
                                    // Встроенная разметка ссылок имеет свой
                                    // обработчик, и он живёт ВНУТРИ текста —
                                    // получает событие раньше любого нашего.
                                    // Отсюда и было: держишь палец, отпускаешь —
                                    // меню открылось И браузер уехал. Снаружи
                                    // это не гасится, поэтому обработчика там
                                    // больше нет, ссылка просто покрашена.
                                    //
                                    // Событие забираем ТОЛЬКО когда палец
                                    // опустился на сам адрес: иначе обычное
                                    // нажатие и долгое нажатие по остальному
                                    // тексту перестали бы доходить до пузыря.
                                    modifier = if (textLinks.isEmpty()) Modifier
                                    else Modifier.pointerInput(msg.text) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val off = layout?.getOffsetForPosition(down.position)
                                            val hit = off?.let { o ->
                                                textLinks.firstOrNull { o in it.range }
                                            } ?: return@awaitEachGesture

                                            down.consume()
                                            pressedLink = hit.range

                                            var released: androidx.compose.ui.input.pointer.PointerInputChange? = null
                                            val timedOut = withTimeoutOrNull(
                                                viewConfiguration.longPressTimeoutMillis
                                            ) {
                                                released = waitForUpOrCancellation()
                                            } == null

                                            when {
                                                // Держат — меню, и доедаем жест
                                                // до отпускания, гася события.
                                                timedOut -> {
                                                    linkMenu = hit.url
                                                    while (true) {
                                                        val ev = awaitPointerEvent()
                                                        ev.changes.forEach { it.consume() }
                                                        if (ev.changes.none { it.pressed }) break
                                                    }
                                                }
                                                // Отпустили быстро — обычное нажатие.
                                                released != null -> {
                                                    released?.consume()
                                                    val play = com.pismo.messenger.core
                                                        .VideoLinks.of(hit.url)
                                                    if (play != null) videoLink = play
                                                    else com.pismo.messenger.core
                                                        .LinkOpener.open(context, hit.url)
                                                }
                                                // Жест отменили (потянули ленту) —
                                                // не делаем ничего.
                                            }
                                            pressedLink = null
                                        }
                                    },
                                )

                                DropdownMenu(
                                    expanded = linkMenu != null,
                                    onDismissRequest = { linkMenu = null },
                                    modifier = Modifier.background(PismoColors.BgElevated),
                                ) {
                                    val url = linkMenu
                                    val playable = url?.let {
                                        com.pismo.messenger.core.VideoLinks.of(it)
                                    }
                                    if (playable != null) {
                                        DropdownMenuItem(
                                            text = { Text("▶  Смотреть здесь") },
                                            onClick = { videoLink = playable; linkMenu = null },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("🔗  Открыть в приложении") },
                                        onClick = {
                                            url?.let {
                                                com.pismo.messenger.core.LinkOpener.open(context, it)
                                            }
                                            linkMenu = null
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("📋  Копировать ссылку") },
                                        onClick = {
                                            url?.let { clipboard.setText(AnnotatedString(it)) }
                                            linkMenu = null
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("↗  Поделиться") },
                                        onClick = {
                                            url?.let { shareText(context, it) }
                                            linkMenu = null
                                        },
                                    )
                                }
                            }
                            // Строка источника под текстом: по одному адресу
                            // в самом сообщении не всегда понятно, куда он
                            // ведёт, — длинные ссылки обрезаются, а короткие
                            // вроде t.me/xxx ничего не говорят.
                            LinkSourceRows(msg.text)
                        }
                    }

                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (msg.isEdited) "${formatTime(msg.createdAtMs)} · изменено"
                            else formatTime(msg.createdAtMs),
                            color = if (isMine) Color(0xFFB9BEFF) else PismoColors.TextMuted,
                            fontSize = 10.sp,
                        )

                        // Галочки — только у СВОИХ сообщений и только в личной
                        // переписке. У группы и канала получателей много, и
                        // «прочитано» одним значком там значило бы неправду:
                        // на ПК их по той же причине рисуют лишь для ЛС.
                        if (isMine && scopeKind == Scope.DM) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "✓✓",
                                // Синие — прочитано, серые — доставлено.
                                // Цвета взяты с ПК.
                                color = if (msg.isRead) Color(0xFF58AAFF) else Color(0xFF96A0B4),
                                fontSize = 10.sp,
                            )
                        }
                    }
                }

                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        ReactionsRepository.QUICK.take(6).forEach { emoji ->
                            Text(
                                emoji,
                                fontSize = 20.sp,
                                modifier = Modifier
                                    .clickable { onReact(emoji); menuOpen = false }
                                    .padding(6.dp),
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("↩  Ответить") },
                        onClick = { onReply(); menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("↪  Переслать") },
                        onClick = { showForward = true; menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("☑  Выделить") },
                        onClick = { menuOpen = false; onEnterSelect() },
                    )
                    DropdownMenuItem(
                        text = { Text("😀  Ещё реакции…") },
                        onClick = { showEmojiPicker = true; menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("📌  Закрепить / открепить") },
                        onClick = {
                            scope.launch { PinsRepository.toggle(msg.id, scopeKind) }
                            menuOpen = false
                        },
                    )
                    if (msg.text.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("📋  Копировать текст") },
                            onClick = {
                                // Пункт был, а обработчика у него не было: меню
                                // просто закрывалось, и со стороны это выглядело
                                // как «копирование не работает».
                                clipboard.setText(AnnotatedString(msg.text))
                                menuOpen = false
                            },
                        )
                    }
                    if (msg.isEdited) {
                        DropdownMenuItem(
                            text = { Text("🕓  История изменений") },
                            onClick = { showHistory = true; menuOpen = false },
                        )
                    }
                    if (isMine && msg.text.isNotBlank() && !msg.isDeleted) {
                        DropdownMenuItem(
                            text = { Text("✏  Редактировать") },
                            onClick = { onEdit(); menuOpen = false },
                        )
                    }
                    if (isMine || com.pismo.messenger.core.UserSession.isAdmin) {
                        DropdownMenuItem(
                            text = { Text("🗑  Удалить", color = PismoColors.Red) },
                            onClick = { onDelete(); menuOpen = false },
                        )
                    }
                }
            }

            if (reactions.isNotEmpty()) {
                Row(Modifier.padding(top = 3.dp)) {
                    reactions.forEach { r ->
                        Row(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (r.mine) PismoColors.Blurple.copy(alpha = 0.35f)
                                    else PismoColors.BgElevated
                                )
                                .clickable { onReact(r.emoji) }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(r.emoji, fontSize = 13.sp)
                            Spacer(Modifier.width(3.dp))
                            Text(
                                r.count.toString(),
                                color = PismoColors.TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }

    viewerBytes?.let { bytes ->
        FullscreenImageViewer(
            bytes = bytes,
            onDismiss = { viewerBytes = null },
            // У картинок имени в базе больше нет — собираем осмысленное из
            // номера сообщения, чтобы в галерее не лежали «pismo_17…».
            fileName = msg.fileName
                ?: ("pismo_${msg.id}." + if (MediaKinds.isGif(bytes)) "gif" else "jpg"),
        )
    }

    if (showEmojiPicker) {
        EmojiPickerDialog(
            onDismiss = { showEmojiPicker = false },
            onPick = { emoji -> onReact(emoji); showEmojiPicker = false },
        )
    }

    if (showHistory) {
        EditHistoryDialog(
            messageId = msg.id,
            scopeKind = scopeKind,
            onDismiss = { showHistory = false },
        )
    }

    if (showForward) {
        ForwardDialog(
            srcScope = scopeKind,
            srcMessageId = msg.id,
            srcText = msg.text,
            srcSender = msg.senderName,
            onDismiss = { showForward = false },
            onDone = { showForward = false },
        )
    }
}

/**
 * Голосовое сообщение.
 *
 * Состояние читается из потоков плеера, а не из его полей: обычное поле
 * Compose не отслеживает, и кнопка не менялась на «стоп» при запуске и не
 * возвращалась по окончании — звук шёл, а пузырь выглядел нетронутым.
 *
 * Полоса с перемоткой — сверх того, что есть на ПК (там просто
 * «▶ Голосовое / ⏹ Остановить»). На телефоне голосовые слушают на ходу и
 * переспрашивают середину, а переслушивать минуту ради десяти секунд —
 * не вариант.
 */
@Composable
private fun VoiceRow(msgId: Int, audio: ByteArray?, isMine: Boolean) {
    val playingId by WavPlayer.playingId.collectAsState()
    val positionMs by WavPlayer.positionMs.collectAsState()
    val livePlayerDuration by WavPlayer.durationMs.collectAsState()

    val playing = playingId == msgId

    // Пока сообщение не играет, длина берётся из шапки самой записи: иначе
    // подпись появлялась бы только после первого запуска.
    val headerSeconds = remember(audio) { audio?.let { WavPlayer.durationSecondsOf(it) } ?: 0 }
    val durationMs = if (playing && livePlayerDuration > 0) livePlayerDuration
    else headerSeconds * 1000

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x22000000))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Icon(
            if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = if (playing) "Остановить" else "Воспроизвести",
            tint = PismoColors.onBubble(isMine),
            modifier = Modifier
                .size(22.dp)
                .clickable { audio?.let { WavPlayer.toggle(msgId, it) } },
        )
        Spacer(Modifier.width(8.dp))

        Column(Modifier.weight(1f, fill = false)) {
            if (audio == null) {
                Text(
                    "Загрузка…",
                    color = PismoColors.onBubble(isMine),
                    fontSize = 13.sp,
                )
            } else {
                // Полоса живёт только у играющего сообщения: у остальных она
                // была бы вечным нулём и лишней строкой в ленте.
                if (playing && durationMs > 0) {
                    Slider(
                        value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                        onValueChange = { WavPlayer.seekTo(msgId, it.toInt()) },
                        valueRange = 0f..durationMs.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = PismoColors.onBubble(isMine),
                            activeTrackColor = PismoColors.onBubble(isMine),
                        ),
                        modifier = Modifier.height(18.dp).width(150.dp),
                    )
                }
                Text(
                    if (playing) "${voiceClock(positionMs)} / ${voiceClock(durationMs)}"
                    else "Голосовое · $headerSeconds с",
                    color = PismoColors.onBubble(isMine),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/** мм:сс для подписи голосового. */
private fun voiceClock(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}


/**
 * Подсвечивает @упоминания внутри текста сообщения.
 *
 * Правило выделения — то же, что у разбора: «@» и всё до пробела или
 * следующей собаки. Отдельного цвета для «упомянули именно меня» здесь нет
 * намеренно: это видно по фону всего пузыря, как и на ПК, а раскрашивать
 * ещё и слово значило бы сказать одно и то же дважды.
 */
@Composable
/**
 * Размечает текст сообщения: упоминания и ссылки.
 *
 * Ссылки размечаются ОБЫЧНЫМ стилем, а не встроенной разметкой ссылок.
 * У встроенной свой обработчик нажатий, и живёт он ВНУТРИ текста — то есть
 * получает событие раньше любого нашего. Погасить его снаружи нельзя:
 * долгое нажатие открывало меню и одновременно уводило в браузер. Поэтому
 * оба жеста ведём сами (см. pointerInput выше), а отсюда нужен только вид.
 *
 * [pressed] — кусок, на котором сейчас палец: его подсвечиваем.
 *
 * Оба вида разметки идут одним проходом по общему списку кусков: если
 * размечать их по очереди, второй проход не знал бы о смещениях первого.
 */
private fun highlightMentions(
    text: String,
    isMine: Boolean,
    pressed: IntRange? = null,
): AnnotatedString {
    val mentions = com.pismo.messenger.core.Mentions.spans(text)
    val links = com.pismo.messenger.core.Links.find(text)
    if (mentions.isEmpty() && links.isEmpty()) return AnnotatedString(text)

    val accent = if (isMine) Color.White else PismoColors.Cyan
    val linkColor = if (isMine) Color.White else PismoColors.Cyan

    // Куски, отсортированные по началу. Ссылка старше упоминания: адрес вида
    // https://site/@user иначе распался бы на части.
    data class Piece(val range: IntRange, val isLink: Boolean)
    val pieces = (links.map { Piece(it.range, true) } + mentions.map { Piece(it, false) })
        .sortedBy { it.range.first }
        .fold(mutableListOf<Piece>()) { acc, p ->
            if (acc.isEmpty() || p.range.first > acc.last().range.last) acc.add(p)
            acc
        }

    return buildAnnotatedString {
        var pos = 0
        for (p in pieces) {
            if (p.range.first > pos) append(text.substring(pos, p.range.first))
            val chunk = text.substring(p.range.first, p.range.last + 1)
            val style = if (p.isLink) SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
                background = if (p.range == pressed) linkColor.copy(alpha = 0.25f)
                             else Color.Unspecified,
            ) else SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)
            withStyle(style) { append(chunk) }
            pos = p.range.last + 1
        }
        if (pos < text.length) append(text.substring(pos))
    }
}

/**
 * Карточка ссылки под текстом сообщения: значок и название службы, а если
 * сайт отдал разметку — ещё заголовок, описание и картинку.
 *
 * Значок и название есть всегда: они рисуются на месте, без всякой сети.
 * Остальное подтягивается с самого сайта и потому может не появиться —
 * страница без разметки, нет связи, показ выключен в настройках.
 */
@Composable
private fun LinkSourceRows(text: String) {
    val links = remember(text) { com.pismo.messenger.core.Links.find(text) }
    if (links.isEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    /** Открытое окно проигрывателя для ссылки на видео. */
    var videoLink by remember { mutableStateOf<com.pismo.messenger.core.VideoLinks.Playable?>(null) }

    videoLink?.let { LinkVideoDialog(it) { videoLink = null } }

    // Больше трёх карточек под сообщением — уже полотно; остальные ссылки
    // по-прежнему нажимаются прямо в тексте.
    links.distinctBy { com.pismo.messenger.core.LinkSources.domainOf(it.url) }
        .take(3)
        .forEach { link ->
            val src = com.pismo.messenger.core.LinkSources.of(link.url)
            val playable = remember(link.url) { com.pismo.messenger.core.VideoLinks.of(link.url) }
            var card by remember(link.url) {
                mutableStateOf(com.pismo.messenger.data.LinkPreviews.cached(link.url))
            }
            LaunchedEffect(link.url) {
                if (card == null) {
                    card = com.pismo.messenger.data.LinkPreviews.fetch(link.url)
                }
            }

            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x22000000))
                    // Видео открываем прямо здесь, остальное — в браузере.
                    // Уходить из переписки ради ролика не нужно.
                    .clickable {
                        if (playable != null) videoLink = playable
                        else com.pismo.messenger.core.LinkOpener.open(context, link.url)
                    }
                    .padding(8.dp)
                    .widthIn(max = 260.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(src.color)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            src.mark,
                            color = Color.White,
                            fontSize = if (src.mark.length > 1) 9.sp else 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        (card?.site?.takeIf { it.isNotBlank() } ?: src.title) +
                                if (playable != null) "  ▶" else "",
                        color = PismoColors.Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }

                card?.let { c ->
                    if (c.title.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            c.title,
                            color = PismoColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    if (c.description.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            c.description,
                            color = PismoColors.TextMuted,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    c.imageFile?.let { path ->
                        Spacer(Modifier.height(6.dp))
                        AsyncImage(
                            model = java.io.File(path),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp)),
                        )
                    }
                }
            }
        }
}

/** Отдаёт адрес любому приложению, которым его захотят переслать. */
private fun shareText(context: android.content.Context, text: String) {
    runCatching {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        context.startActivity(android.content.Intent.createChooser(send, "Поделиться ссылкой"))
    }
}
