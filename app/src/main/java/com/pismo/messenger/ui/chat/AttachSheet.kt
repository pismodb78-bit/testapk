package com.pismo.messenger.ui.chat

import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pismo.messenger.core.MediaKinds
import com.pismo.messenger.core.formatDuration
import com.pismo.messenger.core.isGifName
import com.pismo.messenger.core.isImageName
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Выбранное к отправке — снимок, ролик или документ.
 *
 * Держим ССЫЛКУ, а не содержимое: пачка из десяти роликов в памяти не
 * помещается, а до отправки байты всё равно не нужны. Читает их отправка,
 * по одному (см. AttachSend).
 */
data class Picked(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val isImage: Boolean,
    val sizeBytes: Long,
)

/**
 * Лист вложений — как в Telegram.
 *
 * Раньше скрепка открывала системный выбор файлов: список имён, в котором не
 * видно ни что выбираешь, ни в каком порядке. Теперь это своя сетка галереи:
 *
 *   • кружок справа сверху — ВЫБОР, и в нём стоит НОМЕР: плитка уйдёт
 *     собеседнику в том порядке, в каком её выбирали;
 *   • нажатие в середину плитки — ПРОСМОТР, чтобы не отправлять вслепую;
 *   • подпись пишется здесь же, под сеткой, и уходит вместе с пачкой;
 *   • «Внутреннее хранилище» — документы, они встают в ту же пачку.
 *
 * Выход (кнопка «назад», жест или опускание листа) при непустом выборе
 * спрашивает, сбрасывать ли его — ровно как Telegram: случайно смахнуть лист
 * и потерять отмеченные два десятка фотографий обиднее всего.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(
    onDismiss: () -> Unit,
    onSend: (List<Picked>, String) -> Unit,
    onCamera: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var needPermission by remember { mutableStateOf(!GalleryStore.granted(context)) }
    // Порядок выбора — это и есть порядок отправки, поэтому храним СПИСОК,
    // а не множество.
    var chosen by remember { mutableStateOf<List<Picked>>(emptyList()) }
    var caption by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Picked?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    // Вкладки, как в Telegram: «Галерея» и «Файлы». Выбор у них ОБЩИЙ —
    // и сам список, и порядок номеров переживают переход туда и обратно:
    // отмеченное фото не должно теряться из-за того, что следом понадобился
    // документ.
    var tab by remember { mutableStateOf(0) }

    suspend fun reloadGallery() {
        items = GalleryStore.load(context)
    }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        needPermission = !GalleryStore.granted(context)
        scope.launch { reloadGallery() }
    }

    LaunchedEffect(Unit) {
        if (needPermission) askPermission.launch(GalleryStore.permissions())
        else reloadGallery()
    }

    // Документы берём системным выбором: до файлов вне галереи приложению
    // всё равно не дотянуться, а выбранное встаёт в общую пачку.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val add = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val name = queryFileName(context, uri)
                    Picked(
                        uri = uri,
                        name = name,
                        isVideo = MediaKinds.isVideo(name),
                        isImage = isImageName(name) || isGifName(name),
                        sizeBytes = fileSizeOf(context, uri),
                    )
                }
            }
            chosen = chosen + add.filter { a -> chosen.none { it.uri == a.uri } }
        }
    }

    fun toggle(p: Picked) {
        chosen = if (chosen.any { it.uri == p.uri }) chosen.filterNot { it.uri == p.uri }
        else if (chosen.size >= ATTACH_MAX_AT_ONCE) chosen
        else chosen + p
    }

    fun requestClose() {
        if (chosen.isEmpty()) onDismiss() else confirmClose = true
    }

    ModalBottomSheet(
        onDismissRequest = { requestClose() },
        sheetState = sheetState,
        containerColor = PismoColors.BgElevated,
        contentColor = PismoColors.TextPrimary,
    ) {
        Column(Modifier.fillMaxHeight(0.94f)) {
            // Шапка — она же счётчик: «Выбрано 5 медиафайлов».
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (chosen.isEmpty()) "Галерея"
                    else "Выбрано ${chosen.size} ${plural(chosen.size)}",
                    color = PismoColors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (chosen.isNotEmpty()) {
                    TextButton(onClick = { chosen = emptyList() }) {
                        Text("Сбросить", color = PismoColors.TextSecondary, fontSize = 13.sp)
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AttachTab("Фото и видео", tab == 0) { tab = 0 }
                AttachTab("Файлы", tab == 1) { tab = 1 }
            }

            if (tab == 1) {
                FilesTab(
                    chosen = chosen,
                    onBrowse = { filePicker.launch(arrayOf("*/*")) },
                    onToggle = { toggle(it) },
                    modifier = Modifier.weight(1f),
                )
            } else
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
            ) {
                // Камера первой плиткой — как в Telegram: снять и отправить,
                // не выходя из листа.
                item {
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .background(Color(0xFF101114))
                            .clickable { onDismiss(); onCamera() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.PhotoCamera, "Снять фото",
                            tint = Color(0xFFCDD0D6), modifier = Modifier.size(30.dp),
                        )
                    }
                }

                if (needPermission) {
                    item(span = { GridItemSpan(3) }) {
                        Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            Text(
                                "Нет доступа к галерее — плитку показать нечем.",
                                color = PismoColors.TextSecondary, fontSize = 14.sp,
                            )
                            TextButton(onClick = { askPermission.launch(GalleryStore.permissions()) }) {
                                Text("Разрешить доступ", color = PismoColors.Blurple)
                            }
                        }
                    }
                }

                items(items, key = { it.uri.toString() }) { g ->
                    val picked = Picked(g.uri, g.name, g.isVideo, !g.isVideo, g.sizeBytes)
                    val order = chosen.indexOfFirst { it.uri == g.uri }
                    GalleryCell(
                        item = g,
                        order = if (order < 0) 0 else order + 1,
                        onOpen = { preview = picked },
                        onToggle = { toggle(picked) },
                    )
                }

            }

            // Подпись и отправка — как в Telegram, прямо в листе: подпись
            // уходит вместе с пачкой, а не отдельным сообщением следом.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(PismoColors.BgDarkest)
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = caption,
                    onValueChange = { caption = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Добавить подпись…", color = PismoColors.TextMuted, fontSize = 14.sp) },
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = PismoColors.TextPrimary,
                        unfocusedTextColor = PismoColors.TextPrimary,
                        cursorColor = PismoColors.Blurple,
                    ),
                )
                Box(contentAlignment = Alignment.TopEnd) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(
                                if (chosen.isEmpty()) PismoColors.BgElevated else PismoColors.Blurple
                            )
                            .clickable(enabled = chosen.isNotEmpty()) {
                                val out = chosen
                                val cap = caption
                                chosen = emptyList()
                                caption = ""
                                onDismiss()
                                onSend(out, cap)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Send, "Отправить",
                            tint = if (chosen.isEmpty()) PismoColors.TextMuted else Color.White,
                        )
                    }
                    if (chosen.isNotEmpty()) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E8FE1)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${chosen.size}", color = Color.White, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }

    // Просмотр по нажатию в середину плитки.
    preview?.let { p ->
        AttachPreview(p, onClose = { preview = null })
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            containerColor = PismoColors.BgElevated,
            title = { Text("Сбросить выбор", color = PismoColors.TextPrimary) },
            text = {
                Text(
                    "Закрыть меню вложений? Отмеченные ${chosen.size} ${plural(chosen.size)} " +
                            "не отправятся.",
                    color = PismoColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    chosen = emptyList()
                    caption = ""
                    onDismiss()
                }) { Text("Сбросить", color = Color(0xFFE06C6C)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmClose = false
                    // Лист уже уехал вниз от жеста — поднимаем обратно.
                    scope.launch { runCatching { sheetState.show() } }
                }) { Text("Отмена", color = PismoColors.Blurple) }
            },
        )
    }
}

/** Потолок на одну пачку: больше в памяти отправки держать незачем. */
private const val ATTACH_MAX_AT_ONCE = 50

private fun plural(n: Int): String {
    val n10 = n % 10
    val n100 = n % 100
    return when {
        n10 == 1 && n100 != 11 -> "медиафайл"
        n10 in 2..4 && n100 !in 12..14 -> "медиафайла"
        else -> "медиафайлов"
    }
}

/** Плитка галереи: превью, кружок выбора с номером, длительность у роликов. */
@Composable
private fun GalleryCell(
    item: GalleryItem,
    order: Int,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    // У ролика превью — кадр из него; Coil сам его не достанет, а системный
    // загрузчик миниатюр умеет это с 29-го Android.
    var frame by remember(item.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(item.uri) {
        if (!item.isVideo || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@LaunchedEffect
        frame = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(item.uri, Size(320, 320), null)
            }.getOrNull()
        }
    }

    Box(
        Modifier
            .aspectRatio(1f)
            .background(Color(0xFF17181C))
            .clickable { onOpen() },
    ) {
        if (item.isVideo) {
            frame?.let {
                androidx.compose.foundation.Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            AsyncImage(
                model = item.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (item.isVideo) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.PlayArrow, null, tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    formatDuration(item.durationMs / 1000),
                    color = Color.White, fontSize = 11.sp,
                )
            }
        }

        // Кружок — ВЫБОР. Он отдельная мишень: нажатие по плитке открывает
        // просмотр, и смешивать эти два действия нельзя.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (order > 0) Color(0xFF1E8FE1) else Color(0x33000000))
                .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                .clickable { onToggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (order > 0) {
                Text(
                    "$order", color = Color.White, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Просмотр выбранного до отправки: фото — с зумом, ролик — обычным плеером. */
@Composable
private fun AttachPreview(p: Picked, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            if (p.isVideo) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.widget.VideoView(ctx).apply {
                            setVideoURI(p.uri)
                            setOnPreparedListener { it.isLooping = true; start() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                )
            } else {
                AsyncImage(
                    model = p.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                p.name,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
            )
        }
    }
}


/** Переключатель вкладок листа — «Фото и видео» / «Файлы». */
@Composable
private fun AttachTab(title: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) PismoColors.Blurple else Color(0x22FFFFFF))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            title,
            color = if (active) Color.White else PismoColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Вкладка «Файлы».
 *
 * До самих файлов на диске приложению не дотянуться — за этим ходим в
 * системный выбор. Зато выбранное остаётся ЗДЕСЬ, в общей пачке и со своим
 * номером: вернувшись к фотографиям, человек видит прежние номера, а
 * отправится всё одним движением.
 */
@Composable
private fun FilesTab(
    chosen: List<Picked>,
    onBrowse: () -> Unit,
    onToggle: (Picked) -> Unit,
    modifier: Modifier = Modifier,
) {
    val docs = chosen.filter { !it.isImage && !it.isVideo }
    androidx.compose.foundation.lazy.LazyColumn(modifier.fillMaxWidth()) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onBrowse() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Folder, null, tint = Color(0xFF53B16A))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Внутреннее хранилище", color = PismoColors.TextPrimary, fontSize = 15.sp)
                    Text(
                        "Документы, музыка и всё остальное",
                        color = PismoColors.TextMuted, fontSize = 12.sp,
                    )
                }
            }
        }
        if (docs.isEmpty()) {
            item {
                Text(
                    "Выбранные файлы появятся здесь — и уйдут вместе с фотографиями, " +
                            "одной пачкой.",
                    color = PismoColors.TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(docs.size, key = { "doc-" + docs[it].uri }) { i ->
            val d = docs[i]
            val order = chosen.indexOfFirst { it.uri == d.uri } + 1
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(d) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E8FE1)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$order", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, color = PismoColors.TextPrimary, fontSize = 14.sp, maxLines = 1)
                    Text(
                        formatBytesShort(d.sizeBytes),
                        color = PismoColors.TextMuted, fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
