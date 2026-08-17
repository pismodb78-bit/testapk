package com.pismo.messenger.ui.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.data.model.FriendEntry
import com.pismo.messenger.data.model.Presence
import com.pismo.messenger.data.repo.FriendsRepository
import com.pismo.messenger.data.repo.PresenceRepository
import com.pismo.messenger.ui.components.LetterAvatar
import com.pismo.messenger.ui.components.UserAvatar
import com.pismo.messenger.ui.components.UnreadBadge
import com.pismo.messenger.ui.login.PismoField
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Друзья, заявки и поиск — порт FriendsAddForm.cs и раздела «Друзья»
 * из ПК-версии.
 *
 * Раздел важнее, чем кажется: список личных диалогов на ПК показывает
 * только друзей и тех, с кем уже была переписка (см. LoadConversations),
 * поэтому без друзей новый собеседник в списке просто не появится.
 */
@Composable
fun FriendsScreen(onOpenChat: (Int, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }

    var friends by remember { mutableStateOf<List<FriendEntry>>(emptyList()) }
    var incoming by remember { mutableStateOf<List<FriendEntry>>(emptyList()) }
    var outgoing by remember { mutableStateOf<List<FriendEntry>>(emptyList()) }
    var found by remember { mutableStateOf<List<FriendEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    // Статусы друзей для точек на аватарках — такой же лёгкий запрос
    // раз в 6 секунд, как в списке чатов и на ПК.
    var presence by remember { mutableStateOf<Map<Int, Presence>>(emptyMap()) }

    suspend fun reload() {
        FriendsRepository.ensureSchema()
        runCatching {
            friends = FriendsRepository.friends()
            incoming = FriendsRepository.incomingRequests()
            outgoing = FriendsRepository.outgoingRequests()
        }.onFailure { status = it.message.orEmpty() }
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(Unit) {
        while (isActive) {
            val ids = friends.map { it.userId }
            if (ids.isNotEmpty()) {
                runCatching { presence = PresenceRepository.presenceFor(ids) }
            }
            delay(6000)
        }
    }

    Column(Modifier.fillMaxSize().background(PismoColors.BgSidebar)) {
        TabRow(
            selectedTabIndex = tab,
            containerColor = PismoColors.BgDarkest,
            contentColor = PismoColors.Blurple,
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = {
                Text("Друзья (${friends.size})", fontSize = 13.sp)
            })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Заявки", fontSize = 13.sp)
                    if (incoming.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        UnreadBadge(incoming.size)
                    }
                }
            })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = {
                Text("Поиск", fontSize = 13.sp)
            })
        }

        when (tab) {
            0 -> LazyColumn(Modifier.fillMaxSize()) {
                if (friends.isEmpty()) {
                    item { EmptyHint("Друзей пока нет. Найдите их во вкладке «Поиск».") }
                }
                items(friends, key = { it.userId }) { f ->
                    PersonRow(f, presence[f.userId]) {
                        IconButton(onClick = { onOpenChat(f.userId, f.name) }) {
                            Icon(Icons.Default.Chat, "Написать", tint = PismoColors.Blurple)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                FriendsRepository.remove(f.userId)
                                reload()
                            }
                        }) {
                            Icon(Icons.Default.PersonRemove, "Удалить из друзей", tint = PismoColors.Red)
                        }
                    }
                }
            }

            1 -> LazyColumn(Modifier.fillMaxSize()) {
                item { SectionLabel("ВХОДЯЩИЕ") }
                if (incoming.isEmpty()) item { EmptyHint("Входящих заявок нет.") }
                items(incoming, key = { "in${it.userId}" }) { f ->
                    PersonRow(f) {
                        IconButton(onClick = {
                            scope.launch { FriendsRepository.accept(f.userId); reload() }
                        }) {
                            Icon(Icons.Default.Check, "Принять", tint = PismoColors.Green)
                        }
                        IconButton(onClick = {
                            scope.launch { FriendsRepository.decline(f.userId); reload() }
                        }) {
                            Icon(Icons.Default.Close, "Отклонить", tint = PismoColors.Red)
                        }
                    }
                }

                item { SectionLabel("ИСХОДЯЩИЕ") }
                if (outgoing.isEmpty()) item { EmptyHint("Исходящих заявок нет.") }
                items(outgoing, key = { "out${it.userId}" }) { f ->
                    PersonRow(f) {
                        Text("ожидает", color = PismoColors.TextMuted, fontSize = 12.sp)
                        IconButton(onClick = {
                            scope.launch { FriendsRepository.remove(f.userId); reload() }
                        }) {
                            Icon(Icons.Default.Close, "Отменить", tint = PismoColors.TextMuted)
                        }
                    }
                }
            }

            else -> Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PismoField(
                        value = query,
                        onValueChange = {
                            query = it
                            scope.launch {
                                found = if (it.trim().length >= 2) {
                                    FriendsRepository.searchUsers(it)
                                } else emptyList()
                            }
                        },
                        label = "Логин, имя или фамилия",
                        modifier = Modifier.weight(1f),
                    )
                }

                if (status.isNotEmpty()) {
                    Text(status, color = PismoColors.Green, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }

                LazyColumn(Modifier.fillMaxSize()) {
                    if (query.trim().length < 2) {
                        item { EmptyHint("Введите минимум 2 символа.") }
                    } else if (found.isEmpty()) {
                        item { EmptyHint("Никого не найдено.") }
                    }
                    items(found, key = { "s${it.userId}" }) { f ->
                        PersonRow(f) {
                            IconButton(onClick = {
                                scope.launch {
                                    FriendsRepository.sendRequest(f.userId)
                                    status = "Заявка отправлена: ${f.name}"
                                    reload()
                                }
                            }) {
                                Icon(Icons.Default.PersonAdd, "В друзья", tint = PismoColors.Green)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonRow(
    f: FriendEntry,
    presence: Presence? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(f.userId, f.name, 40.dp, presence = presence)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, color = PismoColors.TextPrimary, fontSize = 15.sp)
            Text("@${f.login}", color = PismoColors.TextMuted, fontSize = 12.sp)
        }
        actions()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = PismoColors.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        color = PismoColors.TextMuted,
        fontSize = 13.sp,
        modifier = Modifier.padding(16.dp),
    )
}
