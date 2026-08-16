package com.pismo.messenger.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.pismo.messenger.data.repo.FriendsRepository
import com.pismo.messenger.ui.chats.ChatListScreen
import com.pismo.messenger.ui.components.UnreadBadge
import com.pismo.messenger.ui.friends.FriendsScreen
import com.pismo.messenger.ui.profile.ProfileScreen
import com.pismo.messenger.ui.servers.ServersScreen
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Главный экран с нижней навигацией.
 *
 * На ПК всё это — колонки одного окна: рельс серверов, сайдбар чатов,
 * список друзей. На телефоне ширины на такое нет, поэтому разделы
 * разнесены по вкладкам — это единственное существенное отличие
 * компоновки от десктопа.
 */
@Composable
fun HomeScreen(
    onOpenChat: (Int, String) -> Unit,
    onOpenGroup: (Int, String) -> Unit,
    onOpenChannel: (Int, Int, String) -> Unit,
    onJoinVoice: (Int, String) -> Unit,
    onOpenMembers: (Int) -> Unit,
    onSettings: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var friendRequests by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (isActive) {
            runCatching { friendRequests = FriendsRepository.incomingCount() }
            delay(15_000)
        }
    }

    Scaffold(
        containerColor = PismoColors.BgSidebar,
        bottomBar = {
            NavigationBar(containerColor = PismoColors.BgDarkest) {
                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    indicatorColor = PismoColors.Blurple,
                    unselectedIconColor = PismoColors.TextMuted,
                    unselectedTextColor = PismoColors.TextMuted,
                )

                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Chat, "Чаты") },
                    label = { Text("Чаты", fontSize = 11.sp) },
                    colors = colors,
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = {
                        Box {
                            Icon(Icons.Default.Group, "Друзья")
                            if (friendRequests > 0) UnreadBadge(friendRequests)
                        }
                    },
                    label = { Text("Друзья", fontSize = 11.sp) },
                    colors = colors,
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Dns, "Серверы") },
                    label = { Text("Серверы", fontSize = 11.sp) },
                    colors = colors,
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.Person, "Профиль") },
                    label = { Text("Профиль", fontSize = 11.sp) },
                    colors = colors,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> ChatListScreen(
                    onOpenChat = onOpenChat,
                    onOpenGroup = onOpenGroup,
                    onSettings = onSettings,
                    onLoggedOut = onLoggedOut,
                )
                1 -> FriendsScreen(onOpenChat = onOpenChat)
                2 -> ServersScreen(
                    onOpenChannel = onOpenChannel,
                    onJoinVoice = onJoinVoice,
                    onOpenMembers = onOpenMembers,
                )
                else -> ProfileScreen(
                    onSettings = onSettings,
                    onLoggedOut = onLoggedOut,
                )
            }
        }
    }
}
