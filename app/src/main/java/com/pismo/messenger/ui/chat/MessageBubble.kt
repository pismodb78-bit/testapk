package com.pismo.messenger.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pismo.messenger.core.avatarColor
import com.pismo.messenger.core.ellipsize
import com.pismo.messenger.core.fileBadge
import com.pismo.messenger.core.fileColor
import com.pismo.messenger.core.MediaKinds
import com.pismo.messenger.core.formatTime
import com.pismo.messenger.data.MediaCache
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.ReactionSummary
import com.pismo.messenger.data.model.ReplyQuote
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.PinsRepository
import com.pismo.messenger.data.repo.ReactionsRepository
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
) {
    val scope = rememberCoroutineScope()
    val isMine = msg.isMine
    var menuOpen by remember { mutableStateOf(false) }
    // Начальное значение берём из памяти СИНХРОННО. Пузырь, ушедший за край
    // экрана, LazyColumn уничтожает вместе с этим состоянием, и на обратной
    // прокрутке всё грузилось заново — выглядело как перезагрузка чата,
    // из которого ты даже не выходил. Готовое из памяти показывается сразу,
    // а LaunchedEffect ниже дочитывает только то, чего в ней нет.
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
                Column(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(12.dp))
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
                                        scope.launch {
                                            fileStatus = "Загрузка с сервера…"
                                            val data = runCatching {
                                                ChatRepository.loadFile(msg.id, scopeKind, msg.fileName)
                                            }.getOrNull()
                                            fileStatus = if (data == null) "Не удалось загрузить"
                                            else if (saveAndOpenFile(context, msg.fileName!!, data)) ""
                                            else "Нет приложения для этого типа файла"
                                        }
                                    }
                                    .padding(8.dp),
                            ) {
                                FileBadge(fileBadge(msg.fileName), fileColor(msg.fileName), 36.dp)
                                Spacer(Modifier.width(8.dp))
                                Column {
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
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        if (msg.text.isNotBlank()) {
                            Text(
                                highlightMentions(msg.text, isMine),
                                color = PismoColors.onBubble(isMine),
                                fontSize = 15.sp,
                            )
                        }
                    }

                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (msg.isEdited) "${formatTime(msg.createdAtMs)} · изменено"
                        else formatTime(msg.createdAtMs),
                        color = if (isMine) Color(0xFFB9BEFF) else PismoColors.TextMuted,
                        fontSize = 10.sp,
                    )
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
                            onClick = { menuOpen = false },
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

@Composable
private fun VoiceRow(msgId: Int, audio: ByteArray?, isMine: Boolean) {
    val playing = WavPlayer.playingId == msgId
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x22000000))
            .clickable { audio?.let { WavPlayer.toggle(msgId, it) } }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Icon(
            if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = if (playing) "Остановить" else "Воспроизвести",
            tint = PismoColors.onBubble(isMine),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (audio == null) "Загрузка…"
            else "Голосовое · ${WavPlayer.durationSecondsOf(audio)} с",
            color = PismoColors.onBubble(isMine),
            fontSize = 13.sp,
        )
    }
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
private fun highlightMentions(text: String, isMine: Boolean): AnnotatedString {
    val spans = com.pismo.messenger.core.Mentions.spans(text)
    if (spans.isEmpty()) return AnnotatedString(text)

    val accent = if (isMine) Color.White else PismoColors.Cyan
    return buildAnnotatedString {
        var pos = 0
        for (range in spans) {
            if (range.first > pos) append(text.substring(pos, range.first))
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(range.first, range.last + 1))
            }
            pos = range.last + 1
        }
        if (pos < text.length) append(text.substring(pos))
    }
}
