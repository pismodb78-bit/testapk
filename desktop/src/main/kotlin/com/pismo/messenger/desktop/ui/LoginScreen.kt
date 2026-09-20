package com.pismo.messenger.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.data.repo.AuthRepository
import kotlinx.coroutines.launch

/**
 * Вход. Проверка пароля, счётчик неудачных попыток и обновление старых
 * хешей — всё в общем AuthRepository, который здесь не менялся ни на
 * строку: пароль обязан проверяться одинаково на всех клиентах.
 */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {

    var login by remember { mutableStateOf(Prefs.savedLogin) }
    var password by remember { mutableStateOf(if (Prefs.rememberMe) Prefs.savedPassword else "") }
    var remember by remember { mutableStateOf(Prefs.rememberMe) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy || login.isBlank() || password.isBlank()) return
        busy = true
        error = null
        scope.launch {
            when (val r = AuthRepository.login(login.trim(), password)) {
                is AuthRepository.LoginResult.Success -> {
                    Prefs.rememberMe = remember
                    if (remember) {
                        Prefs.savedLogin = login.trim()
                        Prefs.savedPassword = password
                    } else Prefs.clearSavedCredentials()
                    onLoggedIn()
                }
                is AuthRepository.LoginResult.BadCredentials ->
                    error = "Неверный логин или пароль"
                is AuthRepository.LoginResult.Locked ->
                    error = "Слишком много попыток. Подождите ${r.seconds} с"
                is AuthRepository.LoginResult.Error ->
                    error = "Не удалось войти: ${r.message}"
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PISMO", fontSize = 34.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = login,
            onValueChange = { login = it },
            label = { Text("Логин") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.width(340.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.width(340.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.width(340.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = remember, onCheckedChange = { remember = it }, enabled = !busy)
            Text("Запомнить меня")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { /* настройки подключения */ }, enabled = false) {
                Text("${Prefs.dbHost}:${Prefs.dbPort}", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.width(340.dp)) {
            if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
            else Text("Войти")
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
