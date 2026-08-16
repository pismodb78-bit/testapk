package com.pismo.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pismo.messenger.data.model.Presence
import com.pismo.messenger.data.repo.ProfileRepository
import com.pismo.messenger.ui.theme.PismoColors

/**
 * Аватар пользователя: картинка из users.avatar_data, если она есть,
 * иначе кружок с буквой — как в ПК-версии (AvatarStore + GetAvatarColor).
 *
 * Байты берутся из памяти мгновенно, если список заранее прогрет через
 * ProfileRepository.prefetchAvatars; иначе подгружаются по одному.
 */
@Composable
fun UserAvatar(
    userId: Int,
    name: String,
    size: Dp = 40.dp,
    presence: Presence? = null,
) {
    var bytes by remember(userId) { mutableStateOf(ProfileRepository.cachedAvatar(userId)) }

    LaunchedEffect(userId) {
        if (bytes == null) {
            bytes = runCatching { ProfileRepository.avatar(userId) }.getOrNull()
        }
    }

    val data = bytes
    if (data == null || data.isEmpty()) {
        LetterAvatar(userId, name, size, presence = presence)
        return
    }

    Box(contentAlignment = Alignment.Center) {
        AsyncImage(
            model = data,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(PismoColors.BgElevated),
        )

        if (presence != null) {
            val dot = when {
                presence.isIdle -> PismoColors.Idle
                presence.isOnline -> PismoColors.Online
                else -> PismoColors.Offline
            }
            Box(
                modifier = Modifier
                    .size(size * 0.32f)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(PismoColors.BgSidebar)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
        }
    }
}

/** Аватар группы: цвет из group_chats.avatar_color, буква из названия. */
@Composable
fun GroupAvatar(groupId: Int, name: String, colorHex: String, size: Dp = 40.dp) {
    LetterAvatar(
        id = groupId,
        name = name,
        size = size,
        color = com.pismo.messenger.core.parseHexColor(colorHex),
    )
}
