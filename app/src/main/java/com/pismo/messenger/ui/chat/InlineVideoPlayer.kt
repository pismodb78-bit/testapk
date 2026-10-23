package com.pismo.messenger.ui.chat

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pismo.messenger.data.MediaCache
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.media.MediaSaver
import com.pismo.messenger.media.WavPlayer
import com.pismo.messenger.ui.theme.PismoColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Видео прямо в пузыре — порт InlineVideoPlayer.cs.
 *
 * Логика ПК: тяжёлый плеер не создаётся заранее (там это отдельный WebView2
 * на каждое видео, чат бы встал колом). По умолчанию видна лёгкая обложка с
 * крупной ▶ и именем файла, а настоящий плеер — с перемоткой, громкостью и
 * полным экраном — запускается ТОЛЬКО по нажатию.
 *
 * Здесь то же самое и по той же причине: VideoView держит декодер и
 * поверхность, и десяток таких в ленте съел бы и память, и аппаратные
 * декодеры устройства (их на телефоне единицы).
 *
 * Отличие от ПК, вынужденное: там видео рисуется в пузыре, только если байты
 * УЖЕ скачаны, иначе показывается обычная карточка файла. На телефоне мы
 * тянем вложение из удалённой базы, и качать десятки мегабайт заранее нельзя,
 * поэтому обложка показывается всегда, а загрузка идёт по нажатию — ровно
 * как в карточке файла на ПК («нажмите для воспроизведения»). Если файл уже
 * в кеше, на обложке появляется настоящий первый кадр.
 */
@Composable
fun InlineVideoBubble(
    msgId: Int,
    scopeKind: Scope,
    fileName: String,
    isMine: Boolean = false,
    /** Ключ переписки — чтобы полоса загрузки встала в своём чате. */
    where: String = "",
    onFullscreen: (java.io.File, String, Int) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var file by remember(msgId) { mutableStateOf(MediaCache.fileFor(msgId, "file", fileName)) }
    var cover by remember(msgId) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(msgId) { mutableStateOf(false) }
    var status by remember(msgId) { mutableStateOf("") }
    var playing by remember(msgId) { mutableStateOf(false) }

    // ВИДЕО КАЧАЕТСЯ ОБЩЕЙ ОЧЕРЕДЬЮ, а не прямо отсюда.
    //
    // Раньше загрузка жила в области самого пузыря: стоило уйти из чата или
    // просто отлистать ленту так, чтобы пузырь уехал за край, — и Compose
    // убивал его вместе с загрузкой. Полсотни мегабайт качались заново с
    // нуля. Свернуть приложение значило то же самое.
    //
    // В общей очереди (Transfers) этого нет: она живёт в области процесса,
    // поднимает на время передачи службу и показывает ход и в переписке, и
    // в шторке. Голосовые остались на прямом пути намеренно — они весят
    // сотни килобайт, доезжают за мгновение, и строка в списке передач была
    // бы для них шумом.
    val transfers by com.pismo.messenger.data.Transfers.active.collectAsState()
    var taskId by remember(msgId) { androidx.compose.runtime.mutableLongStateOf(-1L) }
    val task = transfers.firstOrNull { it.id == taskId }
    val taskError = task?.error
    val busy = loading || (task != null && taskError == null)
    val shown = when {
        taskError != null -> taskError
        task != null && task.waiting -> "В очереди…"
        task != null -> "Загрузка видео… " + (task.progress * 100).toInt() + " %"
        else -> status
    }
    // Позиция, с которой продолжает следующий плеер. Одновременно живым
    // должен быть РОВНО ОДИН: пока их было два, инлайновый продолжал играть
    // за диалогом, и слышно было обе дорожки с расхождением в пару секунд —
    // это и есть «рассинхрон звука в полном экране».
    var handoffMs by remember(msgId) { mutableIntStateOf(VideoPositions.get(msgId)) }
    var livePosMs by remember(msgId) { mutableIntStateOf(VideoPositions.get(msgId)) }

    // Обложка = первый кадр. Достаём в фоне: MediaMetadataRetriever
    // раскодирует кадр, на главном потоке это заметная пауза.
    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        if (cover != null) return@LaunchedEffect
        cover = withContext(Dispatchers.IO) { firstFrame(f) }
    }

    DisposableEffect(msgId) {
        onDispose { cover?.let { runCatching { it.recycle() } } }
    }

    fun open() {
        if (busy) return
        if (file != null) {
            playing = true
            return
        }
        // Прошлая попытка сорвалась — её строку закрываем, иначе список
        // передач копил бы по строке на каждое нажатие.
        if (taskError != null) {
            com.pismo.messenger.data.Transfers.cancel(taskId)
            taskId = -1L
        }
        status = ""
        taskId = com.pismo.messenger.data.Transfers.download(
            where = where,
            msgId = msgId,
            scopeKind = scopeKind,
            fileName = fileName,
        ) {
            // Сюда попадаем только при успехе, и байты уже в кеше.
            file = MediaCache.fileFor(msgId, "file", fileName)
            if (file != null) playing = true
            else status = "Не удалось сохранить во временную папку"
        }
    }

    fun saveToGallery() {
        val f = file
        if (f != null) {
            // Файл уже на диске — качать нечего.
            scope.launch {
                status = "Сохранение…"
                val bytes = withContext(Dispatchers.IO) { f.readBytes() }
                status = if (MediaSaver.saveVideo(context, fileName, bytes))
                    "Сохранено в галерею" else "Не удалось сохранить"
            }
            return
        }
        if (busy) return
        if (taskError != null) {
            com.pismo.messenger.data.Transfers.cancel(taskId)
            taskId = -1L
        }
        status = ""
        // Качаем общей очередью: сохранение на полсотни мегабайт переживает
        // и уход из чата, и свёрнутое приложение.
        taskId = com.pismo.messenger.data.Transfers.download(
            where = where,
            msgId = msgId,
            scopeKind = scopeKind,
            fileName = fileName,
        ) { bytes ->
            file = MediaCache.fileFor(msgId, "file", fileName)
            status = if (MediaSaver.saveVideo(context, fileName, bytes))
                "Сохранено в галерею" else "Не удалось сохранить"
        }
    }

    Column(Modifier.widthIn(max = 280.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                // Пропорции ОДИНАКОВЫ до и после запуска. Пока они менялись,
                // нажатие на видео меняло высоту пузыря, лента пересчитывала
                // раскладку и уезжала вниз.
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(PismoColors.BgDarkest)
                // Ни обложка, ни элементы плеера не должны забирать фокус:
                // из-за него лента чата подтягивала пузырь в видимую область.
                .focusProperties { canFocus = false }
                .then(if (playing) Modifier else Modifier.clickable { open() }),
            contentAlignment = Alignment.Center,
        ) {
            val f = file
            if (playing && f != null) {
                VideoSurface(
                    file = f,
                    startAtMs = handoffMs,
                    autoPlay = true,
                    onPositionChange = { livePosMs = it },
                    // Инлайновый плеер именно ЗАКРЫВАЕТСЯ, а не остаётся
                    // играть под диалогом: уход из композиции вызывает
                    // stopPlayback, и второй дорожки просто не возникает.
                    // Полный экран открывает ЭКРАН, а не пузырь. Пока диалог
                    // жил внутри пузыря, он умирал вместе с ним: LazyColumn
                    // уничтожает элемент, ушедший за край видимой области, а
                    // при повороте в альбомную область становится ниже — и
                    // видео закрывалось само, стоило прокрутить или повернуть
                    // телефон.
                    onFullscreen = {
                        VideoPositions.put(msgId, livePosMs)
                        playing = false
                        onFullscreen(f, fileName, msgId)
                    },
                    onSave = {
                        scope.launch {
                            status = "Сохранение…"
                            val ok = MediaSaver.saveVideo(context, fileName, f.readBytes())
                            status = if (ok) "Сохранено в галерею" else "Не удалось сохранить"
                        }
                    },
                )
            } else {
                cover?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Затемнение, иначе белая ▶ на светлом кадре не читается.
                    Box(Modifier.fillMaxSize().background(Color(0x55000000)))
                }
                if (busy) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                } else {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color(0x99000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Воспроизвести",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }

                // «Сохранить» — углом на самой обложке. У запущенного плеера
                // такая кнопка есть в его собственной панели, и вторая, под
                // видео, была лишней.
                IconButton(
                    onClick = { saveToGallery() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                        .focusProperties { canFocus = false },
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Сохранить в галерею",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // ПОД ВИДЕО — НИЧЕГО ЛИШНЕГО.
        //
        // Здесь была постоянная полоска с именем файла, словом «Видео» и
        // второй кнопкой сохранения — та же, что уже есть в панели плеера.
        // Для человека это шум: имя файла у видео ничего не значит, а
        // полоска отодвигала от кадра то единственное, что там и правда
        // нужно, — подпись к видео (и имя отправителя в группе, оно стоит
        // над пузырём). Поэтому строка остаётся только тогда, когда ей есть
        // что сказать: доля загрузки, причина срыва, «Сохранено в галерею».
        if (shown.isNotBlank()) {
            Text(
                shown,
                color = if (taskError != null) PismoColors.Red else PismoColors.TextMuted,
                fontSize = 11.sp,
                maxLines = 2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Сам проигрыватель: поверхность VideoView плюс свои элементы управления.
 *
 * Своя панель, а не системный MediaController, по двум причинам: тот всплывает
 * поверх на три секунды и пропадает (в пузыре чата это неудобно), и он рисуется
 * системной темой — в мессенджере с двумя палитрами это выглядит инородно.
 * Набор кнопок повторяет то, что даёт HTML5-плеер на ПК: пуск/пауза, перемотка,
 * громкость, полный экран.
 */
@Composable
internal fun VideoSurface(
    file: File,
    startAtMs: Int,
    autoPlay: Boolean,
    onFullscreen: (() -> Unit)?,
    onSave: (() -> Unit)?,
    onPositionChange: (Int) -> Unit = {},
    insetBottom: Boolean = false,
    /**
     * Видно ли сейчас управление. Нужно полному экрану: ник и подпись
     * лежат поверх кадра рядом с кнопками и должны уходить вместе с ними,
     * а живёт это состояние здесь.
     */
    onControlsChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val view = remember(file.absolutePath) { VideoView(context) }

    var prepared by remember(file.absolutePath) { mutableStateOf(false) }
    var isPlaying by remember(file.absolutePath) { mutableStateOf(autoPlay) }
    var muted by remember(file.absolutePath) { mutableStateOf(false) }
    var durationMs by remember(file.absolutePath) { mutableIntStateOf(0) }
    var positionMs by remember(file.absolutePath) { mutableIntStateOf(0) }
    var dragging by remember(file.absolutePath) { mutableStateOf(false) }
    var dragValue by remember(file.absolutePath) { mutableFloatStateOf(0f) }
    var player by remember(file.absolutePath) { mutableStateOf<MediaPlayer?>(null) }
    var failed by remember(file.absolutePath) { mutableStateOf(false) }
    /**
     * Перемотка идёт, ответа ещё нет.
     *
     * Пока она в пути, проигрыватель честно отвечает СТАРЫМ временем — и
     * тикер, не зная об этом, возвращал ползунок назад, к тому месту,
     * откуда его только что утащили. Со стороны это и выглядит как «видео
     * перематывается назад»: рывок к прежнему кадру и только потом прыжок
     * вперёд. Поэтому на время перемотки время берём своё, запрошенное.
     */
    var seeking by remember(file.absolutePath) { mutableStateOf(false) }

    /**
     * Видны ли кнопки. Прячем их, пока видео идёт: ради кадра плеер и
     * открывают, а панель поверх него — это чужая треть экрана. Возвращает
     * их касание по видео, как в любом проигрывателе.
     */
    var controls by remember(file.absolutePath) { mutableStateOf(true) }
    /** Любое действие продлевает показ: счётчик перезапускает отсчёт. */
    var poke by remember(file.absolutePath) { mutableIntStateOf(0) }

    DisposableEffect(file.absolutePath) {
        VideoPlayback.begin()
        onDispose {
            VideoPlayback.end()
            runCatching { view.stopPlayback() }
            player = null
        }
    }

    // Тикер позиции. Крутится только пока идёт воспроизведение: иначе он
    // будил бы композицию пять раз в секунду на каждом видимом видео.
    LaunchedEffect(isPlaying, prepared) {
        if (!prepared) return@LaunchedEffect
        while (isPlaying) {
            if (!dragging && !seeking) {
                positionMs = runCatching { view.currentPosition }.getOrDefault(0)
                onPositionChange(positionMs)
            }
            kotlinx.coroutines.delay(200)
        }
    }

    /**
     * Перемотка в нужное место, а не к ближайшему ключевому кадру.
     *
     * Обычный seekTo у Android ищет ПРЕДЫДУЩИЙ ключевой кадр, а в коротком
     * ролике из мессенджера их бывает один — в самом начале. Отсюда и
     * бралось «ставишь на вторую половину, а уезжает в первую».
     * SEEK_CLOSEST доводит до запрошенного места; он есть с Android 8, ниже
     * остаётся прежнее поведение — другого там нет.
     */
    fun seekExact(toMs: Int) {
        val limit = if (durationMs > 0) durationMs else Int.MAX_VALUE
        val target = toMs.coerceIn(0, limit)
        runCatching {
            val mp = player
            if (mp != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                mp.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
            } else {
                view.seekTo(target)
            }
        }
        positionMs = target
        seeking = true
    }

    LaunchedEffect(controls) { onControlsChange(controls) }

    // Кнопки уходят сами — но только пока видео идёт. На паузе они нужны:
    // человек ровно за ними и остановил.
    LaunchedEffect(controls, isPlaying, poke) {
        if (!controls || !isPlaying) return@LaunchedEffect
        kotlinx.coroutines.delay(2800)
        controls = false
    }

    // Ответа о конце перемотки может и не прийти — например, если файл
    // оборвали. Тогда время так и осталось бы замороженным, поэтому ждём
    // его не дольше трёх секунд.
    LaunchedEffect(seeking) {
        if (!seeking) return@LaunchedEffect
        kotlinx.coroutines.delay(3000)
        seeking = false
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { v ->
                view.apply {
                    // VideoView по умолчанию забирает фокус по касанию, а
                    // Compose послушно подтягивает сфокусированную View в
                    // видимую область — из-за этого нажатие на видео дёргало
                    // ленту чата. Фокус нам не нужен: управление своё.
                    isFocusable = false
                    isFocusableInTouchMode = false
                    // Одного VideoView мало: AndroidView заворачивает его в
                    // собственный контейнер, и ФОКУСИРУЕМЫЙ там как раз
                    // контейнер (isFocusable = true, FOCUS_BEFORE_DESCENDANTS).
                    // Именно он и забирал фокус по касанию, из-за чего лента
                    // продолжала прыгать вниз даже после того, как фокус сняли
                    // с самого VideoView. Добраться до контейнера можно только
                    // после присоединения к окну.
                    blockHolderFocus(this)
                    setOnPreparedListener { mp ->
                        player = mp
                        // Перемотка закончилась — снова верим тому, что
                        // говорит сам проигрыватель. Слушатель ставится
                        // здесь: раньше он стоял за пределами этого
                        // обработчика, где самого проигрывателя ещё нет.
                        mp.setOnSeekCompleteListener { seeking = false }
                        prepared = true
                        durationMs = duration.coerceAtLeast(0)
                        mp.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                        // Слушатель срабатывает НЕ ТОЛЬКО при первом
                        // открытии: при сворачивании приложения поверхность
                        // SurfaceView уничтожается, а при возврате VideoView
                        // открывает файл заново и снова зовёт сюда. Раньше
                        // здесь стояло `if (autoPlay) start()` с изначальным
                        // значением параметра — поэтому поставленное на паузу
                        // видео после разворачивания уезжало дальше само.
                        // Смотрим на ТЕКУЩЕЕ состояние и на позицию, где нас
                        // прервали.
                        // Видео забирает звук себе: играющая музыка или голосовое
                    // поверх него — не то, чего ждут от нажатия на ролик.
                    WavPlayer.stop()
                    val resumeAt = if (positionMs > 0) positionMs else startAtMs
                        if (resumeAt > 0) {
                            // Тем же точным способом: иначе возврат из полного
                            // экрана откатывал бы ролик к ближайшему ключевому
                            // кадру, то есть нередко к началу.
                            if (android.os.Build.VERSION.SDK_INT >=
                                android.os.Build.VERSION_CODES.O
                            ) {
                                runCatching { mp.seekTo(resumeAt.toLong(), MediaPlayer.SEEK_CLOSEST) }
                            } else {
                                seekTo(resumeAt)
                            }
                        }
                        if (isPlaying) start() else pause()
                    }
                    setOnCompletionListener {
                        isPlaying = false
                        positionMs = durationMs
                    }
                    setOnErrorListener { _, _, _ ->
                        failed = true
                        isPlaying = false
                        true   // сообщение об ошибке показываем своё
                    }
                    setVideoPath(file.absolutePath)
                }
                view
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (failed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.background(Color(0xAA000000)).padding(12.dp),
            ) {
                Text("Не удалось воспроизвести", color = Color.White, fontSize = 13.sp)
                Text(
                    "Кодек этого файла не поддерживается устройством — " +
                            "сохраните и откройте во внешнем плеере.",
                    color = PismoColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }

        // Касание по кадру — показать или спрятать кнопки. Слой лежит ПОД
        // ними, поэтому нажатия по самим кнопкам сюда не доходят.
        Box(
            Modifier
                .fillMaxSize()
                // Фокус здесь не нужен и ВРЕДЕН: сфокусированный элемент
                // Compose подтягивает в видимую область, и лента чата
                // прыгает под пальцем. Этим же страдали VideoView и его
                // контейнер — см. blockHolderFocus.
                .focusProperties { canFocus = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { controls = !controls; poke++ }
        )

        // ГЛАВНЫЕ КНОПКИ — ПОСЕРЕДИНЕ.
        //
        // Пуск и перемотка на десять секунд там, куда и так смотришь, и
        // полупрозрачные — кадр сквозь них виден. Внизу осталась только
        // полоса времени: ей место у края, а не под пальцем.
        AnimatedVisibility(
            visible = controls,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                modifier = Modifier.focusProperties { canFocus = false },
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundControl(Icons.Default.Replay10, "Назад на 10 секунд") {
                    seekExact(positionMs - 10_000); poke++
                }
                RoundControl(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (isPlaying) "Пауза" else "Воспроизвести",
                    big = true,
                ) {
                    if (isPlaying) {
                        runCatching { view.pause() }
                        isPlaying = false
                    } else {
                        runCatching { view.start() }
                        isPlaying = true
                    }
                    poke++
                }
                RoundControl(Icons.Default.Forward10, "Вперёд на 10 секунд") {
                    seekExact(positionMs + 10_000); poke++
                }
            }
        }

        // ПАНЕЛЬ УПРАВЛЕНИЯ ПРИПОДНЯТА НАД КАДРОМ.
        //
        // Раньше она лежала ровно по нижнему краю, во всю ширину и с
        // прямыми углами: у нижнего края кадра ползунок перемотки липнет к
        // самой границе, промахнуться по нему пальцем проще, чем попасть, а
        // в пузыре чата панель читалась как продолжение переписки, а не как
        // часть видео. Теперь она отступает от краёв и лежит отдельной
        // скруглённой плашкой поверх кадра — так же, как в любом плеере.
        //
        // Порядок отступов важен: первый padding — это поля СНАРУЖИ плашки,
        // второй, после фона, — внутри неё.
        AnimatedVisibility(
            visible = controls,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .focusProperties { canFocus = false }
                // navigationBarsPadding отступает от жестовой полосы, если
                // плеер рисуется под ней; фиксированный отступ снизу нужен
                // всё равно — иначе ползунок липнет к самому краю экрана и
                // тащить его пальцем невозможно, палец попадает в систему.
                .then(if (insetBottom) Modifier.navigationBarsPadding() else Modifier)
                .padding(
                    start = 6.dp,
                    end = 6.dp,
                    top = 6.dp,
                    // Выше прежнего: полоса не должна оказаться на одной
                    // линии с кнопками, которые теперь стоят по центру.
                    bottom = if (insetBottom) 56.dp else 14.dp,
                )
                .clip(RoundedCornerShape(18.dp))
                // Полупрозрачнее: сквозь полосу видно кадр, а читается она
                // по-прежнему — буквы и ползунок белые.
                .background(Color(0x59000000))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatClock(if (dragging) (dragValue * durationMs).toInt() else positionMs),
                color = Color.White,
                fontSize = 10.sp,
            )

            Slider(
                value = if (dragging) dragValue
                else if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                onValueChange = { dragging = true; dragValue = it; poke++ },
                onValueChangeFinished = {
                    seekExact((dragValue * durationMs).toInt())
                    dragging = false
                    poke++
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = PismoColors.Blurple,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    // Ползунок фокусируется сам, а сфокусированное Compose
                    // подтягивает в видимую область — отсюда и «ленту
                    // телепортирует при перемотке».
                    .focusProperties { canFocus = false },
            )

            Text(formatClock(durationMs), color = Color.White, fontSize = 10.sp)

            IconButton(
                onClick = {
                    muted = !muted
                    player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                    poke++
                },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = if (muted) "Включить звук" else "Выключить звук",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }

            if (onSave != null) {
                IconButton(onClick = onSave, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Сохранить",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (onFullscreen != null) {
                IconButton(onClick = onFullscreen, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Fullscreen,
                        contentDescription = "Во весь экран",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        }
    }
}

/**
 * Круглая кнопка поверх кадра. Полупрозрачная: сквозь неё видно видео, а
 * белый значок читается на любом фоне.
 */
@Composable
private fun RoundControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    big: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(if (big) 58.dp else 44.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0x66000000))
            // Без фокуса: сфокусированную кнопку лента подтягивает к себе, и
            // переписка прыгает прямо во время нажатия.
            .focusProperties { canFocus = false }
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(if (big) 32.dp else 24.dp),
        )
    }
}

/**
 * Музыкальное вложение (mp3, m4a, flac…): строка с пуском и перемоткой.
 *
 * На ПК такой файл открывается тем же встроенным проигрывателем, что и видео;
 * держать для него отдельное окно на телефоне незачем — достаточно строки в
 * пузыре, как у голосового сообщения.
 */
@Composable
fun InlineAudioBubble(
    msgId: Int,
    scopeKind: Scope,
    fileName: String,
    isMine: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var file by remember(msgId) { mutableStateOf(MediaCache.fileFor(msgId, "file", fileName)) }
    var loading by remember(msgId) { mutableStateOf(false) }
    var status by remember(msgId) { mutableStateOf("") }

    // Проигрыватель общий на всё приложение, а не свой у каждого пузыря.
    // Со своим два вложения играли разом поверх друг друга, а стоило
    // отлистать ленту — Compose выбрасывал пузырь вместе с проигрывателем, и
    // музыка обрывалась на середине.
    val playingId by WavPlayer.playingId.collectAsState()
    val positionMs by WavPlayer.positionMs.collectAsState()
    val durationMs by WavPlayer.durationMs.collectAsState()
    val isPlaying = playingId == msgId

    fun startFrom(f: File) {
        runCatching { WavPlayer.toggleFile(msgId, f) }
            .onFailure { status = "Не удалось воспроизвести" }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x22000000))
            .focusProperties { canFocus = false }
            .clickable {
                if (loading) return@clickable
                val f = file
                when {
                    isPlaying -> WavPlayer.stop()
                    f != null -> startFrom(f)
                    else -> scope.launch {
                        loading = true
                        status = "Загрузка…"
                        val data = runCatching {
                            ChatRepository.loadFile(msgId, scopeKind, fileName) { f ->
                                status = "Загрузка… " + (f * 100).toInt() + " %"
                            }
                        }.getOrNull()
                        loading = false
                        if (data == null) {
                            status = loadFailure()
                        } else {
                            status = ""
                            file = MediaCache.fileFor(msgId, "file", fileName)
                            file?.let { startFrom(it) }
                        }
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = PismoColors.onBubble(isMine),
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                tint = PismoColors.onBubble(isMine),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                fileName,
                color = PismoColors.onBubble(isMine),
                fontSize = 13.sp,
                maxLines = 1,
            )
            // Полоса — только у играющего: у остальных пузырей длине взяться
            // неоткуда, общий проигрыватель знает лишь про текущий.
            if (isPlaying && durationMs > 0) {
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                    onValueChange = { WavPlayer.seekTo(msgId, it.toInt()) },
                    valueRange = 0f..durationMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = PismoColors.onBubble(isMine),
                        activeTrackColor = PismoColors.onBubble(isMine),
                    ),
                    modifier = Modifier
                        .height(18.dp)
                        // Та же беда, что у видео: сфокусированный ползунок
                        // лента подтягивает к себе, и переписка прыгает.
                        .focusProperties { canFocus = false },
                )
            }
            Text(
                status.ifBlank {
                    if (isPlaying && durationMs > 0)
                        "${formatClock(positionMs)} / ${formatClock(durationMs)}"
                    else sizeLabel(file) ?: "Аудио"
                },
                color = PismoColors.TextMuted,
                fontSize = 11.sp,
            )
        }
        IconButton(
            onClick = {
                val f = file
                scope.launch {
                    status = "Сохранение…"
                    val bytes = if (f != null) withContext(Dispatchers.IO) { f.readBytes() }
                    else runCatching {
                        ChatRepository.loadFile(msgId, scopeKind, fileName) { f ->
                            status = "Сохранение… " + (f * 100).toInt() + " %"
                        }
                    }.getOrNull()
                    status = when {
                        bytes == null -> loadFailure()
                        MediaSaver.saveFile(context, fileName, bytes) -> "Сохранено в загрузки"
                        else -> "Не удалось сохранить"
                    }
                }
            },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.Default.Download,
                contentDescription = "Сохранить",
                tint = PismoColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Размер уже скачанного вложения; до загрузки он нам неоткуда взяться. */
private fun sizeLabel(file: File?): String? =
    file?.takeIf { it.exists() }?.let { formatBytesShort(it.length()) }

/** Первый кадр видео — обложка до запуска плеера. */
private fun firstFrame(file: File): Bitmap? = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(file.absolutePath)
        r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } finally {
        runCatching { r.release() }
    }
}.getOrNull()

/** мм:сс — как на шкале любого плеера. */
internal fun formatClock(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}


/**
 * Где остановились в каждом видео.
 *
 * Пузырь живёт ровно столько, сколько виден: LazyColumn уничтожает элемент,
 * ушедший за край. Без этой памяти возврат из полного экрана (или обычная
 * прокрутка туда-обратно) начинал бы ролик заново.
 */
object VideoPositions {
    private val positions = HashMap<Int, Int>()

    fun get(msgId: Int): Int = synchronized(positions) { positions[msgId] ?: 0 }

    fun put(msgId: Int, ms: Int) = synchronized(positions) {
        if (ms > 0) positions[msgId] = ms else positions.remove(msgId)
        Unit
    }

    fun clear() = synchronized(positions) { positions.clear() }
}

/**
 * Идёт ли сейчас просмотр видео.
 *
 * Лента чата перечитывается опросом раз в пару секунд и, если человек
 * стоял у её конца, доматывает себя вниз. Пока он смотрит ролик, это
 * выглядит как рывок: кадр уезжает из-под пальца ровно тогда, когда его
 * перематывают. Счётчик, а не флаг: плееров на экране может быть два —
 * в пузыре и в полном экране.
 */
object VideoPlayback {
    private val running = java.util.concurrent.atomic.AtomicInteger(0)

    val active: Boolean get() = running.get() > 0

    internal fun begin() { running.incrementAndGet() }

    internal fun end() { if (running.get() > 0) running.decrementAndGet() }
}

/**
 * Что сейчас открыто во весь экран.
 *
 * Подпись и автор едут сюда вместе с файлом: в полном экране от переписки
 * не остаётся ничего, и без них непонятно ни кто это прислал, ни что было
 * написано рядом с видео.
 */
data class FullscreenVideo(
    val file: File,
    val fileName: String,
    val msgId: Int,
    val sender: String = "",
    val caption: String = "",
)

/**
 * Полноэкранный просмотр видео — живёт НА УРОВНЕ ЭКРАНА, а не внутри пузыря.
 *
 * Пока диалог принадлежал пузырю, он умирал вместе с ним: достаточно было
 * прокрутить ленту так, чтобы сообщение ушло за край, — и просмотр
 * закрывался сам. В альбомной ориентации видимая область ниже, поэтому там
 * это случалось сразу после поворота.
 */
@Composable
fun FullscreenVideoHost(
    video: FullscreenVideo?,
    onDismiss: () -> Unit,
) {
    if (video == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var livePos by remember(video.msgId) { mutableIntStateOf(VideoPositions.get(video.msgId)) }
    /** Видно ли управление — его показывает и прячет сам плеер. */
    var chrome by remember(video.msgId) { mutableStateOf(true) }

    fun close() {
        VideoPositions.put(video.msgId, livePos)
        onDismiss()
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(
                file = video.file,
                startAtMs = VideoPositions.get(video.msgId),
                autoPlay = true,
                onPositionChange = { livePos = it },
                onControlsChange = { chrome = it },
                insetBottom = true,
                onFullscreen = null,
                onSave = {
                    scope.launch {
                        MediaSaver.saveVideo(context, video.fileName, video.file.readBytes())
                    }
                },
            )
            // Кто прислал и что написал — над панелью управления.
            //
            // В пузыре это видно и так: имя стоит над сообщением, подпись —
            // под видео. В полном экране не остаётся ни того, ни другого, и
            // ролик висит сам по себе. Полоса держится выше кнопок, чтобы не
            // спорить с перемоткой, и уходит целиком, когда сказать нечего.
            // КТО ПРИСЛАЛ — СВЕРХУ, ЧТО НАПИСАЛ — СНИЗУ.
            //
            // И то и другое уходит вместе с кнопками: ради кадра полный
            // экран и открывают, а подписи поверх него нужны ровно в тот
            // момент, когда человек разбирается, что это и от кого.
            //
            // По строке на каждое — длинная подпись лезла бы на кадр.
            // Что не поместилось, обрывается многоточием; целиком
            // сообщение никуда не делось, оно в переписке.
            AnimatedVisibility(
                visible = chrome && video.sender.isNotBlank(),
                modifier = Modifier.align(Alignment.TopStart),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    video.sender,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        // Справа место под крестик: он остаётся на виду
                        // всегда, иначе из скрытого управления было бы не
                        // выйти.
                        .padding(start = 16.dp, end = 60.dp, top = 18.dp),
                )
            }

            AnimatedVisibility(
                visible = chrome && video.caption.isNotBlank(),
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    video.caption,
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 104.dp),
                )
            }

            // Крестик уходит вместе со всем остальным.
            //
            // Выйти всё равно есть чем: касание по кадру возвращает
            // управление, а системная кнопка «назад» закрывает просмотр и
            // без него.
            AnimatedVisibility(
                visible = chrome,
                modifier = Modifier.align(Alignment.TopEnd),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                IconButton(
                    onClick = { close() },
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(12.dp),
                ) {
                    Icon(Icons.Default.Close, "Закрыть", tint = Color.White)
                }
            }
        }
    }
}

/**
 * Снимает фокусируемость с контейнера, в который AndroidView заворачивает
 * встроенную View. Контейнер создаётся самим Compose, добраться до него
 * можно только через parent и только после присоединения к окну.
 */
private fun blockHolderFocus(view: android.view.View) {
    fun apply(v: android.view.View) {
        (v.parent as? android.view.ViewGroup)?.apply {
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }
    if (view.isAttachedToWindow) apply(view)
    view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: android.view.View) = apply(v)
        override fun onViewDetachedFromWindow(v: android.view.View) = Unit
    })
}

/**
 * Почему вложение не приехало — человеческим языком.
 *
 * «Не удалось загрузить» само по себе не говорит ничего: чинить сеть,
 * адрес сервиса или смириться с потерянным файлом — непонятно. Причину
 * знает файловый сервис, её и показываем.
 */
private fun loadFailure(): String {
    val why = com.pismo.messenger.data.FileService.lastError
    return if (why.isNullOrBlank()) "Не удалось загрузить" else "Не удалось загрузить: $why"
}
