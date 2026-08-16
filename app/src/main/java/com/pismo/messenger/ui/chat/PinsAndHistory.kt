package com.pismo.messenger.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.pismo.messenger.core.formatDateSeparator
import com.pismo.messenger.core.formatTime
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.PinsRepository
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.launch

/**
 * Закреплённые сообщения чата — порт PinsRepository.cs и панели закрепов
 * из ПК-версии. Закреп общий для чата: снять его может любой участник,
 * как и на десктопе.
 */
@Composable
fun PinnedMessagesDialog(
    scopeKind: Scope,
    targetId: Int,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<PinsRepository.PinnedItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    suspend fun reload() {
        items = runCatching {
            when (scopeKind) {
                Scope.DM -> PinsRepository.listDirect(targetId)
                Scope.GROUP -> PinsRepository.listGroup(targetId)
                Scope.SERVER -> PinsRepository.listChannel(targetId)
            }
        }.getOrDefault(emptyList())
        loading = false
    }

    LaunchedEffect(targetId, scopeKind) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PushPin, null, tint = PismoColors.Yellow,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Закреплённые (${items.size})", color = Color.White)
            }
        },
        text = {
            when {
                loading -> Text("Загрузка…", color = PismoColors.TextMuted, fontSize = 13.sp)
                items.isEmpty() -> Text(
                    "Закреплённых сообщений нет.\nДолгое нажатие по сообщению → «Закрепить».",
                    color = PismoColors.TextMuted, fontSize = 13.sp,
                )
                else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(items, key = { it.messageId }) { pin ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Text(pin.sender, color = PismoColors.Cyan, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text(pin.text, color = PismoColors.TextPrimary, fontSize = 14.sp,
                                maxLines = 3)
                            TextButton(onClick = {
                                scope.launch {
                                    PinsRepository.toggle(pin.messageId, scopeKind)
                                    reload()
                                }
                            }) {
                                Text("Открепить", color = PismoColors.Red, fontSize = 12.sp)
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

/**
 * История правок сообщения — таблица message_edits.
 * Прежний текст хранится зашифрованным, расшифровка идёт в репозитории.
 */
@Composable
fun EditHistoryDialog(
    messageId: Int,
    scopeKind: Scope,
    onDismiss: () -> Unit,
) {
    var history by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(messageId) {
        history = runCatching { ChatRepository.editHistory(scopeKind, messageId) }
            .getOrDefault(emptyList())
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = { Text("История изменений", color = Color.White) },
        text = {
            when {
                loading -> Text("Загрузка…", color = PismoColors.TextMuted, fontSize = 13.sp)
                history.isEmpty() -> Text(
                    "Сообщение не редактировалось.",
                    color = PismoColors.TextMuted, fontSize = 13.sp,
                )
                else -> LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(history.size) { index ->
                        val (text, ts) = history[index]
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(
                                "${formatDateSeparator(ts)}, ${formatTime(ts)}",
                                color = PismoColors.TextMuted, fontSize = 11.sp,
                            )
                            Text(text, color = PismoColors.TextPrimary, fontSize = 14.sp)
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

/**
 * Расширенный выбор эмодзи для реакции — Android-аналог EmojiPickerForm.cs.
 * Набор намеренно небольшой и статичный: колонка emoji в БД — VARCHAR(16),
 * а сравнение идёт побайтово (utf8mb4_bin), поэтому составные эмодзи с
 * модификаторами тона кожи туда просто не поместятся.
 */
@Composable
fun EmojiPickerDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val emojis = remember {
        listOf(
            "👍", "👎", "❤️", "🔥", "😂", "😊", "😍", "🤔",
            "😮", "😢", "😡", "🎉", "👏", "🙏", "💯", "✅",
            "❌", "⚡", "🚀", "💡", "📌", "👀", "🤝", "🥳",
            "😅", "😎", "🤯", "🫡", "🙌", "💀", "🍕", "☕",
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = { Text("Выберите реакцию", color = Color.White) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier.heightIn(max = 260.dp),
            ) {
                items(emojis) { emoji ->
                    Box(
                        Modifier
                            .padding(4.dp)
                            .clickable { onPick(emoji) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, fontSize = 24.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = PismoColors.TextMuted) }
        },
    )
}
