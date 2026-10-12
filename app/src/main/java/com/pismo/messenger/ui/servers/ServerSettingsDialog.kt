package com.pismo.messenger.ui.servers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.ServerMemory
import com.pismo.messenger.data.model.ServerPermissions
import com.pismo.messenger.data.repo.ServerRepository
import com.pismo.messenger.ui.components.ColorPalettePicker
import com.pismo.messenger.ui.components.ServerAvatar
import com.pismo.messenger.ui.login.PismoField
import com.pismo.messenger.ui.profile.AvatarCropDialog
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
    avatarColor: String = "",
    hasAvatar: Boolean = false,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onLeft: () -> Unit,
    onIdChanged: (Int) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var name by remember(serverId) { mutableStateOf(serverName) }
    var muted by remember(serverId) { mutableStateOf(perms.mutedNotifications) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmNewId by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    // Оформление до записи. У окна есть «Готово» и «Отмена», и если часть
    // правок применяется мимо них, кнопки обманывают: «Отмена» уже ничего не
    // отменяет. Поэтому выбранное держим здесь и пишем всё разом по «Готово».
    var color by remember(serverId) { mutableStateOf(avatarColor) }
    var pendingPng by remember(serverId) { mutableStateOf<ByteArray?>(null) }
    var dropPicture by remember(serverId) { mutableStateOf(false) }
    var cropUri by remember { mutableStateOf<Uri?>(null) }

    val picture = pendingPng != null || (hasAvatar && !dropPicture)

    // Выбранный файл сначала идёт в обрезку — значок квадратный и мелкий,
    // заливать в базу оригинал с камеры незачем: его потом тянет каждый
    // участник сервера при каждой отрисовке рельса.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) cropUri = uri }

    cropUri?.let { uri ->
        AvatarCropDialog(
            uri = uri,
            onCancel = { cropUri = null },
            onDone = { png ->
                cropUri = null
                pendingPng = png
                dropPicture = false
                note = ""
            },
        )
        return
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить сервер?",
            message = "Вместе с сервером исчезнут все его каналы и переписка в них. " +
                    "Отменить это будет нельзя.",
            confirmText = "Удалить",
            onConfirm = {
                scope.launch {
                    ServerRepository.deleteServer(serverId)
                    ServerMemory.invalidate(serverId)
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
                    ServerMemory.invalidate(serverId)
                    confirmLeave = false
                    onLeft()
                }
            },
            onDismiss = { confirmLeave = false },
        )
        return
    }

    if (confirmNewId) {
        ConfirmDialog(
            title = "Сменить ID сервера?",
            message = "Сервер получит новый случайный номер. По старому номеру " +
                    "($serverId) войти больше не получится — разошлите новый тем, " +
                    "кого ждёте. Участники, каналы и переписка останутся на месте.",
            confirmText = "Сменить",
            onConfirm = {
                confirmNewId = false
                scope.launch {
                    val fresh = ServerRepository.changeServerId(serverId)
                    if (fresh > 0) {
                        ServerMemory.invalidate(serverId)
                        note = "Новый ID: $fresh"
                        onIdChanged(fresh)
                    } else note = "Не удалось сменить ID."
                }
            },
            onDismiss = { confirmNewId = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = { Text("Настройки сервера", color = PismoColors.TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (perms.isAdminLike) {
                    PismoField(name, { name = it }, "Название")
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text(name, color = PismoColors.TextPrimary, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                }

                // Оформление меняет тот, кто и так распоряжается сервером:
                // значок виден всем участникам в рельсе.
                if (perms.isAdminLike) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val chosen = pendingPng
                        if (chosen != null) {
                            // Только что выбранная картинка ещё не в базе —
                            // показываем её из памяти, иначе до «Готово» была
                            // бы видна прежняя.
                            AsyncImage(
                                model = chosen,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(18.dp)),
                            )
                        } else {
                            ServerAvatar(
                                serverId = serverId,
                                name = name.ifBlank { serverName },
                                colorHex = color,
                                hasAvatar = hasAvatar && !dropPicture,
                                size = 56.dp,
                                shape = RoundedCornerShape(18.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            TextButton(onClick = { picker.launch("image/*") }) {
                                Text(
                                    if (picture) "Сменить картинку" else "Загрузить картинку",
                                    color = PismoColors.Cyan, fontSize = 13.sp,
                                )
                            }
                            if (picture) {
                                TextButton(onClick = {
                                    pendingPng = null
                                    dropPicture = true
                                    note = ""
                                }) {
                                    Text(
                                        "Убрать картинку",
                                        color = PismoColors.TextMuted, fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Цвет значка",
                        color = PismoColors.TextSecondary, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    ColorPalettePicker(
                        selected = color,
                        onPick = { hex -> color = hex },
                    )
                    if (color.isNotBlank()) {
                        TextButton(onClick = { color = "" }) {
                            Text(
                                "Цвет по умолчанию",
                                color = PismoColors.TextMuted, fontSize = 13.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Text(
                    "ID для приглашения: $serverId",
                    color = PismoColors.Cyan, fontSize = 14.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        copyToClipboard(context, serverId.toString())
                        note = "ID скопирован."
                    }) {
                        Text("Скопировать", color = PismoColors.TextSecondary, fontSize = 13.sp)
                    }
                    if (perms.isOwner) {
                        TextButton(onClick = { confirmNewId = true }) {
                            Text("Новый ID", color = PismoColors.Yellow, fontSize = 13.sp)
                        }
                    }
                }

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
                // Удалять — владельцу. Системному администратору тоже: без
                // него нечем убрать сервер, чей владелец давно ушёл.
                if (perms.isOwner || UserSession.isAdmin) {
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
                scope.launch {
                    val renamed = perms.isAdminLike && name.isNotBlank() && name != serverName
                    val recolored = perms.isAdminLike && color != avatarColor
                    val repictured = perms.isAdminLike && (pendingPng != null || dropPicture)

                    if (renamed) ServerRepository.renameServer(serverId, name)

                    // Цвет и картинка — одна и та же запись в servers; null
                    // в картинке означает «убрать», поэтому разводим случаи.
                    var ok = true
                    if (recolored) ok = ServerRepository.setServerLook(serverId, null, color) && ok
                    val png = pendingPng
                    if (png != null) ok = ServerRepository.setServerLook(serverId, png, null) && ok
                    else if (dropPicture) ok = ServerRepository.clearServerAvatar(serverId) && ok

                    if (!ok) {
                        note = "Не удалось сохранить значок: в базе нет его колонок (нужна миграция)."
                        return@launch
                    }
                    if (renamed || recolored || repictured) onChanged()
                    onDismiss()
                }
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
