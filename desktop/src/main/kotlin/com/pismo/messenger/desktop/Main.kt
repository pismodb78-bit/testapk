package com.pismo.messenger.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.db.DbMigrator
import com.pismo.messenger.desktop.ui.ChatsScreen
import com.pismo.messenger.desktop.ui.LoginScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

fun main() {
    Platform.init()
    application {
        val state = rememberWindowState(size = DpSize(1180.dp, 760.dp))
        Window(
            // Соединения закрываем ДО выхода и не в фоне: иначе сервер
            // держал бы их до своего wait_timeout, а их всего четыре.
            onCloseRequest = {
                runBlocking { runCatching { Db.closeAll() } }
                exitApplication()
            },
            state = state,
            title = "PISMO",
        ) {
            PismoTheme {
                Surface(modifier = Modifier.fillMaxSize()) { Root() }
            }
        }
    }
}

/**
 * Куда смотрит окно. Навигации в настольной версии ровно два состояния —
 * вход и всё остальное, — поэтому библиотека навигации тут была бы
 * тяжелее самой задачи.
 */
@androidx.compose.runtime.Composable
private fun Root() {
    var loggedIn by remember { mutableStateOf(false) }
    var checkedAuto by remember { mutableStateOf(false) }

    // Миграции и автовход — до первого экрана. Обе операции сетевые, и
    // держать на них поток отрисовки нельзя: окно бы не открылось вовсе.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { DbMigrator.run() }
            if (Prefs.rememberMe) {
                runCatching {
                    if (com.pismo.messenger.data.repo.AuthRepository.autoLogin()) loggedIn = true
                }
            }
        }
        checkedAuto = true
    }

    Box(Modifier.fillMaxSize()) {
        when {
            !checkedAuto -> Splash()
            loggedIn && UserSession.userId > 0 -> ChatsScreen(onLogout = {
                UserSession.clear()
                loggedIn = false
            })
            else -> LoginScreen(onLoggedIn = { loggedIn = true })
        }
    }
}

@androidx.compose.runtime.Composable
private fun Splash() {
    Box(Modifier.fillMaxSize()) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
        )
    }
}
