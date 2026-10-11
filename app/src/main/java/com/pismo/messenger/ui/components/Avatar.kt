package com.pismo.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pismo.messenger.data.model.Presence
import com.pismo.messenger.data.repo.GroupRepository
import com.pismo.messenger.data.repo.ProfileRepository
import com.pismo.messenger.data.repo.ServerRepository
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

/**
 * Значок группы: картинка из group_chats.avatar_data, если её поставили,
 * иначе буква на цвете из avatar_color — как было раньше.
 *
 * [hasAvatar] приходит из списка чатов: саму картинку там не тянут, чтобы
 * не возить мегабайты ради списка. [version] — счётчик, по которому
 * значок перечитывается после смены картинки.
 */
@Composable
fun GroupAvatar(
    groupId: Int,
    name: String,
    colorHex: String,
    size: Dp = 40.dp,
    hasAvatar: Boolean = false,
    version: Int = 0,
) {
    var bytes by remember(groupId) {
        mutableStateOf(if (hasAvatar) GroupRepository.cachedGroupAvatar(groupId) else null)
    }

    LaunchedEffect(groupId, hasAvatar, version) {
        bytes = if (hasAvatar) {
            runCatching { GroupRepository.groupAvatar(groupId) }.getOrNull()
        } else null
    }

    val data = bytes
    if (data != null && data.isNotEmpty()) {
        AsyncImage(
            model = data,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(com.pismo.messenger.core.parseHexColor(colorHex)),
        )
        return
    }

    LetterAvatar(
        id = groupId,
        name = name,
        size = size,
        color = com.pismo.messenger.core.parseHexColor(colorHex),
    )
}

/**
 * Значок сервера: картинка из servers.avatar_data, если владелец её
 * залил, иначе две буквы названия на выбранном им цвете.
 *
 * [plateColor] перебивает цвет — им рельс подсвечивает выбранный сервер.
 * [version] — счётчик, по которому значок перечитывается после смены
 * картинки: байты лежат в памяти, и без него остался бы прежний вид.
 */
@Composable
fun ServerAvatar(
    serverId: Int,
    name: String,
    colorHex: String = "",
    hasAvatar: Boolean = false,
    size: Dp = 48.dp,
    shape: Shape = CircleShape,
    plateColor: Color? = null,
    version: Int = 0,
) {
    var bytes by remember(serverId) {
        mutableStateOf(if (hasAvatar) ServerRepository.cachedServerAvatar(serverId) else null)
    }

    LaunchedEffect(serverId, hasAvatar, version) {
        bytes = if (hasAvatar) {
            runCatching { ServerRepository.serverAvatar(serverId) }.getOrNull()
        } else null
    }

    val data = bytes
    if (data != null && data.isNotEmpty()) {
        // Под картинкой — выбранный цвет сервера, а если его нет, то ничего:
        // пусть сквозь прозрачные места просвечивает фон приложения. Раньше
        // здесь был BgElevated, и картинка с прозрачностью сидела на светлом
        // пятне, которое выбивалось из тёмного рельса.
        val backdrop = if (colorHex.isBlank()) Color.Transparent
        else com.pismo.messenger.core.parseHexColor(colorHex)
        AsyncImage(
            model = data,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(backdrop),
        )
        return
    }

    val plain = plateColor == null && colorHex.isBlank()
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(
                plateColor ?: if (colorHex.isBlank()) PismoColors.BgSidebar
                else com.pismo.messenger.core.parseHexColor(colorHex)
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(2).uppercase(),
            color = if (plain) PismoColors.TextPrimary else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.31f).sp,
        )
    }
}
