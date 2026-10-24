package com.pismo.messenger.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.pismo.messenger.core.MediaKinds
import com.pismo.messenger.core.formatTime
import com.pismo.messenger.data.MediaCache
import com.pismo.messenger.data.Transfers
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Плитка альбома — несколько фотографий и роликов, отправленных одной пачкой.
 *
 * Раньше каждое вложение было отдельным пузырём: десять снимков занимали
 * экран на десять прокруток, и в каком порядке их отправляли, понять было
 * нельзя. Теперь они собираются в одну плитку, как в Telegram: порядок —
 * тот, в котором их выбирали, подпись одна на всю пачку, а нажатие на
 * ячейку открывает именно её.
 *
 * Кто попадает в одну плитку, решает core/Albums — по тем же признакам, по
 * которым это видно человеку: один отправитель, подряд, подпись только у
 * первого.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumBubble(
    msgs: List<ChatMessage>,
    scopeKind: Scope,
    isGroup: Boolean,
    transferKey: String,
    onOpenVideo: (java.io.File, String, Int, String, String) -> Unit,
    /** Открыть ленту просмотра на этой ячейке — с переходами по соседям. */
    onOpenMedia: ((Int) -> Unit)? = null,
    selectMode: Boolean = false,
    selected: Boolean = false,
    onEnterSelect: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
) {
    if (msgs.isEmpty()) return
    val head = msgs.first()
    val last = msgs.last()
    val isMine = head.isMine
    val caption = head.text
    var viewerBytes by remember(head.id) { mutableStateOf<ByteArray?>(null) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 312.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isMine) PismoColors.Blurple else PismoColors.BgElevated)
                .padding(3.dp)
                // Фокус пузырю не нужен: из-за него лента подтягивала его к
                // себе и уезжала из-под пальца.
                .focusProperties { canFocus = false },
        ) {
            if (isGroup && !isMine) {
                Text(
                    head.senderName,
                    color = Color(0xFF7FD1FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 3.dp),
                )
            }

            AlbumGrid(
                msgs = msgs,
                scopeKind = scopeKind,
                transferKey = transferKey,
                selectMode = selectMode,
                onOpenMedia = onOpenMedia,
                onPhoto = { bytes -> viewerBytes = bytes },
                onVideo = { f, name, id -> onOpenVideo(f, name, id, head.senderName, caption) },
                onLongPress = onEnterSelect,
                onSelect = onToggleSelect,
            )

            if (caption.isNotBlank()) {
                Text(
                    caption,
                    color = if (isMine) Color.White else PismoColors.TextPrimary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(start = 5.dp, end = 5.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectMode) {
                    Text(
                        if (selected) "✔ выбрано" else "выбрать",
                        color = if (selected) PismoColors.Green else PismoColors.TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                Text(
                    formatTime(last.createdAtMs) + if (isMine && last.isRead) " ✓✓" else "",
                    color = if (isMine) Color(0xCCFFFFFF) else PismoColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }
    }

    viewerBytes?.let { b ->
        FullscreenImageViewer(bytes = b, onDismiss = { viewerBytes = null })
    }
}

/**
 * Раскладка плитки.
 *
 * До четырёх снимков — по два в ряд, дальше — по три: ровно так пачка
 * выглядит в Telegram, и ровно так в неё помещается десять штук, не
 * превращаясь в ленту огрызков.
 */
@Composable
private fun AlbumGrid(
    msgs: List<ChatMessage>,
    scopeKind: Scope,
    transferKey: String,
    selectMode: Boolean,
    onOpenMedia: ((Int) -> Unit)?,
    onPhoto: (ByteArray) -> Unit,
    onVideo: (java.io.File, String, Int) -> Unit,
    onLongPress: () -> Unit,
    onSelect: () -> Unit,
) {
    // Ряды РАЗНОЙ длины — чтобы не оставалось пустых клеток (см. albumRows).
    // Ячейки ряда делят ширину поровну, поэтому ряд из двух выше ряда из трёх:
    // мозаика получается плотной, без синих проплешин.
    val width = 304.dp
    val gap = 2.dp
    // Ряды считаем ЗАРАНЕЕ, здесь же: внутри Column нельзя вести счётчик —
    // Compose перезапускает вложенные куски сам по себе, и счётчик уехал бы.
    val slices = ArrayList<IntRange>(4).also { out ->
        var from = 0
        albumRows(msgs.size).forEach { count ->
            val to = minOf(from + count, msgs.size)
            if (from < to) out.add(from until to)
            from = to
        }
    }
    // Внешние углы мозаики скруглены, внутренние стыки — почти прямые, как в
    // телеграме: альбом читается одной карточкой, а не россыпью квадратиков.
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        slices.forEach { range ->
            val count = range.last - range.first + 1
            val side = (width - gap * (count - 1)) / count
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                range.forEach { i ->
                    val m = msgs[i]
                    Box(Modifier.width(side).height(side)) {
                        AlbumCell(
                            msg = m,
                            scopeKind = scopeKind,
                            transferKey = transferKey,
                            selectMode = selectMode,
                            onOpenMedia = onOpenMedia,
                            onPhoto = onPhoto,
                            onVideo = onVideo,
                            onLongPress = onLongPress,
                            onSelect = onSelect,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Длины рядов плитки — как в телеграме: лишних клеток не остаётся.
 *
 * Раньше пачка резалась на ряды одинаковой длины, и остаток последнего ряда
 * оставался пустым — пузырь зиял синим. Теперь ряды разной длины: в каждом
 * два, три или четыре снимка, а в сумме ровно столько, сколько прислали.
 */
private fun albumRows(n: Int): List<Int> {
    if (n <= 3) return listOf(n)
    val rows = (n + 2) / 3
    val base = n / rows
    val rem = n % rows
    return List(rows) { if (it < rem) base + 1 else base }
}

/** Одна ячейка плитки: снимок или ролик. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCell(
    msg: ChatMessage,
    scopeKind: Scope,
    transferKey: String,
    selectMode: Boolean,
    onOpenMedia: ((Int) -> Unit)?,
    onPhoto: (ByteArray) -> Unit,
    onVideo: (java.io.File, String, Int) -> Unit,
    onLongPress: () -> Unit,
    onSelect: () -> Unit,
) {
    val isVideo = msg.hasFile && MediaKinds.isVideo(msg.fileName)
    var bytes by remember(msg.id) {
        mutableStateOf(if (msg.hasImage) MediaCache.get(msg.id, "img", msg.fileName) else null)
    }
    var frame by remember(msg.id) { mutableStateOf<Bitmap?>(null) }
    var file by remember(msg.id) {
        mutableStateOf(
            if (isVideo) MediaCache.fileFor(msg.id, "file", msg.fileName ?: "video.mp4") else null
        )
    }
    var busy by remember(msg.id) { mutableStateOf(false) }

    LaunchedEffect(msg.id) {
        if (msg.hasImage && bytes == null) {
            bytes = runCatching { ChatRepository.loadImage(msg.id, scopeKind, msg.fileName) }
                .getOrNull()
        }
    }
    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        if (frame != null) return@LaunchedEffect
        frame = withContext(Dispatchers.IO) { runCatching { firstFrame(f) }.getOrNull() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0x33000000))
            .focusProperties { canFocus = false }
            .combinedClickable(
                onLongClick = onLongPress,
                onClick = {
                    if (selectMode) { onSelect(); return@combinedClickable }
                    // Лента просмотра: из неё листаются и соседние ячейки, и
                    // остальные снимки переписки.
                    if (onOpenMedia != null) { onOpenMedia(msg.id); return@combinedClickable }
                    if (isVideo) {
                        val f = file
                        if (f != null) { onVideo(f, msg.fileName ?: "video.mp4", msg.id); return@combinedClickable }
                        if (busy) return@combinedClickable
                        busy = true
                        Transfers.download(
                            where = transferKey,
                            msgId = msg.id,
                            scopeKind = scopeKind,
                            fileName = msg.fileName ?: "video.mp4",
                        ) {
                            busy = false
                            file = MediaCache.fileFor(msg.id, "file", msg.fileName ?: "video.mp4")
                            file?.let { onVideo(it, msg.fileName ?: "video.mp4", msg.id) }
                        }
                    } else {
                        bytes?.let(onPhoto)
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            frame != null -> Image(
                bitmap = frame!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            bytes != null -> AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(bytes)
                    // Ключ и ограниченный размер — как в одиночном пузыре:
                    // иначе быстрая прокрутка раскодирует снимок заново на
                    // каждом возврате ячейки в видимую область.
                    .memoryCacheKey("album-${msg.id}")
                    .size(512)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (isVideo) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White)
            }
        }
    }
}
