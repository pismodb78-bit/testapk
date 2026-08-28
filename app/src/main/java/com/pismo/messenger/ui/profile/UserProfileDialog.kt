package com.pismo.messenger.ui.profile

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pismo.messenger.data.model.Presence
import com.pismo.messenger.data.model.UserProfile
import com.pismo.messenger.data.model.headerText
import com.pismo.messenger.data.repo.PresenceRepository
import com.pismo.messenger.data.repo.ProfileRepository
import com.pismo.messenger.ui.components.UserAvatar
import com.pismo.messenger.ui.theme.PismoColors

/**
 * Чужой профиль в режиме только чтения — порт ProfileForm(uid, readOnly: true).
 *
 * На ПК это та же форма профиля, открытая без права редактирования: баннер,
 * аватар, имя, логин, «о себе» и ссылки. Здесь то же самое, но диалогом:
 * отдельный экран ради четырёх полей на телефоне избыточен.
 */
@Composable
fun UserProfileDialog(
    userId: Int,
    fallbackName: String,
    onDismiss: () -> Unit,
    onOpenChat: (() -> Unit)? = null,
) {
    // Профиль и баннер берём из памяти сразу, если они там есть: диалог
    // должен открываться мгновенно, а не после round-trip к базе.
    var profile by remember(userId) { mutableStateOf(ProfileRepository.cachedProfile(userId)) }
    var banner by remember(userId) { mutableStateOf<ByteArray?>(null) }
    var presence by remember(userId) { mutableStateOf(PresenceRepository.cached(userId)) }
    var loading by remember(userId) { mutableStateOf(profile == null) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(userId) {
        // Даже если что-то показали из кэша, обновляем в фоне: профиль мог
        // измениться с прошлого открытия.
        profile = runCatching { ProfileRepository.load(userId) }.getOrNull() ?: profile
        banner = runCatching { ProfileRepository.banner(userId) }.getOrNull()
        presence = runCatching { PresenceRepository.presenceOf(userId) }.getOrNull() ?: presence
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = null,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Баннер как на ПК: полоса сверху, поверх неё аватар.
                Box(Modifier.fillMaxWidth().height(96.dp)) {
                    val b = banner
                    if (b != null && b.isNotEmpty()) {
                        AsyncImage(
                            model = b,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(PismoColors.Green)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(userId, profile?.let { fullName(it) } ?: fallbackName, 56.dp, presence = presence)
                    Spacer(Modifier.height(0.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(
                            profile?.let { fullName(it) } ?: fallbackName,
                            color = PismoColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        )
                        profile?.login?.takeIf { it.isNotBlank() }?.let {
                            Text("@$it", color = PismoColors.TextMuted, fontSize = 13.sp)
                        }
                        presence?.let { p ->
                            Text(
                                p.headerText(),
                                color = when {
                                    !p.isOnline -> PismoColors.TextMuted
                                    p.isIdle -> PismoColors.Yellow
                                    else -> PismoColors.Green
                                },
                                fontSize = 12.sp,
                            )
                        }
                    }
                }

                if (loading) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(
                        Modifier.height(20.dp), color = PismoColors.Blurple, strokeWidth = 2.dp,
                    )
                }

                // Поля показываем ВСЕГДА, даже пустыми, — как на ПК, где это
                // поля формы, а не появляющиеся строки. Иначе у человека без
                // «о себе» профиль выглядит обрезанным, и непонятно, то ли не
                // заполнено, то ли не загрузилось.
                Spacer(Modifier.height(12.dp))
                Text("О себе", color = PismoColors.TextMuted, fontSize = 11.sp)
                val about = profile?.about.orEmpty()
                Text(
                    about.ifBlank { "не указано" },
                    color = if (about.isBlank()) PismoColors.TextMuted else PismoColors.TextPrimary,
                    fontSize = 14.sp,
                )

                Spacer(Modifier.height(12.dp))
                Text("Ссылки", color = PismoColors.TextMuted, fontSize = 11.sp)
                val links = parseLinks(profile?.socialLinks.orEmpty())
                if (links.isEmpty()) {
                    Text("не указано", color = PismoColors.TextMuted, fontSize = 14.sp)
                } else {
                    // На ПК ссылки хранятся строками «Название|адрес». Показывать их
                    // сырым текстом, как было, — значит заставить переписывать адрес
                    // руками. Разбираем и открываем нажатием.
                    links.forEach { (label, url) ->
                        Text(
                            if (label.isBlank()) url else "$label — $url",
                            color = PismoColors.Cyan,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .padding(vertical = 3.dp)
                                .clickable { runCatching { uriHandler.openUri(url) } },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (onOpenChat != null) {
                TextButton(onClick = onOpenChat) {
                    Text("Написать", color = PismoColors.Cyan)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = PismoColors.TextMuted) }
        },
    )
}

/**
 * Разбирает поле ссылок профиля. Формат тот же, что на ПК: по строке на
 * ссылку, «Название|адрес»; название необязательно. Адрес без схемы получает
 * https:// — иначе браузер откажется его открывать.
 */
private fun parseLinks(raw: String): List<Pair<String, String>> =
    raw.split('\n')
        .mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty()) return@mapNotNull null
            val i = t.indexOf('|')
            val label = if (i >= 0) t.substring(0, i).trim() else ""
            var url = if (i >= 0) t.substring(i + 1).trim() else t
            if (url.isEmpty()) return@mapNotNull null
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url
            label to url
        }

private fun fullName(p: UserProfile): String =
    "${p.name} ${p.surname}".trim().ifBlank { p.login }
