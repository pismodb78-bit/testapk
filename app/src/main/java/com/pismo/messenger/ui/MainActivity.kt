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
import com.pismo.messenger.net.SignalingClient
import com.pismo.messenger.ui.chat.ChatScreen
import com.pismo.messenger.ui.chats.ChatListScreen
import com.pismo.messenger.ui.login.LoginScreen
import com.pismo.messenger.ui.login.RegisterScreen
import com.pismo.messenger.ui.settings.SettingsScreen
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
                    val restored = com.pismo.messenger.data.repo.AuthRepository.autoLogin()
                    startRoute = if (restored) Routes.CHATS else Routes.LOGIN
                    if (restored) SignalingClient.connect(UserSession.effectiveId)
                }

                val start = startRoute ?: return@PismoTheme

                NavHost(navController = navController, startDestination = start) {
                    composable(Routes.LOGIN) {
                        LoginScreen(
                            onLoggedIn = {
                                SignalingClient.connect(UserSession.effectiveId)
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
                        ChatListScreen(
                            onOpenChat = { id, name ->
                                navController.navigate(Routes.chat(id, name, false))
                            },
                            onOpenGroup = { id, name ->
                                navController.navigate(Routes.chat(id, name, true))
                            },
                            onSettings = { navController.navigate(Routes.SETTINGS) },
                            onLoggedOut = {
                                navController.navigate(Routes.LOGIN) {
                                    popUpTo(Routes.CHATS) { inclusive = true }
                                }
                            },
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

    fun chat(id: Int, name: String, isGroup: Boolean): String {
        val safe = android.net.Uri.encode(name.ifBlank { "Чат" })
        return "chat/$id/${if (isGroup) 1 else 0}/$safe"
    }
}
