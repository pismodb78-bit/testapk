package com.pismo.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.avatarColor
import com.pismo.messenger.core.avatarLetter
import com.pismo.messenger.data.model.Presence
import com.pismo.messenger.ui.theme.PismoColors

/**
 * Аватар с буквой — прямой аналог кружка с первой буквой имени,
 * который ПК-версия рисует в GetAvatarColor/AddUserCard.
 */
@Composable
fun LetterAvatar(
    id: Int,
    name: String,
    size: Dp = 40.dp,
    color: Color? = null,
    presence: Presence? = null,
) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(color ?: avatarColor(id)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = avatarLetter(name),
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = (size.value * 0.42f).sp,
                textAlign = TextAlign.Center,
            )
        }

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

/** Красный бейдж непрочитанных — «9+» при переполнении, как на ПК. */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier, color: Color = PismoColors.Red) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // 9+ было слишком рано: «сколько именно непрочитано» — весь
            // смысл цифры, а до сотни счёт вполне читаемый.
            text = if (count > 99) "99+" else count.toString(),
            // Бейдж бывает и красным, и светло-серым (просто непрочитанное),
            // а в светлой теме серый становится почти белым — цифру на нём
            // белым не увидеть. Контраст выбираем по яркости подложки.
            color = if (color.luminance() > 0.5f) PismoColors.TextPrimary else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Цветная плитка расширения файла — как BuildFileCard на ПК. */
@Composable
fun FileBadge(badge: String, color: Color, size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = badge,
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = if (badge.length > 3) 9.sp else 12.sp,
        )
    }
}

/** Разделитель дат в ленте сообщений. */
@Composable
fun DateSeparator(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(PismoColors.Divider)
        )
        Text(
            text = text,
            color = PismoColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(PismoColors.Divider)
        )
    }
}

/** Пилюля с текстом — счётчик участников, лимит канала и т.п. */
@Composable
fun Pill(text: String, color: Color = PismoColors.BgDarkest, textColor: Color = PismoColors.TextSecondary) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .border(1.dp, PismoColors.Divider, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text = text, color = textColor, fontSize = 11.sp)
    }
}
