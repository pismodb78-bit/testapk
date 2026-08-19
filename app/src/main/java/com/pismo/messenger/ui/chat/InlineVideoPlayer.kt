package com.pismo.messenger.ui.chat

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.foundation.Image
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pismo.messenger.data.MediaCache
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.media.MediaSaver
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    onFullscreen: (java.io.File, String, Int) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var file by remember(msgId) { mutableStateOf(MediaCache.fileFor(msgId, "file", fileName)) }
    var cover by remember(msgId) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(msgId) { mutableStateOf(false) }
    var status by remember(msgId) { mutableStateOf("") }
    var playing by remember(msgId) { mutableStateOf(false) }
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
        if (loading) return
        if (file != null) {
            playing = true
            return
        }
        scope.launch {
            loading = true
            status = "Загрузка видео…"
            val data = runCatching {
                ChatRepository.loadFile(msgId, scopeKind, fileName)
            }.getOrNull()
            loading = false
            if (data == null) {
                status = "Не удалось загрузить"
                return@launch
            }
            status = ""
            file = MediaCache.fileFor(msgId, "file", fileName)
            if (file != null) playing = true else status = "Не удалось сохранить во временную папку"
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
                if (loading) {
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
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x22000000))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    fileName,
                    // Подпись лежит уже НЕ на видео, а на самом пузыре, и в
                    // светлой теме белым по светлому её не видно.
                    color = PismoColors.onBubble(isMine),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                Text(
                    status.ifBlank { sizeLabel(file) ?: "Видео" },
                    color = PismoColors.TextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
            IconButton(
                onClick = {
                    val f = file
                    scope.launch {
                        status = "Сохранение…"
                        val bytes = if (f != null) withContext(Dispatchers.IO) { f.readBytes() }
                        else runCatching {
                            ChatRepository.loadFile(msgId, scopeKind, fileName)
                        }.getOrNull()
                        status = when {
                            bytes == null -> "Не удалось загрузить"
                            MediaSaver.saveVideo(context, fileName, bytes) -> "Сохранено в галерею"
                            else -> "Не удалось сохранить"
                        }
                        if (file == null) file = MediaCache.fileFor(msgId, "file", fileName)
                    }
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Сохранить в галерею",
                    tint = PismoColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
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

    DisposableEffect(file.absolutePath) {
        onDispose {
            runCatching { view.stopPlayback() }
            player = null
        }
    }

    // Тикер позиции. Крутится только пока идёт воспроизведение: иначе он
    // будил бы композицию пять раз в секунду на каждом видимом видео.
    LaunchedEffect(isPlaying, prepared) {
        if (!prepared) return@LaunchedEffect
        while (isPlaying) {
            if (!dragging) {
                positionMs = runCatching { view.currentPosition }.getOrDefault(0)
                onPositionChange(positionMs)
            }
            kotlinx.coroutines.delay(200)
        }
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
                        val resumeAt = if (positionMs > 0) positionMs else startAtMs
                        if (resumeAt > 0) seekTo(resumeAt)
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

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .focusProperties { canFocus = false }
                .background(Color(0x99000000))
                // navigationBarsPadding отступает от жестовой полосы, если
                // диалог рисуется под ней; фиксированные 20.dp снизу нужны
                // всё равно — иначе ползунок липнет к самому краю экрана и
                // тащить его пальцем невозможно, палец попадает в систему.
                .then(if (insetBottom) Modifier.navigationBarsPadding() else Modifier)
                .padding(
                    start = 4.dp,
                    end = 4.dp,
                    top = if (insetBottom) 6.dp else 0.dp,
                    bottom = if (insetBottom) 44.dp else 0.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    if (isPlaying) {
                        runCatching { view.pause() }
                        isPlaying = false
                    } else {
                        runCatching { view.start() }
                        isPlaying = true
                    }
                },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }

            Text(
                formatClock(if (dragging) (dragValue * durationMs).toInt() else positionMs),
                color = Color.White,
                fontSize = 10.sp,
            )

            Slider(
                value = if (dragging) dragValue
                else if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    runCatching { view.seekTo((dragValue * durationMs).toInt()) }
                    positionMs = (dragValue * durationMs).toInt()
                    dragging = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = PismoColors.Blurple,
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )

            Text(formatClock(durationMs), color = Color.White, fontSize = 10.sp)

            IconButton(
                onClick = {
                    muted = !muted
                    player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
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

/**
 * Музыкальное вложение (mp3, m4a, flac…): строка с пуском и перемоткой.
 *
 * На ПК такой файл открывается тем же встроенным проигрывателем, что и видео;
 * держать для него отдельное окно на телефоне незачем — достаточно строки в
 * пузыре, как у голосового сообщения.
 */
@Composable
/**
 * Кто из аудио-пузырей играет прямо сейчас.
 *
 * На ПК проигрыватель голосовых один на всё окно, и второй запуск просто
 * забирает его себе. Здесь у каждого пузыря свой MediaPlayer, и без общей
 * отметки два вложения зазвучали бы разом поверх друг друга — а с учётом
 * голосовых сообщений и трёх.
 */
private var activeAudioPlayer: android.media.MediaPlayer? = null

private fun claimAudioFloor(mp: android.media.MediaPlayer?) {
    val previous = activeAudioPlayer
    if (previous !== mp) runCatching { previous?.pause() }
    activeAudioPlayer = mp
    // Голосовые живут в своём проигрывателе — его гасим отдельно.
    com.pismo.messenger.media.WavPlayer.stop()
}

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
    var player by remember(msgId) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(msgId) { mutableStateOf(false) }
    var durationMs by remember(msgId) { mutableIntStateOf(0) }
    var positionMs by remember(msgId) { mutableIntStateOf(0) }

    DisposableEffect(msgId) {
        onDispose {
            if (activeAudioPlayer === player) activeAudioPlayer = null
            runCatching { player?.release() }
            player = null
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

            // Начавшийся разговор музыку глушит: слушать вложение и
            // собеседника разом всё равно невозможно.
            if (com.pismo.messenger.call.ActiveCall.engine != null) {
                runCatching { player?.pause() }
                isPlaying = false
                break
            }

            // Чужой пузырь забрал звук себе — гасим свою кнопку, иначе она
            // осталась бы на «паузе» у молчащего вложения.
            if (activeAudioPlayer !== player) {
                isPlaying = false
                break
            }
            kotlinx.coroutines.delay(300)
        }
    }

    fun startFrom(f: File) {
        runCatching {
            val mp = player ?: MediaPlayer().also { m ->
                m.setDataSource(f.absolutePath)
                m.prepare()
                m.setOnCompletionListener { isPlaying = false; positionMs = 0 }
                player = m
                durationMs = m.duration.coerceAtLeast(0)
            }
            claimAudioFloor(mp)
            mp.start()
            isPlaying = true
        }.onFailure { status = "Не удалось воспроизвести" }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x22000000))
            .clickable {
                if (loading) return@clickable
                val f = file
                when {
                    isPlaying -> { runCatching { player?.pause() }; isPlaying = false }
                    f != null -> startFrom(f)
                    else -> scope.launch {
                        loading = true
                        status = "Загрузка…"
                        val data = runCatching {
                            ChatRepository.loadFile(msgId, scopeKind, fileName)
                        }.getOrNull()
                        loading = false
                        if (data == null) {
                            status = "Не удалось загрузить"
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
            // Полоса появляется, когда файл уже открыт: до этого длина
            // неизвестна, и рисовать нечего.
            if (durationMs > 0) {
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                    onValueChange = {
                        positionMs = it.toInt()
                        runCatching { player?.seekTo(it.toInt()) }
                    },
                    valueRange = 0f..durationMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = PismoColors.onBubble(isMine),
                        activeTrackColor = PismoColors.onBubble(isMine),
                    ),
                    modifier = Modifier.height(18.dp),
                )
            }
            Text(
                status.ifBlank {
                    if (durationMs > 0) "${formatClock(positionMs)} / ${formatClock(durationMs)}"
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
                        ChatRepository.loadFile(msgId, scopeKind, fileName)
                    }.getOrNull()
                    status = when {
                        bytes == null -> "Не удалось загрузить"
                        MediaSaver.saveFile(context, fileName, bytes) -> "Сохранено"
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

/** Что сейчас открыто во весь экран. */
data class FullscreenVideo(val file: File, val fileName: String, val msgId: Int)

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
                insetBottom = true,
                onFullscreen = null,
                onSave = {
                    scope.launch {
                        MediaSaver.saveVideo(context, video.fileName, video.file.readBytes())
                    }
                },
            )
            IconButton(
                onClick = { close() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp),
            ) {
                Icon(Icons.Default.Close, "Закрыть", tint = Color.White)
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
