package com.pismo.messenger.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.net.GifItem
import com.pismo.messenger.net.GiphyClient
import com.pismo.messenger.ui.components.AnimatedImage
import com.pismo.messenger.ui.login.PismoField
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.delay

/**
 * Поиск гифок — порт GifPickerForm.cs (кнопка GIF, как в Discord).
 *
 * Пустая строка — популярное сейчас, иначе поиск по запросу. Задержка перед
 * запросом обязательна: Giphy на бета-ключе даёт сотню запросов в час, и
 * набор слова по букве съел бы её за один вечер.
 *
 * ОТЛИЧИЕ ОТ ПК, намеренное: выбранная гифка не улетает сразу, а становится
 * прикреплённым вложением — к ней можно дописать подпись, и уйдёт всё одним
 * сообщением. Ровно так же здесь ведёт себя обычное вложение.
 */
@Composable
fun GifPickerDialog(
    onPicked: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<GifItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }

    /** Скачанные превью: url → байты. Заново одно и то же не тянем. */
    val previews = remember { mutableStateMapOf<String, ByteArray>() }

    /** Какую гифку сейчас качаем целиком — чтобы не жать дважды. */
    var picking by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        loading = true
        note = ""
        val q = query.trim()
        // Пауза перед запросом — не на каждую букву.
        if (q.isNotEmpty()) delay(400)
        val found = if (q.isEmpty()) GiphyClient.trending() else GiphyClient.search(q)
        items = found
        loading = false
        if (found.isEmpty()) {
            note = if (q.isEmpty()) "Giphy недоступен — проверьте интернет."
            else "Ничего не нашлось."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgElevated,
        title = {
            Text("GIF", color = PismoColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                PismoField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Поиск по Giphy",
                )
                Spacer(Modifier.height(10.dp))

                if (loading && items.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = PismoColors.Blurple)
                    }
                } else if (note.isNotEmpty()) {
                    Text(note, color = PismoColors.TextMuted, fontSize = 12.sp)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.heightIn(max = 360.dp),
                    ) {
                        items(items, key = { it.previewUrl }) { gif ->
                            GifCell(
                                gif = gif,
                                bytes = previews[gif.previewUrl],
                                busy = picking == gif.fullUrl,
                                onLoaded = { previews[gif.previewUrl] = it },
                                onClick = {
                                    if (picking != null) return@GifCell
                                    picking = gif.fullUrl
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть", color = PismoColors.TextSecondary)
            }
        },
    )

    // Полную версию тянем отдельно от нажатия: сеть в обработчике клика
    // держала бы диалог замороженным без единого признака жизни.
    LaunchedEffect(picking) {
        val url = picking ?: return@LaunchedEffect
        val bytes = GiphyClient.download(url)
        picking = null
        if (bytes == null || bytes.isEmpty()) {
            note = "Не удалось загрузить гифку."
        } else {
            onPicked(bytes)
            onDismiss()
        }
    }
}

@Composable
private fun GifCell(
    gif: GifItem,
    bytes: ByteArray?,
    busy: Boolean,
    onLoaded: (ByteArray) -> Unit,
    onClick: () -> Unit,
) {
    LaunchedEffect(gif.previewUrl) {
        if (bytes != null) return@LaunchedEffect
        GiphyClient.download(gif.previewUrl)?.let(onLoaded)
    }

    Box(
        Modifier
            .padding(3.dp)
            .fillMaxWidth()
            .aspectRatio(1.2f)
            .clip(RoundedCornerShape(8.dp))
            .background(PismoColors.BgDarkest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bytes != null -> AnimatedImage(
                bytes = bytes,
                contentDescription = "GIF",
                modifier = Modifier.fillMaxSize(),
            )
            else -> CircularProgressIndicator(
                color = PismoColors.TextMuted,
                modifier = Modifier.height(20.dp),
            )
        }
        if (busy) {
            Box(
                Modifier.fillMaxSize().background(PismoColors.BgDarkest.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = PismoColors.Blurple)
            }
        }
    }
}
