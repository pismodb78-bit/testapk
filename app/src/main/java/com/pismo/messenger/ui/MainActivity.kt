package com.pismo.messenger.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.service.PollingService
import com.pismo.messenger.net.SignalingClient
import com.pismo.messenger.ui.chat.ChatScreen
import com.pismo.messenger.ui.home.HomeScreen
import com.pismo.messenger.ui.servers.ChannelChatScreen
import com.pismo.messenger.ui.servers.ServerMembersScreen
import com.pismo.messenger.ui.login.LoginScreen
import com.pismo.messenger.ui.login.RegisterScreen
import com.pismo.messenger.ui.settings.SettingsScreen
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import com.pismo.messenger.ui.theme.PismoColors
import com.pismo.messenger.ui.theme.PismoTheme

/**
 * Единственная Activity: экраны — composable-назначения навигации.
 * Звонок вынесен в отдельную CallActivity, чтобы его можно было показать
 * поверх экрана блокировки при входящем вызове.
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStartupPermissions()

        setContent {
            PismoTheme {
                val navController = rememberNavController()
                var startRoute by remember { mutableStateOf<String?>(null) }

                // Пытаемся войти по сохранённым данным до первой отрисовки.
                LaunchedEffect(Unit) {
                    // Автологин ходит в удалённую базу. Если она недоступна,
                    // запрос висит до сокетного таймаута, а startRoute всё это
                    // время null — и экран остаётся пустым серым полем.
                    // Ограничиваем ожидание и в любом случае показываем вход.
                    val restored = withTimeoutOrNull(8_000) {
                        runCatching {
                            com.pismo.messenger.data.repo.AuthRepository.autoLogin()
                        }.getOrDefault(false)
                    } ?: false
                    startRoute = if (restored) Routes.CHATS else Routes.LOGIN
                    if (restored) {
                        SignalingClient.connect(UserSession.effectiveId)
                        // Фоновый сервис был написан, но его никто не
                        // запускал — отсюда и «уведомления не приходят
                        // вообще, тем более в свёрнутом состоянии».
                        PollingService.start(this@MainActivity)
                    }
                }

                // Пока решается, куда идти, показываем индикатор, а не пустоту:
                // раньше здесь был голый return, и экран замирал серым.
                val start = startRoute
                if (start == null) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier
                            .fillMaxSize()
                            .background(PismoColors.BgMain),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = PismoColors.Blurple
                        )
                    }
                    return@PismoTheme
                }

                // Окно входящего звонка — в корне, ВНЕ NavHost: иначе оно
                // пересоздаётся на каждом переходе и не показывается там,
                // где его не повесили руками.
                com.pismo.messenger.ui.call.IncomingCallDialog()

                NavHost(navController = navController, startDestination = start) {
                    composable(Routes.LOGIN) {
                        LoginScreen(
                            onLoggedIn = {
                                SignalingClient.connect(UserSession.effectiveId)
                                PollingService.start(this@MainActivity)
                                navController.navigate(Routes.CHATS) {
                                    popUpTo(Routes.LOGIN) { inclusive = true }
                                }
                            },
                            onRegister = { navController.navigate(Routes.REGISTER) },
                            onSettings = { navController.navigate(Routes.SETTINGS) },
                        )
                    }

                    composable(Routes.REGISTER) {
                        RegisterScreen(onDone = { navController.popBackStack() })
                    }

                    composable(Routes.CHATS) {
                        HomeScreen(
                            onOpenChat = { id, name ->
                                navController.navigate(Routes.chat(id, name, false))
                            },
                            onOpenGroup = { id, name ->
                                navController.navigate(Routes.chat(id, name, true))
                            },
                            onOpenChannel = { serverId, channelId, channelName ->
                                navController.navigate(Routes.channel(serverId, channelId, channelName))
                            },
                            onJoinVoice = { channelId, channelName ->
                                startActivity(
                                    android.content.Intent(
                                        this@MainActivity,
                                        com.pismo.messenger.ui.call.CallActivity::class.java
                                    ).apply {
                                        putExtra(
                                            com.pismo.messenger.ui.call.CallActivity.EXTRA_CHANNEL_ID,
                                            channelId
                                        )
                                        putExtra(
                                            com.pismo.messenger.ui.call.CallActivity.EXTRA_PEER_NAME,
                                            channelName
                                        )
                                    }
                                )
                            },
                            onOpenMembers = { serverId ->
                                navController.navigate(Routes.members(serverId))
                            },
                            onSettings = { navController.navigate(Routes.SETTINGS) },
                            onLoggedOut = {
                                PollingService.stop(this@MainActivity)
                                com.pismo.messenger.call.IncomingCallMonitor.reset()
                                navController.navigate(Routes.LOGIN) {
                                    popUpTo(Routes.CHATS) { inclusive = true }
                                }
                            },
                        )
                    }

                    composable(Routes.CHANNEL_PATTERN) { entry ->
                        val serverId = entry.arguments?.getString("server")?.toIntOrNull()
                            ?: return@composable
                        val channelId = entry.arguments?.getString("channel")?.toIntOrNull()
                            ?: return@composable
                        val name = entry.arguments?.getString("name").orEmpty()
                        ChannelChatScreen(
                            serverId = serverId,
                            channelId = channelId,
                            channelName = name,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.MEMBERS_PATTERN) { entry ->
                        val serverId = entry.arguments?.getString("server")?.toIntOrNull()
                            ?: return@composable
                        ServerMembersScreen(
                            serverId = serverId,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.CHAT_PATTERN) { entry ->
                        val id = entry.arguments?.getString("id")?.toIntOrNull() ?: return@composable
                        val name = entry.arguments?.getString("name").orEmpty()
                        val isGroup = entry.arguments?.getString("group") == "1"
                        ChatScreen(
                            targetId = id,
                            title = name,
                            isGroup = isGroup,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.SETTINGS) {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) SignalingClient.disconnect()
    }

    private fun requestStartupPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        runCatching { permissionLauncher.launch(perms.toTypedArray()) }
    }
}

object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val CHATS = "chats"
    const val SETTINGS = "settings"
    const val CHAT_PATTERN = "chat/{id}/{group}/{name}"
    const val CHANNEL_PATTERN = "channel/{server}/{channel}/{name}"
    const val MEMBERS_PATTERN = "members/{server}"

    fun chat(id: Int, name: String, isGroup: Boolean): String {
        val safe = android.net.Uri.encode(name.ifBlank { "Чат" })
        return "chat/$id/${if (isGroup) 1 else 0}/$safe"
    }

    fun channel(serverId: Int, channelId: Int, name: String): String {
        val safe = android.net.Uri.encode(name.ifBlank { "канал" })
        return "channel/$serverId/$channelId/$safe"
    }

    fun members(serverId: Int): String = "members/$serverId"
}
