package com.pismo.messenger.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.model.DmPrivacy
import com.pismo.messenger.data.model.UserProfile
import com.pismo.messenger.data.repo.AuthRepository
import com.pismo.messenger.data.repo.FriendsRepository
import com.pismo.messenger.data.repo.PresenceRepository
import com.pismo.messenger.data.repo.ProfileRepository
import com.pismo.messenger.net.SignalingClient
import com.pismo.messenger.ui.components.LetterAvatar
import com.pismo.messenger.ui.components.UserAvatar
import com.pismo.messenger.ui.login.PismoField
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.launch

/**
 * Профиль, приватность ЛС и смена пароля — порт ProfileForm.cs и
 * ChangePasswordForm.cs.
 */
@Composable
fun ProfileScreen(onSettings: () -> Unit, onLoggedOut: () -> Unit) {
    val scope = rememberCoroutineScope()

    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var name by remember { mutableStateOf("") }
    var surname by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var links by remember { mutableStateOf("") }
    var friendsOnly by remember { mutableStateOf(false) }

    var oldPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var confirmPass by remember { mutableStateOf("") }

    var status by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    fun report(message: String, error: Boolean) {
        status = message
        isError = error
    }

    var avatarVersion by remember { mutableStateOf(0) }
    val context = LocalContext.current

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            when {
                data == null -> report("Не удалось прочитать изображение.", true)
                // Аватар лежит в БД как BLOB и тянется при каждом показе списка,
                // поэтому крупные файлы бьют по скорости всем участникам.
                data.size > 2 * 1024 * 1024 ->
                    report("Файл больше 2 МБ — выберите изображение поменьше.", true)
                ProfileRepository.setAvatar(data) -> {
                    avatarVersion++
                    report("Аватар обновлён.", false)
                }
                else -> report("Не удалось сохранить аватар.", true)
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching {
            val p = ProfileRepository.load(UserSession.effectiveId)
            profile = p
            if (p != null) {
                name = p.name; surname = p.surname; login = p.login
                about = p.about; links = p.socialLinks
            }
            friendsOnly = FriendsRepository.dmPrivacy(UserSession.effectiveId) ==
                    DmPrivacy.FRIENDS_ONLY
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(PismoColors.BgMain)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable { avatarPicker.launch("image/*") }) {
                key(avatarVersion) {
                    UserAvatar(UserSession.effectiveId, UserSession.effectiveName, 64.dp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    UserSession.effectiveName,
                    color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    if (UserSession.isAdmin) "Роль: администратор" else "Роль: пользователь",
                    color = PismoColors.TextMuted, fontSize = 13.sp,
                )
                if (UserSession.isImpersonating) {
                    Text(
                        "Режим «войти за пользователя»",
                        color = PismoColors.Yellow, fontSize = 12.sp,
                    )
                }
                Row {
                    TextButton(onClick = { avatarPicker.launch("image/*") }) {
                        Text("Сменить аватар", color = PismoColors.Cyan, fontSize = 13.sp)
                    }
                    TextButton(onClick = {
                        scope.launch {
                            if (ProfileRepository.setAvatar(null)) {
                                avatarVersion++
                                report("Аватар удалён.", false)
                            }
                        }
                    }) {
                        Text("Убрать", color = PismoColors.TextMuted, fontSize = 13.sp)
                    }
                }
            }
        }

        if (UserSession.isImpersonating) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    UserSession.stopImpersonating()
                    report("Вернулись в свой аккаунт.", false)
                },
                colors = ButtonDefaults.buttonColors(containerColor = PismoColors.Red),
                shape = RoundedCornerShape(8.dp),
            ) { Text("← Вернуться к себе", color = Color.White) }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("Профиль")
        PismoField(name, { name = it }, "Имя")
        Spacer(Modifier.height(8.dp))
        PismoField(surname, { surname = it }, "Фамилия")
        Spacer(Modifier.height(8.dp))
        PismoField(login, { login = it }, "Логин")
        Spacer(Modifier.height(8.dp))
        PismoField(about, { about = it }, "О себе", singleLine = false)
        Spacer(Modifier.height(8.dp))
        PismoField(links, { links = it }, "Ссылки", singleLine = false)
        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                scope.launch {
                    val p = profile ?: return@launch
                    if (login.isBlank()) {
                        report("Логин не может быть пустым.", true); return@launch
                    }
                    if (ProfileRepository.isLoginTaken(login.trim(), p.id)) {
                        report("Этот логин уже занят.", true); return@launch
                    }
                    val ok = ProfileRepository.save(
                        p.copy(
                            name = name.trim(), surname = surname.trim(),
                            login = login.trim(), about = about, socialLinks = links,
                        )
                    )
                    if (ok) {
                        UserSession.userName = "$name $surname".trim().ifBlank { login }
                        report("Профиль сохранён.", false)
                    } else {
                        report("Не удалось сохранить профиль.", true)
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = PismoColors.Blurple),
            shape = RoundedCornerShape(8.dp),
        ) { Text("Сохранить профиль", color = Color.White) }

        Spacer(Modifier.height(20.dp))
        SectionTitle("Приватность")
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Личные сообщения только от друзей",
                color = PismoColors.TextSecondary, fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = friendsOnly,
                onCheckedChange = {
                    friendsOnly = it
                    scope.launch {
                        FriendsRepository.setDmPrivacy(
                            if (it) DmPrivacy.FRIENDS_ONLY else DmPrivacy.EVERYONE
                        )
                        report("Настройка приватности сохранена.", false)
                    }
                },
                colors = SwitchDefaults.colors(checkedTrackColor = PismoColors.Blurple),
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("Смена пароля")
        PismoField(oldPass, { oldPass = it }, "Старый пароль",
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Spacer(Modifier.height(8.dp))
        PismoField(newPass, { newPass = it }, "Новый пароль",
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Spacer(Modifier.height(8.dp))
        PismoField(confirmPass, { confirmPass = it }, "Повторите новый",
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                scope.launch {
                    when (val r = AuthRepository.changePassword(oldPass, newPass, confirmPass)) {
                        is AuthRepository.RegisterResult.Success -> {
                            oldPass = ""; newPass = ""; confirmPass = ""
                            report("Пароль успешно изменён.", false)
                        }
                        is AuthRepository.RegisterResult.Invalid -> report(r.message, true)
                        is AuthRepository.RegisterResult.Error -> report(r.message, true)
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = PismoColors.Blurple),
            shape = RoundedCornerShape(8.dp),
        ) { Text("Изменить пароль", color = Color.White) }

        if (status.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                status,
                color = if (isError) PismoColors.Red else PismoColors.Green,
                fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PismoColors.BgElevated),
            shape = RoundedCornerShape(8.dp),
        ) { Text("Настройки подключения", color = PismoColors.TextPrimary) }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                scope.launch {
                    runCatching { PresenceRepository.markOffline() }
                    SignalingClient.disconnect()
                    Db.closeAll()
                    Prefs.clearSavedCredentials()
                    UserSession.clear()
                    onLoggedOut()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PismoColors.Red),
            shape = RoundedCornerShape(8.dp),
        ) { Text("Выйти из аккаунта", color = Color.White, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
