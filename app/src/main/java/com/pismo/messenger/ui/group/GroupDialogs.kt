package com.pismo.messenger.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.model.GroupMember
import com.pismo.messenger.data.model.UserBrief
import com.pismo.messenger.data.repo.GroupRepository
import com.pismo.messenger.ui.components.LetterAvatar
import com.pismo.messenger.ui.login.PismoField
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.launch

/**
 * Создание группы — порт CreateGroupForm.cs.
 * Создатель автоматически становится админом группы (is_admin=1).
 */
@Composable
fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onCreated: (Int, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<UserBrief>>(emptyList()) }
    val selected = remember { mutableStateListOf<Int>() }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { candidates = GroupRepository.candidatesForNewGroup() }
            .onFailure { error = it.message.orEmpty() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = { Text("Новая группа", color = Color.White) },
        text = {
            Column {
                PismoField(name, { name = it; error = "" }, "Название группы")
                Spacer(Modifier.height(12.dp))
                Text("УЧАСТНИКИ", color = PismoColors.TextMuted, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(candidates, key = { it.id }) { u ->
                        PickRow(
                            id = u.id,
                            name = u.name,
                            login = u.login,
                            checked = selected.contains(u.id),
                            onToggle = {
                                if (selected.contains(u.id)) selected.remove(u.id)
                                else selected.add(u.id)
                            },
                        )
                    }
                }
                if (error.isNotEmpty()) {
                    Text(error, color = PismoColors.Red, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    name.isBlank() -> error = "Введите название группы."
                    selected.isEmpty() -> error = "Выберите хотя бы одного участника."
                    else -> scope.launch {
                        runCatching {
                            val id = GroupRepository.createGroup(name.trim(), selected.toList())
                            if (id > 0) onCreated(id, name.trim()) else error = "Не удалось создать группу."
                        }.onFailure { error = it.message.orEmpty() }
                    }
                }
            }) { Text("Создать", color = PismoColors.Blurple) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = PismoColors.TextMuted) }
        },
    )
}

/**
 * Участники группы — порт GroupMembersForm.cs.
 *
 * Права как на ПК: добавлять может любой участник, исключать — только
 * системный администратор PISMO.
 */
@Composable
fun GroupMembersDialog(
    groupId: Int,
    groupName: String,
    onDismiss: () -> Unit,
    onLeft: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<GroupMember>>(emptyList()) }
    var candidates by remember { mutableStateOf<List<UserBrief>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    val toAdd = remember { mutableStateListOf<Int>() }
    var canDelete by remember { mutableStateOf(false) }

    suspend fun reload() {
        runCatching {
            members = GroupRepository.members(groupId)
            canDelete = GroupRepository.canDeleteGroup(groupId)
        }
    }

    LaunchedEffect(groupId) { reload() }
    LaunchedEffect(adding) {
        if (adding) runCatching { candidates = GroupRepository.candidatesToAdd(groupId) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = {
            Text(
                if (adding) "Добавить участников" else "👥 $groupName (${members.size})",
                color = Color.White,
            )
        },
        text = {
            if (adding) {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(candidates, key = { it.id }) { u ->
                        PickRow(u.id, u.name, u.login, toAdd.contains(u.id)) {
                            if (toAdd.contains(u.id)) toAdd.remove(u.id) else toAdd.add(u.id)
                        }
                    }
                    if (candidates.isEmpty()) {
                        item {
                            Text("Все пользователи уже в этой группе.",
                                color = PismoColors.TextMuted, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(members, key = { it.userId }) { m ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LetterAvatar(m.userId, m.name, 34.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                m.name + if (m.isAdmin) "  ⭐" else "",
                                color = PismoColors.TextPrimary,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                            )
                            // Исключать может только системный администратор.
                            if (UserSession.isAdmin && m.userId != UserSession.effectiveId) {
                                TextButton(onClick = {
                                    scope.launch {
                                        GroupRepository.kickMember(groupId, m.userId)
                                        reload()
                                    }
                                }) {
                                    Text("Исключить", color = PismoColors.Red, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (adding) {
                TextButton(onClick = {
                    scope.launch {
                        GroupRepository.addMembers(groupId, toAdd.toList())
                        toAdd.clear()
                        adding = false
                        reload()
                    }
                }) { Text("Добавить", color = PismoColors.Blurple) }
            } else {
                TextButton(onClick = { adding = true }) {
                    Text("➕ Добавить", color = PismoColors.Blurple)
                }
            }
        },
        dismissButton = {
            Row {
                if (!adding) {
                    TextButton(onClick = {
                        scope.launch {
                            GroupRepository.leaveGroup(groupId)
                            onLeft()
                        }
                    }) { Text("Покинуть", color = PismoColors.Yellow) }

                    if (canDelete) {
                        TextButton(onClick = {
                            scope.launch {
                                GroupRepository.deleteGroup(groupId)
                                onLeft()
                            }
                        }) { Text("Удалить", color = PismoColors.Red) }
                    }
                }
                TextButton(onClick = { if (adding) adding = false else onDismiss() }) {
                    Text(if (adding) "Назад" else "Закрыть", color = PismoColors.TextMuted)
                }
            }
        },
    )
}

@Composable
private fun PickRow(
    id: Int,
    name: String,
    login: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        LetterAvatar(id, name, 30.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name, color = PismoColors.TextPrimary, fontSize = 14.sp)
            Text("@$login", color = PismoColors.TextMuted, fontSize = 11.sp)
        }
    }
}
