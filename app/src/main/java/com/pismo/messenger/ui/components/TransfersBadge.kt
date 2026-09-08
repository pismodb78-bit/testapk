package com.pismo.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.pismo.messenger.data.Transfers
import com.pismo.messenger.ui.theme.PismoColors

/**
 * Кружок с идущими передачами — общий на всё приложение.
 *
 * Полосы над строкой ввода показывают только то, что грузится в ОТКРЫТУЮ
 * переписку. А передачи живут в области процесса и продолжаются после
 * выхода из чата: без общего указателя человек, ушедший в список чатов,
 * не видел ни того, что отправка ещё идёт, ни того, что она сорвалась.
 *
 * Кружок появляется, только когда есть что показывать, и по нажатию
 * раскрывает список: имя, направление, доля и отмена для каждой.
 *
 * Стоит слева: справа внизу на списке чатов живёт кнопка новой переписки,
 * а над ней — строка ввода в чате.
 */
@Composable
fun TransfersBadge() {
    val items by Transfers.active.collectAsState()
    if (items.isEmpty()) return

    var open by remember { mutableStateOf(false) }
    val failed = items.any { it.error != null }
    val overall = items.map { it.progress }.average().toFloat()

    // Пустой Box поверх всего ничего не перехватывает: нажатия проходят
    // насквозь, кликается только сам кружок.
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 96.dp)
                .size(52.dp)
                .clip(CircleShape)
                .background(PismoColors.BgElevated)
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { overall },
                modifier = Modifier.size(44.dp),
                color = if (failed) PismoColors.Red else PismoColors.Blurple,
                trackColor = PismoColors.BgMain,
            )
            Text(
                "${items.size}",
                color = if (failed) PismoColors.Red else PismoColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Surface(shape = RoundedCornerShape(14.dp), color = PismoColors.BgElevated) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Передачи",
                        color = PismoColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    items.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (t.download) "↓" else "↑",
                                color = if (t.error != null) PismoColors.Red else PismoColors.Blurple,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.fileName,
                                    color = PismoColors.TextPrimary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                )
                                Text(
                                    when {
                                        t.error != null -> t.error
                                        t.waiting -> "В очереди"
                                        else -> "${(t.progress * 100).toInt()}%"
                                    },
                                    color = if (t.error != null) PismoColors.Red
                                            else PismoColors.TextMuted,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                )
                                if (t.error == null) {
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { t.progress },
                                        modifier = Modifier.fillMaxWidth().height(3.dp),
                                        color = PismoColors.Blurple,
                                        trackColor = PismoColors.BgMain,
                                    )
                                }
                            }
                            IconButton(onClick = { Transfers.cancel(t.id) }) {
                                Icon(Icons.Default.Close, "Отменить", tint = PismoColors.TextMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}
