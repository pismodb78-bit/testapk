package com.pismo.messenger.ui.servers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.data.model.ServerPermissions
import com.pismo.messenger.data.repo.ServerRepository
import com.pismo.messenger.ui.login.PismoField
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.launch

/**
 * Настройки сервера — то, что на ПК живёт в меню шапки ServersForm:
 * переименование, ID для приглашения, беззвучный режим, выход и удаление.
 *
 * Приглашение здесь — это именно ID: своей системы инвайт-ссылок в
 * проекте нет, на ПК к серверу присоединяются вводом номера.
 */
@Composable
fun ServerSettingsDialog(
    serverId: Int,
    serverName: String,
    perms: ServerPermissions,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onLeft: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var name by remember(serverId) { mutableStateOf(serverName) }
    var muted by remember(serverId) { mutableStateOf(perms.mutedNotifications) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить сервер?",
            message = "Вместе с сервером исчезнут все его каналы и переписка в них. " +
                    "Отменить это будет нельзя.",
            confirmText = "Удалить",
            onConfirm = {
                scope.launch {
                    ServerRepository.deleteServer(serverId)
                    confirmDelete = false
                    onLeft()
                }
            },
            onDismiss = { confirmDelete = false },
        )
        return
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "Покинуть сервер?",
            message = "Вернуться можно будет по ID $serverId, если вас не забанят.",
            confirmText = "Выйти",
            onConfirm = {
                scope.launch {
                    ServerRepository.leaveServer(serverId)
                    confirmLeave = false
                    onLeft()
                }
            },
            onDismiss = { confirmLeave = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = { Text("Настройки сервера", color = PismoColors.TextPrimary) },
        text = {
            Column {
                if (perms.isAdminLike) {
                    PismoField(name, { name = it }, "Название")
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text(name, color = PismoColors.TextPrimary, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                }

                Text(
                    "ID для приглашения: $serverId",
                    color = PismoColors.Cyan, fontSize = 14.sp,
                )
                TextButton(onClick = {
                    copyToClipboard(context, serverId.toString())
                    note = "ID скопирован."
                }) { Text("Скопировать ID", color = PismoColors.TextSecondary, fontSize = 13.sp) }

                Spacer(Modifier.height(4.dp))
                TextButton(onClick = {
                    val next = !muted
                    muted = next
                    scope.launch {
                        ServerRepository.setMutedNotifications(serverId, next)
                        onChanged()
                    }
                }) {
                    Text(
                        if (muted) "🔕 Уведомления заглушены — включить"
                        else "🔔 Уведомления включены — заглушить",
                        color = PismoColors.TextSecondary, fontSize = 13.sp,
                    )
                }

                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { confirmLeave = true }) {
                    Text("Покинуть сервер", color = PismoColors.Yellow, fontSize = 13.sp)
                }
                if (perms.isOwner) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Удалить сервер", color = PismoColors.Red, fontSize = 13.sp)
                    }
                }

                if (note.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(note, color = PismoColors.Green, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (perms.isAdminLike && name.isNotBlank() && name != serverName) {
                    scope.launch {
                        ServerRepository.renameServer(serverId, name)
                        onChanged()
                        onDismiss()
                    }
                } else onDismiss()
            }) { Text("Готово", color = PismoColors.Cyan) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = PismoColors.TextMuted) }
        },
    )
}

/**
 * Настройки канала: переименование, лимит участников для голосового и
 * удаление. Порт контекстного меню канала из ServersForm.
 */
@Composable
fun ChannelSettingsDialog(
    channelId: Int,
    channelName: String,
    isVoice: Boolean,
    userLimit: Int,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(channelId) { mutableStateOf(channelName) }
    var limit by remember(channelId) { mutableStateOf(if (userLimit > 0) userLimit.toString() else "") }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить канал?",
            message = "Сообщения канала «$channelName» будут потеряны.",
            confirmText = "Удалить",
            onConfirm = {
                scope.launch {
                    ServerRepository.deleteChannel(channelId)
                    confirmDelete = false
                    onChanged()
                    onDismiss()
                }
            },
            onDismiss = { confirmDelete = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = { Text("Настройки канала", color = PismoColors.TextPrimary) },
        text = {
            Column {
                PismoField(name, { name = it }, "Название")
                if (isVoice) {
                    Spacer(Modifier.height(10.dp))
                    PismoField(
                        limit, { limit = it.filter(Char::isDigit) },
                        "Лимит участников (пусто — без ограничения)",
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Удалить канал", color = PismoColors.Red, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    if (name.isNotBlank() && name != channelName) {
                        ServerRepository.renameChannel(channelId, name.trim())
                    }
                    if (isVoice) {
                        // Пустая строка — «без ограничения», это 0 в БД:
                        // так же трактует лимит ПК-версия.
                        ServerRepository.setChannelUserLimit(channelId, limit.toIntOrNull() ?: 0)
                    }
                    onChanged()
                    onDismiss()
                }
            }) { Text("Сохранить", color = PismoColors.Cyan) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = PismoColors.TextMuted) }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = { Text(title, color = PismoColors.TextPrimary) },
        text = { Text(message, color = PismoColors.TextSecondary, fontSize = 13.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText, color = PismoColors.Red) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = PismoColors.TextMuted) }
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PISMO", text))
    }
}
