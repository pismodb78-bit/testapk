package com.pismo.messenger.ui.chat

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.pismo.messenger.core.formatSize
import com.pismo.messenger.data.model.Conversation
import com.pismo.messenger.data.model.GroupSummary
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.ServerRepository
import com.pismo.messenger.media.MediaSaver
import com.pismo.messenger.ui.components.GroupAvatar
import com.pismo.messenger.ui.components.LetterAvatar
import com.pismo.messenger.ui.components.UserAvatar
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Полноэкранный просмотр картинки с зумом — аналог ShowImageFullscreen
 * из ПК-версии (там окно с PictureBox.Zoom).
 */
@Composable
fun FullscreenImageViewer(
    bytes: ByteArray,
    onDismiss: () -> Unit,
    fileName: String? = null,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var saveStatus by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f; offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = bytes,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
            )

            Row(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (saveStatus.isNotBlank()) {
                    Text(saveStatus, color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                }
                // «Скачать» на телефоне — это положить файл в галерею:
                // приватный каталог приложения пользователю не показывают
                // ни один файловый менеджер, ни просмотрщик фото.
                IconButton(onClick = {
                    scope.launch {
                        saveStatus = "Сохранение…"
                        val name = fileName?.takeIf { it.isNotBlank() }
                            ?: "pismo_${System.currentTimeMillis()}.jpg"
                        saveStatus =
                            if (MediaSaver.saveImage(context, name, bytes)) "Сохранено в галерею"
                            else "Не удалось сохранить"
                    }
                }) {
                    Icon(Icons.Default.Download, "Сохранить в галерею", tint = Color.White)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Закрыть", tint = Color.White)
                }
            }
        }
    }
}

/**
 * Сохраняет файл в кеш приложения и открывает системным приложением —
 * Android-аналог SaveFileDialog + Process.Start из ПК-версии.
 */
suspend fun saveAndOpenFile(context: Context, fileName: String, bytes: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
            val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val file = File(dir, safeName)
            file.writeBytes(bytes)

            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val ext = safeName.substringAfterLast('.', "")
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext.lowercase()) ?: "*/*"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

/**
 * Пересылка сообщения — выбор адресата, затем копирование через
 * INSERT … SELECT (байты вложений не гоняются через клиент).
 */
@Composable
fun ForwardDialog(
    srcScope: Scope,
    srcMessageId: Int,
    srcText: String,
    srcSender: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) = ForwardDialog(
    srcScope = srcScope,
    items = listOf(ForwardItem(srcMessageId, srcText, srcSender)),
    onDismiss = onDismiss,
    onDone = onDone,
)

/** Одно пересылаемое сообщение: что переслать и от кого оно. */
data class ForwardItem(val messageId: Int, val text: String, val sender: String)

/**
 * Пересылка пачки сообщений — порт BeginForwardExternalBatch с ПК.
 *
 * Порядок сохраняется исходный: пересылка идёт по возрастанию id, иначе
 * у получателя разговор оказался бы перевёрнутым.
 */
@Composable
fun ForwardDialog(
    srcScope: Scope,
    items: List<ForwardItem>,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var groups by remember { mutableStateOf<List<GroupSummary>>(emptyList()) }
    // Каналы серверов как цель пересылки. Репозиторий умел это с самого
    // начала (Scope.SERVER), а в списке выбора их просто не было — переслать
    // из лички на сервер и обратно было нельзя.
    var channels by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            conversations = ChatRepository.loadConversations()
            groups = ChatRepository.loadGroups()
            channels = ServerRepository.channelNames().toList().sortedBy { it.second }
        }
    }

    fun forward(target: Int, dstScope: Scope) {
        busy = true
        scope.launch {
            items.sortedBy { it.messageId }.forEach { item ->
                // Текст с указанием исходного отправителя — формат ПК-версии.
                val caption = if (item.sender.isBlank()) "↪ Переслано:\n${item.text}"
                else "↪ Переслано от ${item.sender}:\n${item.text}"
                runCatching {
                    ChatRepository.forwardMessage(
                        srcScope = srcScope,
                        srcId = item.messageId,
                        dstScope = dstScope,
                        dstTarget = target,
                        captionText = caption,
                    )
                }
            }
            busy = false
            onDone()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = {
            Text(
                if (items.size > 1) "Переслать ${items.size} сообщ." else "Переслать",
                color = PismoColors.TextPrimary,
            )
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                if (groups.isNotEmpty()) {
                    item {
                        Text("ГРУППЫ", color = PismoColors.TextMuted, fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                    items(groups, key = { "g${it.id}" }) { g ->
                        TargetRow(g.id, g.name, TargetKind.GROUP, enabled = !busy) {
                            forward(g.id, Scope.GROUP)
                        }
                    }
                }
                item {
                    Text("ЛИЧНЫЕ", color = PismoColors.TextMuted, fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 4.dp))
                }
                items(conversations, key = { "u${it.userId}" }) { c ->
                    TargetRow(c.userId, c.name, TargetKind.USER, enabled = !busy) {
                        forward(c.userId, Scope.DM)
                    }
                }

                if (channels.isNotEmpty()) {
                    item {
                        Text("КАНАЛЫ СЕРВЕРОВ", color = PismoColors.TextMuted, fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                    items(channels, key = { "c${it.first}" }) { (id, name) ->
                        TargetRow(id, "# $name", TargetKind.CHANNEL, enabled = !busy) {
                            forward(id, Scope.SERVER)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = PismoColors.TextMuted) }
        },
    )
}

/** Чей это ряд — от этого зависит, что рисовать вместо кружка. */
private enum class TargetKind { USER, GROUP, CHANNEL }

@Composable
private fun TargetRow(
    id: Int,
    name: String,
    kind: TargetKind,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (enabled) Modifier.padding(vertical = 6.dp) else Modifier.padding(vertical = 6.dp)
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // У людей — настоящая аватарка. Здесь всегда рисовалась буква, хотя
        // фото у человека есть и видно его во всех остальных списках. У групп
        // свой кружок, у каналов фотографии нет вовсе — там буква и остаётся.
        when (kind) {
            TargetKind.USER -> UserAvatar(id, name, 32.dp)
            TargetKind.GROUP -> GroupAvatar(id, name, "#5865F2", 32.dp)
            TargetKind.CHANNEL -> LetterAvatar(id, name, 32.dp)
        }
        Spacer(Modifier.width(10.dp))
        Text(name, color = PismoColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick, enabled = enabled) {
            Text("Отправить", color = PismoColors.Blurple, fontSize = 13.sp)
        }
    }
}


/**
 * Панель множественного выделения — порт BuildSelectBar с ПК.
 *
 * Появляется поверх ленты, когда включён режим выделения, и повторяет тот же
 * набор: счётчик, отмена, пересылка, удаление. Удаление красное и стоит
 * последним — чтобы не попасть в него, целясь в пересылку.
 */
@Composable
fun SelectionBar(
    count: Int,
    canDelete: Boolean,
    onCancel: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(PismoColors.BgElevated)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Выбрано: $count",
            color = PismoColors.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) {
            Text("Отмена", color = PismoColors.TextMuted)
        }
        TextButton(onClick = onForward, enabled = count > 0) {
            Text("↪ Переслать", color = PismoColors.Cyan)
        }
        if (canDelete) {
            TextButton(onClick = onDelete, enabled = count > 0) {
                Text("🗑 Удалить", color = PismoColors.Red)
            }
        }
    }
}
