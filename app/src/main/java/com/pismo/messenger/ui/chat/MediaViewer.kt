package com.pismo.messenger.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.pismo.messenger.core.formatTime
import com.pismo.messenger.data.MediaCache
import com.pismo.messenger.data.Transfers
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.media.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Одно медиа переписки — для переходов в просмотре. */
data class MediaItem(
    val msgId: Int,
    val isVideo: Boolean,
    val fileName: String?,
    val sender: String,
    val timeMs: Long,
)

/**
 * Просмотр медиа с ПЕРЕХОДАМИ по переписке — как в Telegram.
 *
 * Раньше открывался ровно один снимок: чтобы посмотреть соседний, нужно было
 * закрыть просмотр, найти его в ленте и открыть заново. Теперь перелистывание
 * свайпом и стрелками идёт по всем снимкам и роликам загруженной переписки, а
 * внизу видно, который это кадр по счёту, кто отправил и когда. Там же
 * инструменты: сохранить и поделиться.
 */
@Composable
fun MediaViewerHost(
    items: List<MediaItem>,
    startIndex: Int,
    scopeKind: Scope,
    transferKey: String,
    onClose: () -> Unit,
) {
    if (items.isEmpty()) return
    val pager = rememberPagerState(
        initialPage = startIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size },
    )
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var chrome by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val item = items[page]
                if (item.isVideo) {
                    VideoPage(item, scopeKind, transferKey, onToggleChrome = { chrome = !chrome })
                } else {
                    PhotoPage(item, scopeKind, onToggleChrome = { chrome = !chrome })
                }
            }

            val cur = items[pager.currentPage]

            if (chrome) {
                // Стрелки — как в настольном Telegram: листать можно и свайпом,
                // но нажатием по краю удобнее одной рукой.
                if (pager.currentPage > 0) {
                    RoundArrow(
                        left = true,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                    ) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
                }
                if (pager.currentPage < items.lastIndex) {
                    RoundArrow(
                        left = false,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                    ) { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
                }

                // Шапка: закрыть.
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(8.dp),
                ) {
                    RoundButton(Icons.Default.Close, "Закрыть", onClose)
                }

                // Подпись и инструменты.
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0x99000000))
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        (if (cur.isVideo) "Видео " else "Фото ") +
                                "${pager.currentPage + 1} из ${items.size}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        listOfNotNull(
                            cur.sender.takeIf { it.isNotBlank() },
                            formatTime(cur.timeMs),
                        ).joinToString(" · "),
                        color = Color(0xCCFFFFFF),
                        fontSize = 12.sp,
                    )
                    if (note.isNotBlank()) {
                        Text(note, color = Color(0xFF8BC34A), fontSize = 12.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        RoundButton(Icons.Default.Download, "Сохранить") {
                            scope.launch {
                                note = "Сохранение…"
                                note = if (saveItem(context, cur, scopeKind)) "Сохранено"
                                else "Не удалось сохранить"
                            }
                        }
                        RoundButton(Icons.Default.Share, "Поделиться") {
                            scope.launch { shareItem(context, cur, scopeKind) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundArrow(left: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (left) Icons.Default.KeyboardArrowLeft else Icons.Default.KeyboardArrowRight,
            contentDescription = if (left) "Предыдущее" else "Следующее",
            tint = Color.White,
        )
    }
}

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    hint: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .padding(start = 8.dp)
            .size(42.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = hint, tint = Color.White)
    }
}

/** Снимок: зум щипком, нажатие прячет и возвращает подписи. */
@Composable
private fun PhotoPage(item: MediaItem, scopeKind: Scope, onToggleChrome: () -> Unit) {
    var bytes by remember(item.msgId) {
        mutableStateOf(MediaCache.get(item.msgId, "img", item.fileName))
    }
    var scale by remember(item.msgId) { mutableFloatStateOf(1f) }
    var dx by remember(item.msgId) { mutableFloatStateOf(0f) }
    var dy by remember(item.msgId) { mutableFloatStateOf(0f) }

    LaunchedEffect(item.msgId) {
        if (bytes == null) {
            bytes = runCatching { ChatRepository.loadImage(item.msgId, scopeKind, item.fileName) }
                .getOrNull()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // ЖЕСТЫ РАЗВЕДЕНЫ С ЛИСТАНИЕМ.
            //
            // Обычный detectTransformGestures забирает себе ЛЮБОЕ движение —
            // и свайп в сторону не доходил до пролистывания, то есть соседний
            // снимок пальцем было не достать. Поэтому разбираем события сами:
            // два пальца — это зум и перенос, один палец при увеличенном
            // снимке — перенос, а одиночный свайп на обычном масштабе не
            // трогаем вовсе: его подхватывает лента просмотра.
            .pointerInput(item.msgId) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val many = event.changes.count { it.pressed } >= 2
                        if (many) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            if (scale > 1f) { dx += pan.x; dy += pan.y } else { dx = 0f; dy = 0f }
                            event.changes.forEach { it.consume() }
                        } else if (scale > 1f) {
                            val pan = event.calculatePan()
                            dx += pan.x
                            dy += pan.y
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .clickable { onToggleChrome() },
        contentAlignment = Alignment.Center,
    ) {
        val b = bytes
        if (b == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            AsyncImage(
                model = b,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = dx, translationY = dy),
            )
        }
    }
}

/** Ролик: играет прямо в просмотре, если он уже скачан; иначе — по нажатию. */
@Composable
private fun VideoPage(
    item: MediaItem,
    scopeKind: Scope,
    transferKey: String,
    onToggleChrome: () -> Unit,
) {
    val name = item.fileName ?: "video.mp4"
    var file by remember(item.msgId) { mutableStateOf(MediaCache.fileFor(item.msgId, "file", name)) }
    var busy by remember(item.msgId) { mutableStateOf(false) }
    var play by remember(item.msgId) { mutableStateOf(file != null) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val f = file
        if (play && f != null) {
            VideoSurface(
                file = f,
                startAtMs = 0,
                autoPlay = true,
                onPositionChange = {},
                onFullscreen = {},
                onSave = {},
                insetBottom = true,
                onControlsChange = { visible -> if (!visible) onToggleChrome() },
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable {
                        if (busy) return@clickable
                        val have = file
                        if (have != null) { play = true; return@clickable }
                        busy = true
                        Transfers.download(
                            where = transferKey,
                            msgId = item.msgId,
                            scopeKind = scopeKind,
                            fileName = name,
                        ) {
                            busy = false
                            file = MediaCache.fileFor(item.msgId, "file", name)
                            play = file != null
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(color = Color.White)
                else Icon(
                    Icons.Default.PlayArrow, "Смотреть",
                    tint = Color.White, modifier = Modifier.size(64.dp),
                )
            }
        }
    }
}

private suspend fun saveItem(
    context: android.content.Context,
    item: MediaItem,
    scopeKind: Scope,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        if (item.isVideo) {
            val name = item.fileName ?: "video.mp4"
            val f = MediaCache.fileFor(item.msgId, "file", name)
            val data = f?.readBytes()
                ?: ChatRepository.loadFile(item.msgId, scopeKind, name)
                ?: return@runCatching false
            MediaSaver.saveVideo(context, name, data)
        } else {
            val data = MediaCache.get(item.msgId, "img", item.fileName)
                ?: ChatRepository.loadImage(item.msgId, scopeKind, item.fileName)
                ?: return@runCatching false
            MediaSaver.saveImage(context, item.fileName ?: "photo.jpg", data)
        }
    }.getOrDefault(false)
}

private suspend fun shareItem(
    context: android.content.Context,
    item: MediaItem,
    scopeKind: Scope,
) {
    runCatching {
        val name = item.fileName ?: if (item.isVideo) "video.mp4" else "photo.jpg"
        val data = withContext(Dispatchers.IO) {
            if (item.isVideo) {
                MediaCache.fileFor(item.msgId, "file", name)?.readBytes()
                    ?: ChatRepository.loadFile(item.msgId, scopeKind, name)
            } else {
                MediaCache.get(item.msgId, "img", item.fileName)
                    ?: ChatRepository.loadImage(item.msgId, scopeKind, item.fileName)
            }
        } ?: return
        shareBytes(context, name, data)
    }
}

/** Отдать файл другому приложению — «поделиться». */
private suspend fun shareBytes(
    context: android.content.Context,
    fileName: String,
    bytes: ByteArray,
) = withContext(Dispatchers.IO) {
    runCatching {
        val dir = java.io.File(context.cacheDir, "downloads").apply { mkdirs() }
        val safe = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val file = java.io.File(dir, safe)
        file.writeBytes(bytes)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val ext = safe.substringAfterLast('.', "")
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext.lowercase()) ?: "*/*"
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, "Поделиться")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
