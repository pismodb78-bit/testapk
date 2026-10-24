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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Redo
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
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
 * Инструменты: перо, маркер, неон, ластик, надпись прямо на фотографии и
 * стикер — другой снимок с телефона (гифки не берём: в наклейке они всё
 * равно замрут первым кадром, а весят как ролик). Любое действие можно
 * отменить и вернуть обратно.
 *
 * Слои хранятся в ДОЛЯХ от размера снимка — и положение, и толщина линии, и
 * кегль надписи. Поэтому нарисованное ложится одинаково и на экране, и в
 * готовом файле, какого бы размера ни была фотография. Раньше толщина
 * задавалась в точках экрана, а при сборке домножалась на три — тонкая линия
 * в готовом снимке превращалась в жирную полосу.
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

    // ОДИН список на всё нарисованное, в порядке появления: только так
    // «отменить» и «вернуть» работают по-человечески — шаг за шагом, что бы
    // это ни было: линия, надпись или наклейка.
    val marks = remember { mutableStateListOf<Mark>() }
    val undone = remember { mutableStateListOf<Mark>() }

    var tool by remember { mutableStateOf(Tool.Pen) }
    var color by remember { mutableStateOf(Color(0xFFFF3B30)) }
    /** Толщина — ДОЛЯ ширины снимка, а не точки экрана. */
    var width by remember { mutableFloatStateOf(0.012f) }
    var askText by remember { mutableStateOf(false) }
    var askColor by remember { mutableStateOf(false) }
    /** Свои цвета из палитры — встают рядом с готовыми. */
    val custom = remember { mutableStateListOf<Color>() }
    var busy by remember { mutableStateOf(false) }

    var frame by remember { mutableStateOf(Rect.Zero) }
    val measurer = rememberTextMeasurer()

    fun add(m: Mark) {
        marks += m
        // Новое действие обрывает ветку «вернуть» — как в любом редакторе.
        undone.clear()
    }

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
            add(StickerMark(bmp, Offset(0.5f, 0.45f), 0.35f))
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
                        .pointerInput(tool, color, width) {
                            // Наклейку двигают, крутят и растягивают ДВУМЯ
                            // пальцами — обычным щипком, как в телеграме.
                            if (tool == Tool.Sticker) {
                                detectTransformGestures { _, pan, zoom, rot ->
                                    val st = marks.lastOrNull { it is StickerMark } as? StickerMark
                                        ?: return@detectTransformGestures
                                    if (frame.width > 0f && frame.height > 0f) {
                                        st.pos = Offset(
                                            (st.pos.x + pan.x / frame.width).coerceIn(0f, 1f),
                                            (st.pos.y + pan.y / frame.height).coerceIn(0f, 1f),
                                        )
                                    }
                                    st.scale = (st.scale * zoom).coerceIn(0.05f, 1.6f)
                                    st.angle += rot
                                }
                                return@pointerInput
                            }
                            detectDragGestures(
                                onDragStart = { p ->
                                    if (!tool.draws) return@detectDragGestures
                                    val n = frame.normalize(p) ?: return@detectDragGestures
                                    add(PenStroke(mutableStateListOf(n), color, width, tool))
                                },
                                onDrag = { change, _ ->
                                    val n = frame.normalize(change.position)
                                        ?: return@detectDragGestures
                                    // Надпись и наклейку таскаем ПОСЛЕДНЮЮ
                                    // СВОЮ, а не последнюю вообще: иначе,
                                    // нарисовав что-то после надписи, сдвинуть
                                    // её было уже нельзя.
                                    when (tool) {
                                        Tool.Text ->
                                            (marks.lastOrNull { it is TextMark } as? TextMark)
                                                ?.pos = n

                                        Tool.Sticker ->
                                            (marks.lastOrNull { it is StickerMark } as? StickerMark)
                                                ?.pos = n

                                        else -> (marks.lastOrNull() as? PenStroke)?.points?.add(n)
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

                    // Линии рисуем в ОТДЕЛЬНОМ слое: ластик стирает
                    // нарисованное, а не выедает дыру в самой фотографии.
                    val pens = marks.filterIsInstance<PenStroke>()
                    if (pens.isNotEmpty()) {
                        drawIntoCanvas { canvas ->
                            canvas.saveLayer(frame, androidx.compose.ui.graphics.Paint())
                            pens.forEach { drawStroke(it, frame) }
                            canvas.restore()
                        }
                    }

                    marks.forEach { m ->
                        when (m) {
                            is StickerMark -> {
                                val img = m.bitmap.asImageBitmap()
                                val sw = frame.width * m.scale
                                val sh = sw * img.height / img.width.toFloat()
                                val c = frame.denormalize(m.pos)
                                rotate(m.angle, pivot = c) {
                                    drawImage(
                                        image = img,
                                        dstOffset = androidx.compose.ui.unit.IntOffset(
                                            (c.x - sw / 2f).toInt(), (c.y - sh / 2f).toInt()
                                        ),
                                        dstSize = androidx.compose.ui.unit.IntSize(
                                            sw.toInt(), sh.toInt()
                                        ),
                                    )
                                }
                            }

                            is TextMark -> {
                                val c = frame.denormalize(m.pos)
                                val layout = measurer.measure(
                                    m.text,
                                    TextStyle(
                                        color = m.color,
                                        fontSize = (m.size * frame.width).toSp(),
                                        fontWeight = FontWeight.Bold,
                                    ),
                                )
                                drawText(
                                    layout,
                                    topLeft = Offset(
                                        c.x - layout.size.width / 2f,
                                        c.y - layout.size.height / 2f,
                                    ),
                                )
                            }

                            else -> Unit
                        }
                    }
                }
            }

            // Верх: выйти, отменить, вернуть, готово.
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
                EditorButton(Icons.Default.Undo, "Отменить", enabled = marks.isNotEmpty()) {
                    marks.removeLastOrNull()?.let { undone.add(it) }
                }
                EditorButton(Icons.Default.Redo, "Вернуть", enabled = undone.isNotEmpty()) {
                    undone.removeLastOrNull()?.let { marks.add(it) }
                }
                EditorButton(Icons.Default.Check, "Готово", enabled = !busy, onClick = {
                    val src = source ?: return@EditorButton
                    busy = true
                    scope.launch {
                        val out = withContext(Dispatchers.IO) { render(src, marks.toList()) }
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

                // Инструментов больше, чем влезает в узкий экран, — ряд
                // прокручивается вбок.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolChip("Перо", Icons.Default.Create, tool == Tool.Pen) { tool = Tool.Pen }
                    ToolChip("Маркер", Icons.Default.Brush, tool == Tool.Marker) {
                        tool = Tool.Marker
                    }
                    ToolChip("Неон", Icons.Default.AutoFixNormal, tool == Tool.Neon) {
                        tool = Tool.Neon
                    }
                    ToolChip("Ластик", Icons.Default.Close, tool == Tool.Eraser) {
                        tool = Tool.Eraser
                    }
                    ToolChip("Текст", Icons.Default.TextFields, tool == Tool.Text) {
                        tool = Tool.Text
                        askText = true
                    }
                    ToolChip("Стикер", Icons.Default.Image, tool == Tool.Sticker) {
                        tool = Tool.Sticker
                        stickerPicker.launch(arrayOf("image/*"))
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(0.006f, 0.012f, 0.022f, 0.036f).forEach { w ->
                        Box(
                            Modifier
                                .padding(start = 10.dp)
                                .size((w * 700 + 10).dp)
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
                    // Кегль — тоже доля ширины снимка: иначе надпись на
                    // большом снимке вышла бы мелкой, а на маленьком — во
                    // весь кадр.
                    if (draft.isNotBlank()) add(TextMark(draft, Offset(0.5f, 0.5f), color, 0.06f))
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

private enum class Tool(val draws: Boolean) {
    Pen(true), Marker(true), Neon(true), Eraser(true), Text(false), Sticker(false)
}

/** Что нарисовано поверх снимка — в порядке появления. */
private sealed interface Mark

private class PenStroke(
    val points: androidx.compose.runtime.snapshots.SnapshotStateList<Offset>,
    val color: Color,
    /** Доля ширины снимка. */
    val width: Float,
    val kind: Tool,
) : Mark

private class TextMark(val text: String, pos: Offset, val color: Color, val size: Float) : Mark {
    var pos by mutableStateOf(pos)
}

private class StickerMark(val bitmap: Bitmap, pos: Offset, scale: Float) : Mark {
    var pos by mutableStateOf(pos)
    /** Доля ширины снимка. Щипком — больше или меньше. */
    var scale by mutableFloatStateOf(scale)
    /** Поворот в градусах — тем же щипком, двумя пальцами. */
    var angle by mutableFloatStateOf(0f)
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

/**
 * Линия на экране. Толщина — доля ширины снимка, помноженная на его ширину
 * НА ЭКРАНЕ; при сборке готового файла та же доля множится на настоящие
 * пиксели. Поэтому что видно, то и уходит.
 */
private fun DrawScope.drawStroke(s: PenStroke, frame: Rect) {
    val w = s.width * frame.width
    if (s.points.size == 1) {
        val c = frame.denormalize(s.points[0])
        val r = (if (s.kind == Tool.Marker) w * 0.9f else w) / 2f
        if (s.kind == Tool.Eraser) drawCircle(Color.Transparent, r * 1.6f, c, blendMode = BlendMode.Clear)
        else drawCircle(s.color, r, c)
        return
    }
    if (s.points.size < 2) return
    val path = Path()
    val first = frame.denormalize(s.points.first())
    path.moveTo(first.x, first.y)
    s.points.drop(1).forEach { p ->
        val q = frame.denormalize(p)
        path.lineTo(q.x, q.y)
    }
    when (s.kind) {
        Tool.Marker -> drawPath(
            path, s.color.copy(alpha = 0.4f),
            style = Stroke(w * 1.8f, cap = StrokeCap.Square, join = StrokeJoin.Round),
        )

        Tool.Neon -> {
            drawPath(
                path, s.color.copy(alpha = 0.35f),
                style = Stroke(w * 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawPath(
                path, Color.White,
                style = Stroke(w * 0.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        Tool.Eraser -> drawPath(
            path, Color.Transparent,
            style = Stroke(w * 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.Clear,
        )

        else -> drawPath(
            path, s.color,
            style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

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
        Icon(icon, hint, tint = if (enabled) Color.White else Color(0x66FFFFFF))
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
 *
 * Линии идут в отдельный прозрачный слой — ластик стирает нарисованное, а не
 * протирает дыру в фотографии.
 */
private fun render(src: Bitmap, marks: List<Mark>): ByteArray? = runCatching {
    val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return null
    val canvas = android.graphics.Canvas(out)
    val w = out.width.toFloat()
    val h = out.height.toFloat()

    val pens = marks.filterIsInstance<PenStroke>()
    if (pens.isNotEmpty()) {
        val layer = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
        val lc = android.graphics.Canvas(layer)
        pens.forEach { s ->
            val sw = s.width * w
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = s.color.toArgb()
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
                strokeWidth = sw
                when (s.kind) {
                    Tool.Marker -> {
                        alpha = 102
                        strokeWidth = sw * 1.8f
                        strokeCap = android.graphics.Paint.Cap.SQUARE
                    }

                    Tool.Eraser -> {
                        strokeWidth = sw * 1.6f
                        xfermode = android.graphics.PorterDuffXfermode(
                            android.graphics.PorterDuff.Mode.CLEAR
                        )
                    }

                    else -> Unit
                }
            }
            if (s.points.size == 1) {
                val c = s.points[0]
                val fill = android.graphics.Paint(paint).apply {
                    style = android.graphics.Paint.Style.FILL
                }
                lc.drawCircle(c.x * w, c.y * h, paint.strokeWidth / 2f, fill)
                return@forEach
            }
            if (s.points.size < 2) return@forEach
            val path = android.graphics.Path()
            path.moveTo(s.points[0].x * w, s.points[0].y * h)
            s.points.drop(1).forEach { p -> path.lineTo(p.x * w, p.y * h) }
            if (s.kind == Tool.Neon) {
                val glow = android.graphics.Paint(paint).apply {
                    alpha = 90
                    strokeWidth = sw * 2.6f
                }
                lc.drawPath(path, glow)
                val core = android.graphics.Paint(paint).apply {
                    color = android.graphics.Color.WHITE
                    strokeWidth = sw * 0.6f
                }
                lc.drawPath(path, core)
                return@forEach
            }
            lc.drawPath(path, paint)
        }
        canvas.drawBitmap(layer, 0f, 0f, null)
        layer.recycle()
    }

    marks.forEach { m ->
        when (m) {
            is StickerMark -> {
                val sw = w * m.scale
                val sh = sw * m.bitmap.height / m.bitmap.width.toFloat()
                val cx = m.pos.x * w
                val cy = m.pos.y * h
                val dst = android.graphics.RectF(
                    cx - sw / 2f, cy - sh / 2f, cx + sw / 2f, cy + sh / 2f
                )
                val save = canvas.save()
                canvas.rotate(m.angle, cx, cy)
                canvas.drawBitmap(
                    m.bitmap, null, dst,
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
                )
                canvas.restoreToCount(save)
            }

            is TextMark -> {
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = m.color.toArgb()
                    textSize = m.size * w
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                // По середине строки: на экране надпись тоже стоит серединой
                // в своей точке.
                val mid = (paint.descent() + paint.ascent()) / 2f
                canvas.drawText(m.text, m.pos.x * w, m.pos.y * h - mid, paint)
            }

            else -> Unit
        }
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
            // Нажатие и перетаскивание — двумя обработчиками: тап сам по
            // себе перетаскиванием не считается, а ловить его нужно.
            .pointerInput(colors) {
                detectTapGestures { p -> onChange((p.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(colors) {
                width = size.width.toFloat()
                detectDragGestures(
                    onDragStart = { p -> onChange((p.x / size.width).coerceIn(0f, 1f)) },
                    onDrag = { change, _ ->
                        onChange((change.position.x / size.width).coerceIn(0f, 1f))
                    },
                )
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
