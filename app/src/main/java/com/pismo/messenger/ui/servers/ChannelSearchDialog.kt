package com.pismo.messenger.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.formatDateSeparator
import com.pismo.messenger.core.formatTime
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.repo.ServerRepository
import com.pismo.messenger.ui.login.PismoField
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.delay

/**
 * Поиск по каналу — порт ServersForm_Search.cs.
 *
 * Как и в переписке, ищем на клиенте по расшифрованному тексту: в БД
 * лежит шифртекст, LIKE по нему не сработает в принципе.
 */
@Composable
fun ChannelSearchDialog(
    channelId: Int,
    onDismiss: () -> Unit,
    onJump: (ChatMessage) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        searching = true
        delay(350)   // не гоняем поиск на каждую букву
        results = runCatching { ServerRepository.searchInChannel(channelId, query) }
            .getOrDefault(emptyList())
        searching = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = { Text("Поиск по каналу", color = Color.White) },
        text = {
            Column {
                PismoField(query, { query = it }, "Что ищем")
                Spacer(Modifier.height(8.dp))

                when {
                    query.trim().length < 2 -> Text(
                        "Введите минимум 2 символа.",
                        color = PismoColors.TextMuted, fontSize = 12.sp,
                    )
                    searching -> CircularProgressIndicator(
                        Modifier.height(24.dp), color = PismoColors.Blurple, strokeWidth = 2.dp,
                    )
                    results.isEmpty() -> Text(
                        "Ничего не найдено.", color = PismoColors.TextMuted, fontSize = 13.sp,
                    )
                    else -> {
                        Text(
                            "Найдено: ${results.size}",
                            color = PismoColors.TextMuted, fontSize = 11.sp,
                        )
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(results.size) { index ->
                                val m = results[results.lastIndex - index]   // свежие сверху
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(PismoColors.BgElevated)
                                        .clickable { onJump(m) }
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        "${m.senderName} · ${formatDateSeparator(m.createdAtMs)}, " +
                                                formatTime(m.createdAtMs),
                                        color = PismoColors.Cyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        m.text,
                                        color = PismoColors.TextPrimary,
                                        fontSize = 13.sp,
                                        maxLines = 3,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = PismoColors.TextMuted) }
        },
    )
}
