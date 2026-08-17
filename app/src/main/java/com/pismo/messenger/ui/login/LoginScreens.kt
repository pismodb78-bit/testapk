package com.pismo.messenger.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.data.repo.AuthRepository
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onRegister: () -> Unit,
    onSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf(Prefs.savedLogin) }
    var password by remember { mutableStateOf(Prefs.savedPassword) }
    var remember_ by remember { mutableStateOf(Prefs.rememberMe) }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }

    fun doLogin() {
        if (login.isBlank() || password.isBlank()) {
            error = "Заполните логин и пароль."
            return
        }
        busy = true; error = ""; info = ""
        scope.launch {
            when (val result = AuthRepository.login(login.trim(), password)) {
                is AuthRepository.LoginResult.Success -> {
                    Prefs.rememberMe = remember_
                    if (remember_) {
                        Prefs.savedLogin = login.trim()
                        Prefs.savedPassword = password
                    } else {
                        Prefs.clearSavedCredentials()
                    }
                    busy = false
                    onLoggedIn()
                }
                is AuthRepository.LoginResult.BadCredentials -> {
                    busy = false
                    error = "Неверный логин или пароль."
                }
                is AuthRepository.LoginResult.Error -> {
                    busy = false
                    error = "Ошибка БД: ${result.message}"
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PismoColors.BgMain),
    ) {
        // statusBarsPadding обязателен: без него кнопка рисуется ПОД
        // системной строкой состояния, и все тапы по ней забирает системный
        // UI — визуально кнопка есть, а нажать её нельзя.
        IconButton(
            onClick = onSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(Icons.Default.Settings, "Настройки подключения", tint = PismoColors.TextMuted)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .background(PismoColors.Blurple, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("P", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black)
            }

            Spacer(Modifier.height(20.dp))
            Text("PISMO", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text(
                "С возвращением!",
                color = PismoColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(28.dp))

            PismoField(
                value = login,
                onValueChange = { login = it; error = "" },
                label = "Логин",
                enabled = !busy,
            )

            Spacer(Modifier.height(12.dp))

            PismoField(
                value = password,
                onValueChange = { password = it; error = "" },
                label = "Пароль",
                enabled = !busy,
                keyboardType = KeyboardType.Password,
                visualTransformation = if (showPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            tint = PismoColors.TextMuted,
                        )
                    }
                },
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = remember_,
                    onCheckedChange = { remember_ = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = PismoColors.Blurple,
                        uncheckedColor = PismoColors.TextMuted,
                    ),
                )
                Text("Запомнить меня", color = PismoColors.TextSecondary, fontSize = 14.sp)
            }

            if (error.isNotEmpty()) {
                Text(
                    error,
                    color = PismoColors.Red,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            if (info.isNotEmpty()) {
                Text(
                    info,
                    color = PismoColors.Green,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { doLogin() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PismoColors.Blurple),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Войти", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            TextButton(onClick = onRegister, enabled = !busy) {
                Text("Нет аккаунта? Зарегистрироваться", color = PismoColors.Cyan, fontSize = 14.sp)
            }

            if (Prefs.savedLogin.isNotEmpty()) {
                TextButton(onClick = {
                    Prefs.clearSavedCredentials()
                    login = ""; password = ""; remember_ = false
                    info = "Сохранённые данные удалены."
                }) {
                    Text("Забыть сохранённый вход", color = PismoColors.TextMuted, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun RegisterScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var surname by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var success by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PismoColors.BgMain)
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Text("Создать аккаунт", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(24.dp))

        PismoField(name, { name = it; error = "" }, "Имя", !busy)
        Spacer(Modifier.height(12.dp))
        PismoField(surname, { surname = it; error = "" }, "Фамилия", !busy)
        Spacer(Modifier.height(12.dp))
        PismoField(login, { login = it; error = "" }, "Логин", !busy)
        Spacer(Modifier.height(12.dp))
        PismoField(
            password, { password = it; error = "" }, "Пароль (минимум 8 символов)", !busy,
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
        )

        if (error.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = PismoColors.Red, fontSize = 13.sp)
        }
        if (success.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(success, color = PismoColors.Green, fontSize = 13.sp)
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                busy = true; error = ""; success = ""
                scope.launch {
                    when (val r = AuthRepository.register(name, surname, login, password)) {
                        is AuthRepository.RegisterResult.Success -> {
                            busy = false
                            success = "Аккаунт создан! Теперь войдите."
                            onDone()
                        }
                        is AuthRepository.RegisterResult.Invalid -> {
                            busy = false; error = r.message
                        }
                        is AuthRepository.RegisterResult.Error -> {
                            busy = false; error = "Ошибка БД: ${r.message}"
                        }
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PismoColors.Blurple),
        ) {
            Text("Зарегистрироваться", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        TextButton(onClick = onDone) {
            Text("Назад ко входу", color = PismoColors.TextMuted)
        }
    }
}

/** Единое оформление поля ввода — тёмное, как в ПК-версии. */
@Composable
fun PismoField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = PismoColors.TextMuted) },
        enabled = enabled,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = trailing,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = PismoColors.BgDarkest,
            unfocusedContainerColor = PismoColors.BgDarkest,
            disabledContainerColor = PismoColors.BgDarkest,
            focusedTextColor = PismoColors.TextPrimary,
            unfocusedTextColor = PismoColors.TextPrimary,
            focusedBorderColor = PismoColors.Blurple,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = PismoColors.Blurple,
        ),
    )
}
