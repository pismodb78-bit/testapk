package com.pismo.messenger.ui.servers

import com.pismo.messenger.ui.profile.UserProfileDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.pismo.messenger.core.parseHexColor
import com.pismo.messenger.data.model.Presence
import com.pismo.messenger.data.model.ServerMemberRow
import com.pismo.messenger.data.model.ServerPermissions
import com.pismo.messenger.data.model.ServerRole
import com.pismo.messenger.data.repo.PresenceRepository
import com.pismo.messenger.data.repo.ServerRepository
import com.pismo.messenger.ui.components.LetterAvatar
import com.pismo.messenger.ui.components.UserAvatar
import com.pismo.messenger.ui.components.Pill
import com.pismo.messenger.ui.login.PismoField
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Участники, роли и баны сервера — порт соответствующих панелей
 * ServersForm.cs. Права проверяются как на ПК: владелец может всё,
 * остальным разрешено то, что даёт назначенная роль.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerMembersScreen(serverId: Int, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }

    var members by remember { mutableStateOf<List<ServerMemberRow>>(emptyList()) }
    var roles by remember { mutableStateOf<List<ServerRole>>(emptyList()) }
    var banned by remember { mutableStateOf<List<ServerMemberRow>>(emptyList()) }
    var perms by remember { mutableStateOf(ServerPermissions()) }
    var serverName by remember { mutableStateOf("") }
    var editRole by remember { mutableStateOf<ServerRole?>(null) }
    var creatingRole by remember { mutableStateOf(false) }
    // Точки статуса у участников — то же присутствие, что в списке чатов.
    var presence by remember { mutableStateOf<Map<Int, Presence>>(emptyMap()) }
    var profileOf by remember { mutableStateOf<Pair<Int, String>?>(null) }

    suspend fun reload() {
        runCatching {
            members = ServerRepository.members(serverId)
            roles = ServerRepository.roles(serverId)
            banned = ServerRepository.bannedUsers(serverId)
            perms = ServerRepository.permissions(serverId)
            serverName = ServerRepository.serverInfo(serverId)?.first.orEmpty()
        }
    }

    LaunchedEffect(serverId) { reload() }

    profileOf?.let { (uid, name) ->
        UserProfileDialog(
            userId = uid,
            fallbackName = name,
            onDismiss = { profileOf = null },
        )
    }

    // Ключ — сам список идентификаторов, а НЕ Unit. С Unit цикл стартовал
    // один раз, когда список ещё пуст: первый проход не делал ничего и
    // уходил в шестисекундную паузу, поэтому точки статусов появлялись
    // через шесть секунд после самого списка. Теперь приход данных
    // перезапускает цикл, и запрос уходит сразу же. А до ответа рисуем то,
    // что уже знает общая память — обычно это соседний экран, открытый
    // секунду назад.
    val presenceKey = members.joinToString(",") { it.userId.toString() }
    LaunchedEffect(presenceKey) {
        val ids = members.map { it.userId }
        if (ids.isEmpty()) return@LaunchedEffect
        PresenceRepository.cachedFor(ids).takeIf { it.isNotEmpty() }?.let { presence = it }
        while (isActive) {
            runCatching { presence = PresenceRepository.presenceFor(ids) }
            delay(6000)
        }
    }

    Scaffold(
        containerColor = PismoColors.BgSidebar,
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Column {
                        Text(serverName.ifBlank { "Сервер" }, color = PismoColors.TextPrimary, fontSize = 16.sp)
                        Text(
                            if (perms.isOwner) "Вы владелец"
                            else if (perms.isAdminLike) "Модератор" else "Участник",
                            color = PismoColors.TextMuted, fontSize = 11.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад", tint = PismoColors.TextSecondary)
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = PismoColors.BgDarkest
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = PismoColors.BgDarkest,
                contentColor = PismoColors.Blurple,
            ) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Участники (${members.size})", fontSize = 13.sp) })
                Tab(tab == 1, { tab = 1 }, text = { Text("Роли (${roles.size})", fontSize = 13.sp) })
                Tab(tab == 2, { tab = 2 }, text = { Text("Баны (${banned.size})", fontSize = 13.sp) })
            }

            when (tab) {
                0 -> LazyColumn(Modifier.fillMaxSize()) {
                    items(members, key = { it.userId }) { m ->
                        MemberRow(m, presence[m.userId], roles, perms, onOpenProfile = {
                            // На ПК профиль участника открывается из его меню
                            // (ProfileForm(uid, readOnly: true)); здесь — тапом
                            // по аватарке, меню на телефоне и так занято.
                            profileOf = m.userId to m.name
                        }) { action ->
                            scope.launch {
                                when (action) {
                                    is MemberAction.SetRole ->
                                        ServerRepository.assignRole(serverId, m.userId, action.roleId)
                                    MemberAction.Kick ->
                                        ServerRepository.kickMember(serverId, m.userId, false)
                                    MemberAction.Ban ->
                                        ServerRepository.kickMember(serverId, m.userId, true)
                                }
                                reload()
                            }
                        }
                    }
                }

                1 -> LazyColumn(Modifier.fillMaxSize()) {
                    if (perms.isAdminLike) {
                        item {
                            TextButton(onClick = { creatingRole = true }) {
                                Text("+ Создать роль", color = PismoColors.Green)
                            }
                        }
                    }
                    items(roles, key = { it.id }) { r ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = perms.isAdminLike) { editRole = r }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .width(4.dp)
                                    .height(28.dp)
                                    .background(parseHexColor(r.colorHex))
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, color = parseHexColor(r.colorHex), fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOfNotNull(
                                        if (r.canManage) "управление" else null,
                                        if (r.canBan) "баны" else null,
                                        if (r.canKick) "кик" else null,
                                        if (r.canMute) "мьют" else null,
                                    ).joinToString(", ").ifBlank { "без прав" },
                                    color = PismoColors.TextMuted, fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (banned.isEmpty()) {
                        item {
                            Text("Забаненных нет.", color = PismoColors.TextMuted,
                                fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                        }
                    }
                    items(banned, key = { it.userId }) { b ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UserAvatar(b.userId, b.name, 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.name, color = PismoColors.TextPrimary, fontSize = 14.sp)
                                Text("@${b.login}", color = PismoColors.TextMuted, fontSize = 12.sp)
                            }
                            if (perms.canBan) {
                                TextButton(onClick = {
                                    scope.launch { ServerRepository.unban(serverId, b.userId); reload() }
                                }) {
                                    Text("Разбанить", color = PismoColors.Green, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val dialogRole = editRole
    if (dialogRole != null || creatingRole) {
        RoleDialog(
            role = dialogRole,
            onDismiss = { editRole = null; creatingRole = false },
            onSave = { updated ->
                scope.launch {
                    if (dialogRole == null) ServerRepository.createRole(serverId, updated)
                    else ServerRepository.updateRole(updated.copy(id = dialogRole.id))
                    editRole = null; creatingRole = false
                    reload()
                }
            },
            onDelete = if (dialogRole == null) null else {
                {
                    scope.launch {
                        ServerRepository.deleteRole(dialogRole.id)
                        editRole = null
                        reload()
                    }
                }
            },
        )
    }
}

private sealed interface MemberAction {
    data class SetRole(val roleId: Int?) : MemberAction
    data object Kick : MemberAction
    data object Ban : MemberAction
}

@Composable
private fun MemberRow(
    m: ServerMemberRow,
    presence: Presence?,
    roles: List<ServerRole>,
    perms: ServerPermissions,
    onOpenProfile: () -> Unit = {},
    onAction: (MemberAction) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val canModerate = (perms.canKick || perms.canBan || perms.isAdminLike) && !m.isOwner

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = canModerate) { menu = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clickable(onClick = onOpenProfile)) {
                UserAvatar(m.userId, m.name, 38.dp, presence = presence)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (m.isOwner) "👑 " else "") + m.name,
                    color = PismoColors.TextPrimary,
                    fontSize = 15.sp,
                )
                Text("@${m.login}", color = PismoColors.TextMuted, fontSize = 12.sp)
            }
            if (m.roleName.isNotBlank()) Pill(m.roleName)
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (perms.isAdminLike) {
                DropdownMenuItem(
                    text = { Text("Снять роль") },
                    onClick = { onAction(MemberAction.SetRole(null)); menu = false },
                )
                roles.forEach { r ->
                    DropdownMenuItem(
                        text = { Text("Выдать роль: ${r.name}") },
                        onClick = { onAction(MemberAction.SetRole(r.id)); menu = false },
                    )
                }
            }
            if (perms.canKick) {
                DropdownMenuItem(
                    text = { Text("Исключить", color = PismoColors.Yellow) },
                    onClick = { onAction(MemberAction.Kick); menu = false },
                )
            }
            if (perms.canBan) {
                DropdownMenuItem(
                    text = { Text("Забанить", color = PismoColors.Red) },
                    onClick = { onAction(MemberAction.Ban); menu = false },
                )
            }
        }
    }
}

@Composable
private fun RoleDialog(
    role: ServerRole?,
    onDismiss: () -> Unit,
    onSave: (ServerRole) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(role?.name.orEmpty()) }
    var color by remember { mutableStateOf(role?.colorHex ?: "#5865F2") }
    var canBan by remember { mutableStateOf(role?.canBan ?: false) }
    var canKick by remember { mutableStateOf(role?.canKick ?: false) }
    var canMute by remember { mutableStateOf(role?.canMute ?: false) }
    var canManage by remember { mutableStateOf(role?.canManage ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = { Text(if (role == null) "Новая роль" else "Роль «${role.name}»", color = PismoColors.TextPrimary) },
        text = {
            Column {
                PismoField(name, { name = it }, "Название")
                Spacer(Modifier.height(8.dp))
                PismoField(color, { color = it }, "Цвет (#RRGGBB)")
                Spacer(Modifier.height(8.dp))
                PermCheck("Управление сервером", canManage) { canManage = it }
                PermCheck("Банить", canBan) { canBan = it }
                PermCheck("Исключать", canKick) { canKick = it }
                PermCheck("Мьютить", canMute) { canMute = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onSave(
                        ServerRole(
                            id = role?.id ?: 0,
                            name = name.trim(),
                            colorHex = color.trim(),
                            canBan = canBan, canKick = canKick,
                            canMute = canMute, canManage = canManage,
                            position = role?.position ?: 0,
                        )
                    )
                }
            }) { Text("Сохранить", color = PismoColors.Blurple) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Удалить", color = PismoColors.Red) }
                }
                TextButton(onClick = onDismiss) { Text("Отмена", color = PismoColors.TextMuted) }
            }
        },
    )
}

@Composable
private fun PermCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, color = PismoColors.TextSecondary, fontSize = 14.sp)
    }
}
