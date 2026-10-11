package com.pismo.messenger.ui.group

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pismo.messenger.data.repo.GroupRepository
import com.pismo.messenger.ui.components.ColorPalettePicker
import com.pismo.messenger.ui.components.GroupAvatar
import com.pismo.messenger.ui.login.PismoField
import com.pismo.messenger.ui.profile.AvatarCropDialog
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.launch

/**
 * Оформление группы: название, картинка и цвет значка.
 *
 * Устроено как настройки сервера, в том числе в главном: до «Готово» в
 * базу не пишется НИЧЕГО. Выбранная картинка живёт в окне и видна в
 * предпросмотре, «Отмена» отменяет её вместе с остальным.
 */
@Composable
fun GroupSettingsDialog(
    groupId: Int,
    groupName: String,
    avatarColor: String,
    hasAvatar: Boolean,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var name by remember(groupId) { mutableStateOf(groupName) }
    var color by remember(groupId) { mutableStateOf(avatarColor) }
    var pendingPng by remember(groupId) { mutableStateOf<ByteArray?>(null) }
    var dropPicture by remember(groupId) { mutableStateOf(false) }
    var cropUri by remember { mutableStateOf<Uri?>(null) }
    var note by remember { mutableStateOf("") }

    val picture = pendingPng != null || (hasAvatar && !dropPicture)

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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = { Text("Оформление группы", color = PismoColors.TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PismoField(name, { name = it }, "Название")
                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val chosen = pendingPng
                    if (chosen != null) {
                        // Выбранная картинка ещё не в базе — показываем её из
                        // памяти, иначе до «Готово» была бы видна прежняя.
                        AsyncImage(
                            model = chosen,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(PismoColors.BgElevated),
                        )
                    } else {
                        GroupAvatar(
                            groupId = groupId,
                            name = name.ifBlank { groupName },
                            colorHex = color,
                            size = 56.dp,
                            hasAvatar = hasAvatar && !dropPicture,
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
                Text("Цвет значка", color = PismoColors.TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                ColorPalettePicker(selected = color, onPick = { hex -> color = hex })

                if (note.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(note, color = PismoColors.Red, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val renamed = name.isNotBlank() && name != groupName
                    val recolored = color != avatarColor
                    val repictured = pendingPng != null || dropPicture

                    var ok = true
                    if (renamed) ok = GroupRepository.renameGroup(groupId, name) && ok
                    if (recolored) ok = GroupRepository.setGroupLook(groupId, null, color) && ok
                    val png = pendingPng
                    if (png != null) ok = GroupRepository.setGroupLook(groupId, png, null) && ok
                    else if (dropPicture) ok = GroupRepository.clearGroupAvatar(groupId) && ok

                    if (!ok) {
                        note = "Не удалось сохранить: в базе нет колонки картинки (нужна миграция)."
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
