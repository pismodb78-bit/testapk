package com.pismo.messenger.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pismo.messenger.core.isGifName
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Рисование поверх снимка — как в редакторе Telegram.
 *
 * Три инструмента и ничего лишнего: кисть, надпись прямо на фотографии и
 * стикер — другой снимок с телефона (гифки не берём: в наклейке они всё
 * равно замрут первым кадром, а весят как ролик).
 *
 * Слои хранятся в ДОЛЯХ от размера снимка, а не в точках экрана. Поэтому
 * нарисованное ложится одинаково и на экране, и в готовом файле, какого бы
 * размера ни была фотография: при сохранении доли просто умножаются на её
 * настоящие пиксели.
 */
@Composable
fun PhotoEditor(
    bytes: ByteArray,
    onCancel: () -> Unit,
    onDone: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = remember(bytes) {
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }
    val image = remember(source) { source?.asImageBitmap() }

    val strokes = remember { mutableStateListOf<PenStroke>() }
    val texts = remember { mutableStateListOf<TextMark>() }
    val stickers = remember { mutableStateListOf<StickerMark>() }
    /** Порядок добавления — чтобы «отменить» убирало последнее по времени. */
    val order = remember { mutableStateListOf<String>() }

    var tool by remember { mutableStateOf(Tool.Brush) }
    var color by remember { mutableStateOf(Color(0xFFFF3B30)) }
    var width by remember { mutableFloatStateOf(10f) }
    var askText by remember { mutableStateOf(false) }
    var askColor by remember { mutableStateOf(false) }
    /** Свои цвета из палитры — встают рядом с готовыми. */
    val custom = remember { mutableStateListOf<Color>() }
    var busy by remember { mutableStateOf(false) }

    var frame by remember { mutableStateOf(Rect.Zero) }
    val measurer = rememberTextMeasurer()

    val stickerPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryFileName(context, uri)
                    // Гифку в наклейку не берём намеренно.
                    if (isGifName(name)) return@runCatching null
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
            } ?: return@launch
            stickers += StickerMark(bmp, Offset(0.5f, 0.45f), 0.35f)
            order += "sticker"
        }
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (image == null) {
                Text(
                    "Не удалось открыть снимок",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(tool) {
                            detectDragGestures(
                                onDragStart = { p ->
                                    if (tool != Tool.Brush) return@detectDragGestures
                                    val n = frame.normalize(p) ?: return@detectDragGestures
                                    strokes += PenStroke(mutableStateListOf(n), color, width)
                                    order += "stroke"
                                },
                                onDrag = { change, _ ->
                                    val n = frame.normalize(change.position) ?: return@detectDragGestures
                                    when (tool) {
                                        Tool.Brush -> strokes.lastOrNull()?.points?.add(n)
                                        Tool.Text -> texts.lastOrNull()?.let { it.pos = n }
                                        Tool.Sticker -> stickers.lastOrNull()?.let { it.pos = n }
                                    }
                                },
                            )
                        },
                ) {
                    // Вписываем снимок и запоминаем его прямоугольник: всё
                    // нарисованное живёт в долях от него.
                    val k = minOf(size.width / image.width, size.height / image.height)
                    val w = image.width * k
                    val h = image.height * k
                    val left = (size.width - w) / 2f
                    val top = (size.height - h) / 2f
                    frame = Rect(left, top, left + w, top + h)

                    drawImage(
                        image = image,
                        dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
                    )

                    strokes.forEach { s ->
                        if (s.points.size < 2) return@forEach
                        val path = Path()
                        val first = frame.denormalize(s.points.first())
                        path.moveTo(first.x, first.y)
                        s.points.drop(1).forEach { p ->
                            val q = frame.denormalize(p)
                            path.lineTo(q.x, q.y)
                        }
                        drawPath(
                            path = path,
                            color = s.color,
                            style = Stroke(width = s.width * k.coerceAtLeast(0.2f), cap = StrokeCap.Round),
                        )
                    }

                    stickers.forEach { st ->
                        val img = st.bitmap.asImageBitmap()
                        val sw = frame.width * st.scale
                        val sh = sw * img.height / img.width.toFloat()
                        val c = frame.denormalize(st.pos)
                        drawImage(
                            image = img,
                            dstOffset = androidx.compose.ui.unit.IntOffset(
                                (c.x - sw / 2f).toInt(), (c.y - sh / 2f).toInt()
                            ),
                            dstSize = androidx.compose.ui.unit.IntSize(sw.toInt(), sh.toInt()),
                        )
                    }

                    texts.forEach { t ->
                        val c = frame.denormalize(t.pos)
                        val layout = measurer.measure(
                            t.text,
                            TextStyle(
                                color = t.color,
                                fontSize = (t.size * k).sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        drawText(
                            layout,
                            topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f),
                        )
                    }
                }
            }

            // Верх: отмена, отменить последнее, готово.
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorButton(Icons.Default.Close, "Отмена") { onCancel() }
                Box(Modifier.weight(1f))
                EditorButton(Icons.Default.Undo, "Отменить последнее") {
                    when (order.removeLastOrNull()) {
                        "stroke" -> strokes.removeLastOrNull()
                        "text" -> texts.removeLastOrNull()
                        "sticker" -> stickers.removeLastOrNull()
                    }
                }
                EditorButton(Icons.Default.Check, "Готово", enabled = !busy, onClick = {
                    val src = source ?: return@EditorButton
                    busy = true
                    scope.launch {
                        val out = withContext(Dispatchers.IO) {
                            render(src, strokes.toList(), texts.toList(), stickers.toList())
                        }
                        busy = false
                        if (out != null) onDone(out)
                    }
                })
            }

            // Низ: инструменты, цвета и толщина.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xAA000000))
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Палитра — любой оттенок, а не только семь готовых.
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(
                                androidx.compose.ui.graphics.Brush.sweepGradient(
                                    listOf(
                                        Color.Red, Color.Yellow, Color.Green,
                                        Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
                                    )
                                )
                            )
                            .border(1.dp, Color(0x99FFFFFF), CircleShape)
                            .clickable { askColor = true },
                    )
                    (listOf(
                        Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759),
                        Color(0xFF007AFF), Color(0xFFAF52DE), Color.White, Color.Black,
                    ) + custom).forEach { c ->
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    if (c == color) 3.dp else 1.dp,
                                    if (c == color) Color.White else Color(0x66FFFFFF),
                                    CircleShape,
                                )
                                .clickable { color = c },
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolChip("Кисть", Icons.Default.Brush, tool == Tool.Brush) { tool = Tool.Brush }
                    ToolChip("Текст", Icons.Default.TextFields, tool == Tool.Text) {
                        tool = Tool.Text
                        askText = true
                    }
                    ToolChip("Стикер", Icons.Default.Image, tool == Tool.Sticker) {
                        tool = Tool.Sticker
                        stickerPicker.launch(arrayOf("image/*"))
                    }
                    Box(Modifier.weight(1f))
                    listOf(6f, 10f, 18f, 28f).forEach { w ->
                        Box(
                            Modifier
                                .padding(start = 8.dp)
                                .size((w / 2 + 10).dp)
                                .clip(CircleShape)
                                .background(if (width == w) Color.White else Color(0x66FFFFFF))
                                .clickable { width = w },
                        )
                    }
                }
            }
        }
    }

    if (askColor) {
        ColorPickerDialog(
            initial = color,
            onDismiss = { askColor = false },
            onPick = { picked ->
                askColor = false
                color = picked
                if (custom.none { it == picked }) {
                    custom.add(0, picked)
                    while (custom.size > 4) custom.removeAt(custom.lastIndex)
                }
            },
        )
    }

    if (askText) {
        var draft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askText = false },
            containerColor = PismoColors.BgElevated,
            title = { Text("Надпись", color = PismoColors.TextPrimary) },
            text = {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Текст на фото") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askText = false
                    if (draft.isNotBlank()) {
                        texts += TextMark(draft, Offset(0.5f, 0.5f), color, 26f)
                        order += "text"
                    }
                }) { Text("Добавить", color = PismoColors.Blurple) }
            },
            dismissButton = {
                TextButton(onClick = { askText = false }) {
                    Text("Отмена", color = PismoColors.TextSecondary)
                }
            },
        )
    }
}

private enum class Tool { Brush, Text, Sticker }

private class PenStroke(
    val points: androidx.compose.runtime.snapshots.SnapshotStateList<Offset>,
    val color: Color,
    val width: Float,
)

private class TextMark(val text: String, pos: Offset, val color: Color, val size: Float) {
    var pos by mutableStateOf(pos)
}

private class StickerMark(val bitmap: Bitmap, pos: Offset, val scale: Float) {
    var pos by mutableStateOf(pos)
}

/** Экранная точка → доля от снимка (0..1). Вне снимка — null. */
private fun Rect.normalize(p: Offset): Offset? {
    if (width <= 0f || height <= 0f) return null
    val x = (p.x - left) / width
    val y = (p.y - top) / height
    if (x < 0f || x > 1f || y < 0f || y > 1f) return null
    return Offset(x, y)
}

private fun Rect.denormalize(p: Offset): Offset = Offset(left + p.x * width, top + p.y * height)

@Composable
private fun EditorButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    hint: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .padding(start = 8.dp)
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, hint, tint = Color.White)
    }
}

@Composable
private fun ToolChip(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) PismoColors.Blurple else Color(0x33FFFFFF))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, title, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(
            "  $title",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Собрать готовый снимок: доли умножаем на настоящие пиксели, поэтому
 * нарисованное ложится ровно так, как было видно на экране.
 */
private fun render(
    src: Bitmap,
    strokes: List<PenStroke>,
    texts: List<TextMark>,
    stickers: List<StickerMark>,
): ByteArray? = runCatching {
    val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return null
    val canvas = android.graphics.Canvas(out)
    val w = out.width.toFloat()
    val h = out.height.toFloat()
    // Кисть задавалась в точках экрана при ширине снимка на экране; переводим
    // через отношение ширин, иначе на большом снимке линия вышла бы волоском.
    val penK = w / 1080f

    strokes.forEach { s ->
        if (s.points.size < 2) return@forEach
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = s.color.toArgb()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = s.width * 3f * penK
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val path = android.graphics.Path()
        path.moveTo(s.points[0].x * w, s.points[0].y * h)
        s.points.drop(1).forEach { p -> path.lineTo(p.x * w, p.y * h) }
        canvas.drawPath(path, paint)
    }

    stickers.forEach { st ->
        val sw = w * st.scale
        val sh = sw * st.bitmap.height / st.bitmap.width.toFloat()
        val cx = st.pos.x * w
        val cy = st.pos.y * h
        val dst = android.graphics.RectF(cx - sw / 2f, cy - sh / 2f, cx + sw / 2f, cy + sh / 2f)
        canvas.drawBitmap(st.bitmap, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    }

    texts.forEach { t ->
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = t.color.toArgb()
            textSize = t.size * 3f * penK
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText(t.text, t.pos.x * w, t.pos.y * h, paint)
    }

    val bos = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.JPEG, 92, bos)
    bos.toByteArray()
}.getOrNull()

/**
 * Палитра: оттенок, насыщенность и яркость тремя полосами.
 *
 * Системного выбора цвета на Android нет, а семи готовых кружков для
 * рисования мало — здесь получается любой оттенок, и выбранный остаётся
 * рядом с готовыми до конца правки.
 */
@Composable
private fun ColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onPick: (Color) -> Unit,
) {
    val start = remember(initial) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initial.toArgb(), hsv)
        hsv
    }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1].coerceAtLeast(0.05f)) }
    var value by remember { mutableFloatStateOf(start[2].coerceAtLeast(0.05f)) }

    val current = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgElevated,
        title = { Text("Цвет", color = PismoColors.TextPrimary) },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(current)
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(10.dp)),
                )

                GradientBar(
                    colors = listOf(
                        Color.Red, Color.Yellow, Color.Green,
                        Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
                    ),
                    fraction = hue / 360f,
                ) { hue = (it * 360f).coerceIn(0f, 360f) }

                GradientBar(
                    colors = listOf(Color.White, pure),
                    fraction = sat,
                ) { sat = it }

                GradientBar(
                    colors = listOf(Color.Black, pure),
                    fraction = value,
                ) { value = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current) }) {
                Text("Выбрать", color = PismoColors.Blurple)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = PismoColors.TextSecondary)
            }
        },
    )
}

/** Полоса-градиент с бегунком: нажатие и перетаскивание выбирают долю. */
@Composable
private fun GradientBar(
    colors: List<Color>,
    fraction: Float,
    onChange: (Float) -> Unit,
) {
    var width by remember { mutableFloatStateOf(1f) }
    Box(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(colors))
            .pointerInput(colors) {
                width = size.width.toFloat()
                detectDragGestures(
                    onDragStart = { p -> onChange((p.x / size.width).coerceIn(0f, 1f)) },
                    onDrag = { change, _ ->
                        onChange((change.position.x / size.width).coerceIn(0f, 1f))
                    },
                )
            }
            .pointerInput(colors) {
                androidx.compose.foundation.gestures.awaitEachGesture {
                    val down = androidx.compose.foundation.gestures.awaitFirstDown()
                    onChange((down.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(28.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Color(0x66000000), CircleShape),
            )
        }
    }
}
