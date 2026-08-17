package com.pismo.messenger.ui.chat

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.formatDuration
import com.pismo.messenger.media.VideoCircleCodec
import com.pismo.messenger.media.WavRecorder
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Запись видео-кружочка — порт VideoCircleRecordForm.cs.
 *
 * Кадры складываются в тот же контейнер PSMOVID1, что и на ПК: JPEG-кадры
 * плюс WAV-дорожка. Поэтому кружочек, записанный здесь, открывается на
 * десктопе, и наоборот. Внешних кодеков и FFmpeg по-прежнему нет.
 */
private const val CIRCLE_SIZE = 240      // сторона кадра в пикселях
private const val TARGET_FPS = 10
/**
 * Потолок длительности кружка — 3 минуты.
 *
 * Не косметика: кадры копятся в памяти и целиком уходят в БД одним BLOB'ом.
 * Они сжимаются в JPEG сразу при захвате (см. frames ниже), поэтому три
 * минуты — это около 18 МБ, что база принимает спокойно.
 */
private const val MAX_SECONDS = 180

@Composable
fun VideoCircleRecorderDialog(
    onDismiss: () -> Unit,
    onRecorded: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Кадры храним УЖЕ сжатыми в JPEG, а не как Bitmap: 240×240 ARGB_8888
    // весит 230 КБ, и трёхминутная запись на 10 fps — это 1800 кадров,
    // больше 400 МБ. В байтах та же запись занимает около 18 МБ.
    //
    // Список синхронизированный: пишет в него поток анализатора камеры, а
    // читает и чистит — главный.
    val frames = remember {
        java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
    }
    val capturing = remember { AtomicBoolean(false) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val audioRecorder = remember { WavRecorder(scope) }

    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(0) }
    var frontCamera by remember { mutableStateOf(Prefs.frontCamera) }
    var busy by remember { mutableStateOf(false) }

    // PreviewView создаётся один раз, а вот привязка камеры — на каждую
    // смену frontCamera. Раньше bindToLifecycle сидел внутри AndroidView.factory,
    // который выполняется единожды, при пустом update: кнопка переключения
    // меняла флаг, и на этом всё заканчивалось — картинка не менялась.
    val previewView = remember { PreviewView(context) }

    // Кадры прореживаем до TARGET_FPS: анализатор отдаёт их гораздо чаще,
    // а в контейнер нужен ровный поток, иначе на ПК кружочек играет рывками.
    val frameIntervalMs = 1000L / TARGET_FPS
    // AtomicLong, а не обычная переменная: отметку читает и пишет поток
    // анализатора, а сбрасывает главный при старте записи.
    val lastFrameAt = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    DisposableEffect(Unit) {
        onDispose {
            capturing.set(false)
            audioRecorder.cancel()
            analysisExecutor.shutdown()
            frames.clear()
        }
    }

    /** Останавливает запись, собирает контейнер и отдаёт его наружу. */
    fun finishRecording() {
        if (!recording) return
        recording = false
        capturing.set(false)
        busy = true
        scope.launch {
            val wav = audioRecorder.stop()
            val snapshot = synchronized(frames) { frames.toList() }
            if (snapshot.isEmpty()) {
                busy = false
                onDismiss()
                return@launch
            }
            val encoded = runCatching {
                withContext(Dispatchers.Default) {
                    VideoCircleCodec.encodeJpeg(snapshot, wav, TARGET_FPS)
                }
            }.getOrNull()
            busy = false
            if (encoded != null) onRecorded(encoded) else onDismiss()
        }
    }

    // Привязка камеры. Ключ — frontCamera: переключатель обязан
    // перепривязать провайдер, иначе картинка остаётся от старой камеры.
    LaunchedEffect(frontCamera) {
        runCatching {
            // get() блокирующий, поэтому уводим его с главного потока.
            // await() из kotlinx-coroutines-guava не берём — ради одного
            // вызова тянуть Guava в APK не стоит.
            val provider = withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get()
            }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analysisExecutor) { proxy ->
                try {
                    if (capturing.get()) {
                        val now = System.currentTimeMillis()
                        if (now - lastFrameAt.get() >= frameIntervalMs) {
                            lastFrameAt.set(now)
                            toCircleFrame(
                                proxy.toBitmap(),
                                proxy.imageInfo.rotationDegrees,
                            )?.let { bmp ->
                                // Жмём здесь же и сразу отпускаем Bitmap —
                                // копить их до конца записи нельзя.
                                frames.add(VideoCircleCodec.toJpeg(bmp))
                                runCatching { bmp.recycle() }
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    proxy.close()
                }
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }
    }

    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        seconds = 0
        while (isActive && recording && seconds < MAX_SECONDS) {
            delay(1000)
            seconds++
        }
        // Дошли до потолка — именно ЗАВЕРШАЕМ запись, а не просто гасим
        // флаг: раньше здесь сбрасывался только он, захват кадров продолжал
        // идти, и записанное никуда не уходило.
        if (recording && seconds >= MAX_SECONDS) finishRecording()
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (recording) "Запись… ${formatDuration(seconds.toLong())}"
                else "Видео-кружочек",
                color = Color.White,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(16.dp))

            Box(
                Modifier
                    .size(280.dp)
                    .clip(CircleShape)
                    .background(PismoColors.BgDarkest),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                if (recording) "‹ Отмена — крестик слева. Максимум ${MAX_SECONDS / 60} мин"
                else "Максимум ${MAX_SECONDS / 60} мин",
                color = PismoColors.TextMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Отмена доступна и ВО ВРЕМЯ записи: прервать неудачный
                // дубль иначе было нечем — оставалось только записать до
                // конца и отправить. Записанное выбрасываем.
                IconButton(
                    enabled = !busy,
                    onClick = {
                        capturing.set(false)
                        recording = false
                        audioRecorder.cancel()
                        frames.clear()
                        onDismiss()
                    },
                ) {
                    Icon(
                        Icons.Default.Close,
                        if (recording) "Отменить запись" else "Закрыть",
                        tint = if (recording) PismoColors.Red else Color.White,
                    )
                }

                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(if (recording) PismoColors.Red else PismoColors.Blurple),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(
                        enabled = !busy,
                        onClick = {
                            if (!recording) {
                                frames.clear()
                                lastFrameAt.set(0L)
                                audioRecorder.start()
                                capturing.set(true)
                                recording = true
                            } else finishRecording()
                        },
                    ) {
                        Icon(
                            if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            if (recording) "Остановить" else "Записать",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }

                // Переключать можно и НА ХОДУ: кнопка была заблокирована
                // условием !recording, поэтому сменить камеру посреди записи
                // было нельзя вовсе. Перепривязка провайдера занимает доли
                // секунды, запись при этом не прерывается — накопленные кадры
                // и флаг захвата живут отдельно от привязки камеры, так что
                // в записи получится короткий стык, а не два разных файла.
                IconButton(
                    enabled = !busy,
                    onClick = {
                        frontCamera = !frontCamera
                        Prefs.frontCamera = frontCamera
                    },
                ) {
                    Icon(Icons.Default.Cameraswitch, "Сменить камеру", tint = Color.White)
                }
            }
        }
    }
}

/**
 * Приводит кадр к квадрату CIRCLE_SIZE×CIRCLE_SIZE с учётом поворота
 * сенсора: без разворота кружочек на ПК лежал бы на боку.
 */
private fun toCircleFrame(source: Bitmap?, rotationDegrees: Int): Bitmap? {
    if (source == null) return null
    return runCatching {
        val side = minOf(source.width, source.height)
        val cropped = Bitmap.createBitmap(
            source,
            (source.width - side) / 2,
            (source.height - side) / 2,
            side, side,
        )
        val matrix = Matrix().apply { if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(cropped, 0, 0, side, side, matrix, true)
        val scaled = Bitmap.createScaledBitmap(rotated, CIRCLE_SIZE, CIRCLE_SIZE, true)

        if (rotated != scaled) rotated.recycle()
        if (cropped != rotated && cropped != scaled) cropped.recycle()
        if (source != cropped) source.recycle()
        scaled
    }.getOrNull()
}
