package com.pismo.messenger.ui.call

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NoiseAware
import androidx.compose.material.icons.filled.NoiseControlOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.pismo.messenger.call.ActiveCall
import com.pismo.messenger.call.CallEngine
import com.pismo.messenger.call.IncomingCallMonitor
import com.pismo.messenger.call.LiveKitToken
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.repo.CallRepository
import com.pismo.messenger.net.SignalingClient
import com.pismo.messenger.ui.components.LetterAvatar
import com.pismo.messenger.ui.components.UserAvatar
import com.pismo.messenger.ui.theme.PismoColors
import com.pismo.messenger.ui.theme.PismoTheme
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Экран звонка. Комната LiveKit определяется так же, как на ПК:
 *  • личный или групповой звонок — строковый id call-сессии;
 *  • голосовой канал сервера — "vch_<id канала>".
 */
class CallActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_PEER_NAME = "peer_name"
        const val EXTRA_WITH_VIDEO = "with_video"
        const val EXTRA_IS_CALLER = "is_caller"
        const val EXTRA_CHANNEL_ID = "channel_id"

        /**
         * true — активити открыта системой по full-screen intent, звонок
         * ещё НЕ принят. Автоматически входить в комнату в этом случае
         * нельзя: на заблокированном экране Android поднимает активити сам,
         * без участия человека, и звонок бы отвечался сам собой.
         */
        const val EXTRA_RINGING = "ringing"

        /**
         * true — пользователь нажал «Принять» прямо в шторке. Экран «вам
         * звонят» пропускаем и сразу заходим в комнату.
         */
        const val EXTRA_ACCEPT_NOW = "accept_now"
    }

    private lateinit var engine: CallEngine
    private var sessionId = -1
    private var channelId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        // Звонок уже идёт — значит окно просто открыли заново из дока.
        // Берём ЖИВОЙ движок и ни в какую комнату повторно не заходим.
        // Область корутин у движка процессная (ActiveCall.scope), а не
        // lifecycleScope: с закрытием окна разговор не должен обрываться.
        val resuming = ActiveCall.isActive && ActiveCall.engine != null
        engine = ActiveCall.engine ?: CallEngine(applicationContext, ActiveCall.scope)

        val peerId = intent.getIntExtra(EXTRA_PEER_ID, -1)
        val groupId = intent.getIntExtra(EXTRA_GROUP_ID, -1)
        val peerName = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        val withVideo = intent.getBooleanExtra(EXTRA_WITH_VIDEO, false)
        val isCaller = intent.getBooleanExtra(EXTRA_IS_CALLER, false)
        sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        channelId = intent.getIntExtra(EXTRA_CHANNEL_ID, -1)
        // accept_now перекрывает ringing: из шторки звонок уже принят.
        val acceptNow = intent.getBooleanExtra(EXTRA_ACCEPT_NOW, false)
        val ringing = intent.getBooleanExtra(EXTRA_RINGING, false) && !acceptNow

        if (acceptNow) {
            IncomingCallMonitor.incoming.value?.let { IncomingCallMonitor.accepted(this, it) }
            lifecycleScope.launch { runCatching { if (sessionId > 0) CallRepository.accept(sessionId) } }
        }

        setContent {
            PismoTheme {
                var answered by remember { mutableStateOf(!ringing || resuming) }

                if (!answered) {
                    RingingScreen(
                        callerName = peerName,
                        hasVideo = withVideo,
                        onAccept = {
                            answered = true
                            IncomingCallMonitor.incoming.value?.let {
                                IncomingCallMonitor.accepted(this@CallActivity, it)
                            }
                            joinRoom(peerId, groupId, peerName, withVideo, isCaller)
                        },
                        onReject = {
                            IncomingCallMonitor.incoming.value?.let {
                                IncomingCallMonitor.rejected(this@CallActivity, it)
                            }
                            lifecycleScope.launch {
                                runCatching { if (sessionId > 0) CallRepository.reject(sessionId) }
                                finish()
                            }
                        },
                    )
                } else {
                    // Трубку можно положить и из дока, не открывая это окно.
                    // Тогда разговора уже нет, а окно осталось бы висеть в
                    // стеке задач — вернулись бы в мёртвый экран.
                    val live by ActiveCall.current.collectAsState()
                    // Именно «был и пропал», а не просто «нет»: в комнату мы
                    // ещё только заходим, и в первые секунды ActiveCall пуст —
                    // окно закрылось бы само на каждом исходящем звонке.
                    var wasLive by remember { mutableStateOf(live != null) }
                    LaunchedEffect(live) {
                        if (live != null) wasLive = true
                        else if (wasLive && !isFinishing) finish()
                    }

                    CallScreen(
                        engine = engine,
                        peerName = peerName,
                        onHangup = { finishCall() },
                    )
                }
            }
        }

        if (!ringing && !resuming) joinRoom(peerId, groupId, peerName, withVideo, isCaller)

        // Состояние для дока и heartbeat голосового канала теперь крутятся в
        // ActiveCall, на уровне процесса: со свёрнутым окном они обязаны
        // продолжаться, иначе остальные участники увидят, что вы вышли.
    }

    /** Показ поверх экрана блокировки — иначе входящий ночью просто не увидят. */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun joinRoom(
        peerId: Int,
        groupId: Int,
        peerName: String,
        withVideo: Boolean,
        isCaller: Boolean,
    ) {
        // Право начать звонок берётся ОДИН раз на процесс. Пока идут запросы
        // к базе, ActiveCall ещё пуст, и без этой защёлки второе окно, поднятое
        // повторным нажатием, спокойно заводило вторую сессию, слало второе
        // приглашение и создавало второй движок со своим микрофоном.
        if (!ActiveCall.claimStart()) {
            finish()
            return
        }

        lifecycleScope.launch {
            try {
            val room = when {
                // Голосовой канал сервера — сессии в БД нет вовсе.
                channelId > 0 -> LiveKitToken.roomForVoiceChannel(channelId)

                else -> {
                    if (sessionId <= 0) {
                        // Присоединяемся к идущему звонку либо создаём новый.
                        val existing = CallRepository.ongoingFor(
                            peerId.takeIf { it > 0 }, groupId.takeIf { it >= 0 }
                        )
                        sessionId = if (existing > 0) existing
                        else CallRepository.createCall(
                            peerId.takeIf { it > 0 }, groupId.takeIf { it >= 0 }, withVideo
                        )

                        if (isCaller && existing <= 0) {
                            if (groupId >= 0) SignalingClient.send("incoming_call", 0, sessionId, "group")
                            else SignalingClient.send(
                                "incoming_call", peerId, sessionId,
                                if (withVideo) "video" else "audio"
                            )
                        }
                    }
                    LiveKitToken.roomForCall(sessionId)
                }
            }

            if (sessionId > 0) CallRepository.join(sessionId)

            // Док активного звонка на остальных экранах узнаёт о звонке отсюда.
            ActiveCall.start(
                ActiveCall.Info(
                    title = peerName,
                    sessionId = sessionId,
                    channelId = channelId,
                    peerId = peerId,
                    groupId = groupId,
                    withVideo = withVideo,
                ),
                engine,
            )

            engine.join(room, withVideo)

            // ГЛАВНОЕ ЗДЕСЬ. join() наружу ничего не бросает: неудачу он
            // складывает в своё состояние. Раньше это значило вот что —
            // ActiveCall.start выше уже объявил разговор идущим, комната не
            // подключилась, а «идущий разговор» так и остался висеть. Док его
            // показывал, а следующая попытка куда-нибудь зайти упиралась в
            // проверку «занято» и молча закрывала окно. Выглядело как «в
            // голосовой канал иногда не пускает», и чаще всего — именно в тот,
            // где давно сидят: подключение к комнате с несколькими живыми
            // дорожками дольше и срывается легче.
            if (engine.state.value == CallEngine.State.FAILED) {
                ActiveCall.hangUp()
                runCatching {
                    android.widget.Toast.makeText(
                        this@CallActivity,
                        engine.error.value ?: "Не удалось подключиться к каналу",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
            } catch (e: Throwable) {
                // Право начать звонок надо отпустить обязательно: иначе после
                // одной сорвавшейся попытки — а на плохой сети сорваться может
                // любой из запросов — звонки не пошли бы вообще до перезапуска
                // приложения. Это же касается и отмены: окно закрыли, не
                // дождавшись подключения.
                ActiveCall.releaseStart()
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("CallActivity", "не удалось начать звонок", e)
                runCatching {
                    android.widget.Toast.makeText(
                        this@CallActivity,
                        "Не удалось начать звонок: нет связи",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                finish()
            }
        }
    }

    /**
     * Завершение разговора. Корутина живёт в процессной области, а не в
     * lifecycleScope: активити закрывается сразу, и привязанная к ней
     * корутина не успела бы даже сообщить серверу об уходе из канала.
     */
    private fun finishCall() {
        // Сама трубка кладётся в ActiveCall: та же кнопка есть и в доке, а
        // порядок «отметиться в базе → выйти из комнаты» должен быть один.
        ActiveCall.hangUp()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Закрытие окна разговор НЕ завершает. Движок и все его подписки
        // живут в ActiveCall.scope, поэтому звонок продолжается, а вернуться
        // в него можно из дока — как на ПК, где закрытие окна тоже не кладёт
        // трубку. Единственная точка завершения — finishCall().
    }
}

/**
 * Экран «вам звонят», когда активити подняли по full-screen intent.
 * Пока человек не нажал «Принять», в комнату мы не заходим.
 */
@Composable
private fun RingingScreen(
    callerName: String,
    hasVideo: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(PismoColors.BgDarkest)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LetterAvatar(0, callerName, 96.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            callerName.ifBlank { "Входящий звонок" },
            color = PismoColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            if (hasVideo) "Входящий видеозвонок" else "Входящий звонок",
            color = PismoColors.TextMuted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(48.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(PismoColors.Red),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onReject) {
                    Icon(Icons.Default.CallEnd, "Отклонить", tint = Color.White)
                }
            }
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(PismoColors.Green),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onAccept) {
                    Icon(Icons.Default.Call, "Принять", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun CallScreen(
    engine: CallEngine,
    peerName: String,
    onHangup: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val participants by engine.participants.collectAsState()
    val micMuted by engine.micMuted.collectAsState()
    val deafened by engine.deafened.collectAsState()
    val outputs by engine.audioOutputs.collectAsState()
    val output by engine.audioOutput.collectAsState()
    var showOutputs by remember { mutableStateOf(false) }
    val cameraOn by engine.cameraOn.collectAsState()
    val sharing by engine.screenSharing.collectAsState()
    val screenAudioOn by engine.screenAudioOn.collectAsState()
    val frontCamera by engine.frontCamera.collectAsState()
    val state by engine.state.collectAsState()
    val error by engine.error.collectAsState()

    var showShareOptions by remember { mutableStateOf(false) }
    var denoise by remember { mutableStateOf(Prefs.noiseSuppression) }
    var shareAudio by remember { mutableStateOf(Prefs.shareScreenAudio) }
    var volumeFor by remember { mutableStateOf<CallEngine.ParticipantState?>(null) }
    // Плитка, развёрнутая во весь экран. Ради демонстрации это и делается:
    // чужой экран в четверти телефона не читается вообще.
    var fullscreenOf by remember { mutableStateOf<String?>(null) }
    val screenFrozen by engine.screenFrozen.collectAsState()

    // Альбомная ориентация приходит сама: активность объявлена с
    // configChanges, так что поворот её не пересоздаёт, а Compose видит
    // новую конфигурацию и перекладывает экран.
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // «Назад» СВОРАЧИВАЕТ окно звонка, а не завершает разговор: окно
    // закрывается, разговор продолжается, и по приложению можно ходить
    // свободно — читать чат, крутить шумодав, — а вернуться одним нажатием
    // на полоску активного звонка снизу. Завершение осталось за красной
    // кнопкой, как и на ПК.
    val activity = androidx.compose.ui.platform.LocalContext.current as? Activity
    androidx.activity.compose.BackHandler(enabled = true) {
        activity?.finish()
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            scope.launch { engine.startScreenShare(result.data!!, shareAudio) }
        }
    }

    if (showShareOptions) {
        AlertDialog(
            onDismissRequest = { showShareOptions = false },
            containerColor = PismoColors.BgSidebar,
            title = { Text("Демонстрация экрана", color = PismoColors.TextPrimary) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = shareAudio,
                            onCheckedChange = {
                                shareAudio = it
                                Prefs.shareScreenAudio = it
                            },
                        )
                        Text("Передавать системный звук", color = PismoColors.TextPrimary, fontSize = 14.sp)
                    }
                    Text(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            "Звук приложений, которые сами не запретили его записывать. " +
                                    "Второго разрешения система не спросит."
                        else
                            "Захват системного звука доступен с Android 10 — на этой версии " +
                                    "демонстрация пойдёт без звука.",
                        color = PismoColors.TextMuted, fontSize = 11.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showShareOptions = false
                    val mgr = context.getSystemService(MediaProjectionManager::class.java)
                    mgr?.createScreenCaptureIntent()?.let { screenCaptureLauncher.launch(it) }
                }) { Text("Начать", color = PismoColors.Cyan) }
            },
            dismissButton = {
                TextButton(onClick = { showShareOptions = false }) {
                    Text("Отмена", color = PismoColors.TextMuted)
                }
            },
        )
    }

    volumeFor?.let { p -> ParticipantVolumeDialog(engine, p) { volumeFor = null } }

    if (showOutputs) {
        AudioOutputDialog(
            available = outputs,
            selected = output,
            onPick = {
                engine.selectAudioOutput(it)
                showOutputs = false
            },
            onBluetoothGranted = { engine.refreshAudioOutputs() },
            onDismiss = { showOutputs = false },
        )
    }

    // На разговорном динамике экран должен гаснуть у уха — как в обычной
    // звонилке. Иначе щекой нажимаются кнопки: «завершить» в том числе.
    ProximityScreenOff(active = output == CallEngine.AudioOutput.EARPIECE)

    val header: @Composable () -> Unit = {
        Text(
            peerName.ifBlank { "Звонок" },
            color = PismoColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            when (state) {
                CallEngine.State.CONNECTING -> "Подключение…"
                CallEngine.State.CONNECTED ->
                    if (participants.size <= 1) "Ожидание собеседника…"
                    else "В звонке: ${participants.size}"
                CallEngine.State.FAILED -> error ?: "Ошибка подключения"
                CallEngine.State.DISCONNECTED -> "Звонок завершён"
                else -> ""
            },
            color = if (state == CallEngine.State.FAILED) PismoColors.Red else PismoColors.TextMuted,
            fontSize = 13.sp,
        )
        if (screenFrozen) {
            // Android остановил захват сам — чаще всего потому, что показывали
            // одну программу и свернули её. Дорожку мы держим живой чёрным
            // кадром, но человек должен понимать, почему у собеседника чернота.
            Text(
                "Захват экрана остановлен системой — собеседник видит чёрный " +
                        "экран. Нажмите «Экран», чтобы начать показ заново.",
                color = PismoColors.Yellow,
                fontSize = 12.sp,
            )
        }
    }

    val grid: @Composable (Modifier) -> Unit = { mod ->
        LazyVerticalGrid(
            // В альбомной ориентации по высоте места мало, зато по ширине
            // много: плитки идут в два столбца уже с двух участников.
            columns = GridCells.Fixed(
                when {
                    participants.size <= 1 -> 1
                    landscape -> 2
                    participants.size <= 2 -> 1
                    else -> 2
                }
            ),
            modifier = mod,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(participants, key = { it.identity }) { p ->
                ParticipantTile(
                    engine = engine,
                    p = p,
                    landscape = landscape,
                    onClick = { if (p.screenTrack != null || p.cameraTrack != null) fullscreenOf = p.identity },
                    onLongPress = { if (!p.isLocal) volumeFor = p },
                )
            }
        }
    }

    val controls: @Composable (Boolean) -> Unit = { vertical ->
        // itemMod приходит снаружи: в горизонтальном ряду это weight(1f) —
        // семь кружков по 52 dp с подписями в 360 dp экрана иначе не влезают
        // и молча обрезаются справа (вместе с кнопкой «Выйти»).
        val buttons: @Composable (Modifier) -> Unit = { itemMod ->
            CallButton(
                icon = if (micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                active = !micMuted,
                label = "Микрофон",
                modifier = itemMod,
                showLabel = !vertical,
                danger = micMuted,
            ) { scope.launch { engine.toggleMic() } }

            // Полный мут входящего ГОЛОСА (звук чужой демки продолжает
            // играть — так же на ПК). Первое нажатие глушит заодно и
            // микрофон, включение микрофона снимает мут обратно.
            CallButton(
                icon = if (deafened) Icons.Default.HeadsetOff else Icons.Default.Headset,
                active = !deafened,
                label = "Выкл. звук",
                modifier = itemMod,
                showLabel = !vertical,
                danger = deafened,
            ) { scope.launch { engine.toggleDeafen() } }

            // Шумодав наш, а не из WebRTC, поэтому переключается прямо в
            // разговоре — перезаходить в комнату не нужно.
            CallButton(
                icon = if (denoise) Icons.Default.NoiseAware else Icons.Default.NoiseControlOff,
                active = denoise,
                label = "Шумодав",
                modifier = itemMod,
                showLabel = !vertical,
            ) {
                denoise = !denoise
                engine.setNoiseSuppression(denoise)
            }

            CallButton(
                icon = if (cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                active = cameraOn,
                label = "Камера",
                modifier = itemMod,
                showLabel = !vertical,
            ) { scope.launch { engine.toggleCamera() } }

            // Кнопка появляется только при включённой камере: переключать
            // фронталку на выключенной камере нечего.
            if (cameraOn) {
                CallButton(
                    icon = Icons.Default.FlipCameraAndroid,
                    active = true,
                    label = if (frontCamera) "Фронталка" else "Основная",
                    modifier = itemMod,
                    showLabel = !vertical,
                ) { engine.switchCamera() }
            }

            // Куда выводить звук: bluetooth-гарнитура, проводные наушники,
            // динамик телефона, разговорный динамик у уха.
            //
            // Кнопка стоит ВСЕГДА, даже когда выход всего один. Раньше она
            // появлялась от двух — и ровно в том случае, когда гарнитуры не
            // видно, у человека не было ни возможности её выбрать, ни намёка
            // на то, почему её нет.
            CallButton(
                icon = when (output) {
                    CallEngine.AudioOutput.BLUETOOTH -> Icons.Default.BluetoothAudio
                    CallEngine.AudioOutput.WIRED -> Icons.Default.Headphones
                    CallEngine.AudioOutput.EARPIECE -> Icons.Default.PhoneInTalk
                    else -> Icons.Default.VolumeUp
                },
                active = true,
                label = outputLabel(output),
                modifier = itemMod,
                showLabel = !vertical,
            ) { showOutputs = true }

            CallButton(
                icon = if (sharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                active = sharing,
                label = "Экран",
                modifier = itemMod,
                showLabel = !vertical,
            ) {
                if (sharing) {
                    scope.launch { engine.stopScreenShare() }
                } else {
                    showShareOptions = true
                }
            }

            Column(itemMod, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(PismoColors.Red),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = onHangup) {
                        Icon(Icons.Default.CallEnd, "Завершить", tint = Color.White)
                    }
                }
                if (!vertical) {
                    Text(
                        "Выйти",
                        color = PismoColors.Red,
                        fontSize = 9.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
            }
        }

        if (vertical) {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { buttons(Modifier) }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Top,
            ) { buttons(Modifier.weight(1f)) }
        }
    }

    if (landscape) {
        // Шапку в альбомной убираем совсем: две строки текста съедали треть
        // и без того невысокого экрана, а имя собеседника и так на плитке.
        Row(
            Modifier
                .fillMaxSize()
                .background(PismoColors.BgDarkest)
                .padding(10.dp)
        ) {
            grid(Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            controls(true)
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .background(PismoColors.BgDarkest)
                .padding(12.dp)
        ) {
            header()
            Spacer(Modifier.height(12.dp))
            grid(Modifier.weight(1f))
            Spacer(Modifier.height(12.dp))
            controls(false)
        }
    }

    // Плитка во весь экран — прежде всего ради чужой демонстрации.
    //
    // Участник, пропавший из списка, закрывает просмотр сразу. А вот
    // исчезнувшая дорожка — НЕТ: на ПК это отдельный механизм (_resumeWatch),
    // который помнит просмотр 15 секунд и сам возобновляет его при быстром
    // перезапуске демонстрации, чтобы не пришлось заново жать «Смотреть».
    // Здесь то же самое: держим окно открытым и ждём новую дорожку.
    fullscreenOf?.let { id ->
        val p = participants.firstOrNull { it.identity == id }
        if (p == null) {
            fullscreenOf = null
        } else {
            FullscreenTile(engine, p) { fullscreenOf = null }
        }
    }
}

/**
 * Плитка во весь экран. Нужна для демонстрации: чужой экран, ужатый в
 * четверть телефона, нечитаем в принципе, а вертикальный экран телефона
 * внутри горизонтальной плитки превращается в узкую полоску. Здесь картинка
 * занимает всё, что есть, и в альбомной ориентации это наконец нормальный
 * просмотр.
 */
@Composable
private fun FullscreenTile(
    engine: CallEngine,
    p: CallEngine.ParticipantState,
    onDismiss: () -> Unit,
) {
    val track = p.screenTrack ?: p.cameraTrack

    // Сколько ждать возвращения дорожки, прежде чем закрыть просмотр.
    // 15 секунд — то же окно, что и у _resumeWatch на ПК.
    LaunchedEffect(track == null) {
        if (track != null) return@LaunchedEffect
        delay(15_000)
        onDismiss()
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Приёмник кадров обязателен к снятию: без этого закрытый полный
        // экран продолжает получать кадры и держать поверхность, а на
        // телефоне аппаратных декодеров считанные единицы.
        var renderer by remember { mutableStateOf<TextureViewRenderer?>(null) }
        DisposableEffect(track) {
            onDispose {
                val r = renderer
                renderer = null
                if (r != null) {
                    runCatching { track?.removeRenderer(r) }
                    runCatching { r.release() }
                }
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (track != null) {
                AndroidView(
                    factory = { ctx ->
                        TextureViewRenderer(ctx).also { view ->
                            engine.initRenderer(view)
                            runCatching { track.addRenderer(view) }
                            renderer = view
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (track == null) {
                // Дорожки нет — демонстрацию перезапускают. Окно не
                // закрываем: новая дорожка подхватится сама, и просмотр
                // продолжится с того же места.
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = PismoColors.Blurple)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Демонстрация перезапускается…",
                        color = PismoColors.TextMuted,
                        fontSize = 13.sp,
                    )
                }
            }

            Text(
                p.name,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .statusBarsPadding()
                    .padding(12.dp),
            )
            IconButton(
                onClick = onDismiss,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParticipantTile(
    engine: CallEngine,
    p: CallEngine.ParticipantState,
    landscape: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val track = p.screenTrack ?: p.cameraTrack

    // «Смотрим демку» = плитка с экраном на виду. От этого зависит, слышно
    // ли её звук: на ПК звук демонстрации намеренно молчит, пока её не
    // смотрят, иначе в общем звонке одновременно орали бы все стримы.
    DisposableEffect(p.identity, p.screenTrack != null) {
        if (!p.isLocal && p.screenTrack != null) engine.setScreenAudioWatched(p.identity, true)
        onDispose {
            if (!p.isLocal) engine.setScreenAudioWatched(p.identity, false)
        }
    }

    // Пропорции плитки. 16:9 годится для камеры, но НЕ для демонстрации с
    // телефона: там кадр вертикальный, и внутри горизонтальной плитки от
    // него остаётся узкая полоска по центру — отсюда и ощущение, что демка
    // «мыльная», хотя на самом деле она просто крошечная.
    val ratio = when {
        track == null -> 1f
        p.screenTrack != null && !landscape -> 3f / 4f
        else -> 16f / 9f
    }

    // Зелёная рамка у говорящего — как на ПК, где плитка активного
    // участника обводится по контуру. Имя жирным без рамки читалось плохо:
    // на плитке с видео его почти не видно.
    val speakingBorder by androidx.compose.animation.animateColorAsState(
        targetValue = if (p.speaking) PismoColors.Green else Color.Transparent,
        label = "speaking",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(12.dp))
            .background(PismoColors.BgSidebar)
            .border(3.dp, speakingBorder, RoundedCornerShape(12.dp))
            .focusProperties { canFocus = false }
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        if (track != null) {
            AndroidView(
                factory = { ctx ->
                    TextureViewRenderer(ctx).also { renderer ->
                        engine.initRenderer(renderer)
                        runCatching { track.addRenderer(renderer) }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            UserAvatar(p.userId, p.name, 64.dp)
        }

        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                p.name + if (p.isLocal) " (вы)" else "",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = if (p.speaking) FontWeight.Bold else FontWeight.Normal,
            )
            if (p.micMuted) {
                Spacer(Modifier.size(4.dp))
                Icon(
                    Icons.Default.MicOff, "Микрофон выключен",
                    tint = PismoColors.Red, modifier = Modifier.size(14.dp),
                )
            }
            if (p.deafened) {
                Spacer(Modifier.size(4.dp))
                Icon(
                    Icons.Default.HeadsetOff, "Звук выключен",
                    tint = PismoColors.Red, modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * Громкость конкретного участника — то же, что ПКМ по плитке на ПК.
 * Голос и звук его демонстрации регулируются раздельно.
 */
@Composable
private fun ParticipantVolumeDialog(
    engine: CallEngine,
    p: CallEngine.ParticipantState,
    onDismiss: () -> Unit,
) {
    var voice by remember(p.identity) { mutableStateOf(engine.volumeOf(p.identity)) }
    var demo by remember(p.identity) { mutableStateOf(engine.demoVolumeOf(p.identity)) }
    var muted by remember(p.identity) { mutableStateOf(engine.isMutedFor(p.identity)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = { Text(p.name, color = PismoColors.TextPrimary) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = muted,
                        onCheckedChange = {
                            muted = it
                            engine.setParticipantMuted(p.identity, it)
                        },
                    )
                    Text("Заглушить голос", color = PismoColors.TextPrimary, fontSize = 14.sp)
                }

                Text(
                    "Громкость голоса: ${(voice * 100).toInt()}%",
                    color = PismoColors.TextMuted, fontSize = 12.sp,
                )
                Slider(
                    value = voice,
                    onValueChange = {
                        voice = it
                        engine.setParticipantVolume(p.identity, it)
                    },
                    valueRange = 0f..3f,
                    enabled = !muted,
                )

                if (p.screenTrack != null) {
                    Text(
                        "Громкость демонстрации: ${(demo * 100).toInt()}%",
                        color = PismoColors.TextMuted, fontSize = 12.sp,
                    )
                    Slider(
                        value = demo,
                        onValueChange = {
                            demo = it
                            engine.setScreenShareVolume(p.identity, it)
                        },
                        valueRange = 0f..3f,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Готово", color = PismoColors.Cyan) }
        },
    )
}

@Composable
private fun CallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    /**
     * Красный кружок вместо серого — «выключено намеренно и это слышно всем»:
     * мьют микрофона и «наушники». Ровно так же покрашены эти две кнопки на
     * ПК (PaintMuteButton/PaintDeafenButton), и по цвету сразу видно, что
     * нажатие сработало.
     */
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    when {
                        danger -> PismoColors.Red
                        active -> PismoColors.BgHover
                        else -> PismoColors.BgElevated
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onClick) {
                Icon(
                    icon, label,
                    tint = if (active || danger) Color.White else PismoColors.TextMuted,
                )
            }
        }

        // Подпись под кнопкой. Без неё ряд одинаковых кружков читается
        // только по иконкам, а «наушники» и вовсе непонятны: на ПК у кнопки
        // есть всплывающая подсказка, здесь её негде показать.
        if (showLabel) {
            Text(
                label,
                color = if (danger) PismoColors.Red else PismoColors.TextMuted,
                fontSize = 9.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

/** Человеческое название выхода — оно же подпись под кнопкой. */
private fun outputLabel(out: CallEngine.AudioOutput?): String = when (out) {
    CallEngine.AudioOutput.BLUETOOTH -> "Гарнитура"
    CallEngine.AudioOutput.WIRED -> "Наушники"
    CallEngine.AudioOutput.EARPIECE -> "У уха"
    else -> "Динамик"
}

/** Куда выводить звук разговора. */
@Composable
private fun AudioOutputDialog(
    available: List<CallEngine.AudioOutput>,
    selected: CallEngine.AudioOutput?,
    onPick: (CallEngine.AudioOutput) -> Unit,
    onBluetoothGranted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // Без BLUETOOTH_CONNECT системный перечислитель гарнитур бросает
    // SecurityException, и audioswitch просто не видит ни одной — список
    // выходов молча остаётся без Bluetooth. Разрешение спрашивается на
    // старте приложения вместе с тремя другими, и отказать там проще
    // простого; здесь его можно выдать осмысленно.
    var btGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.BLUETOOTH_CONNECT
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val askBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        btGranted = granted
        if (granted) onBluetoothGranted()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgElevated,
        title = { Text("Вывод звука", color = PismoColors.TextPrimary) },
        text = {
            Column {
                // Порядок фиксированный, а не как отдал список: иначе пункты
                // прыгали бы местами при каждом подключении гарнитуры.
                CallEngine.AudioOutput.entries.filter { it in available }.forEach { out ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(out) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when (out) {
                                CallEngine.AudioOutput.BLUETOOTH -> Icons.Default.BluetoothAudio
                                CallEngine.AudioOutput.WIRED -> Icons.Default.Headphones
                                CallEngine.AudioOutput.EARPIECE -> Icons.Default.PhoneInTalk
                                else -> Icons.Default.VolumeUp
                            },
                            null,
                            tint = if (out == selected) PismoColors.Green else PismoColors.TextMuted,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            when (out) {
                                CallEngine.AudioOutput.BLUETOOTH -> "Bluetooth-гарнитура"
                                CallEngine.AudioOutput.WIRED -> "Проводные наушники"
                                CallEngine.AudioOutput.EARPIECE -> "Разговорный динамик"
                                else -> "Динамик телефона"
                            },
                            color = if (out == selected) PismoColors.Green else PismoColors.TextPrimary,
                            fontSize = 15.sp,
                        )
                    }
                }

                // Гарнитуры в списке нет — объясняем, почему, вместо того
                // чтобы оставлять человека с одним «динамиком».
                if (CallEngine.AudioOutput.BLUETOOTH !in available) {
                    Spacer(Modifier.height(8.dp))
                    if (!btGranted) {
                        Text(
                            "Bluetooth-гарнитуру не видно: приложению не выдан " +
                                    "доступ к Bluetooth. Без него система не " +
                                    "показывает подключённые гарнитуры.",
                            color = PismoColors.TextMuted,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            onClick = {
                                askBluetooth.launch(
                                    android.Manifest.permission.BLUETOOTH_CONNECT
                                )
                            }
                        ) {
                            Text("Разрешить доступ к Bluetooth", color = PismoColors.Blurple)
                        }
                    } else {
                        Text(
                            "Bluetooth-гарнитура не найдена. Проверьте, что она " +
                                    "подключена к телефону и поддерживает режим " +
                                    "разговора (профиль гарнитуры, а не только " +
                                    "музыку) — без него звонок идёт через динамик.",
                            color = PismoColors.TextMuted,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = onBluetoothGranted) {
                            Text("Обновить список", color = PismoColors.Blurple)
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
}

/**
 * Гасит экран, когда телефон подносят к уху, — как обычная звонилка.
 *
 * Системный PROXIMITY_SCREEN_OFF_WAKE_LOCK, а не свой датчик: только он
 * гасит экран ВМЕСТЕ с сенсором, поэтому щекой ничего не нажимается.
 * Держим его, только пока выбран разговорный динамик и экран звонка на
 * виду: на громкой связи телефон лежит на столе, и гасить нечего.
 */
@Composable
private fun ProximityScreenOff(active: Boolean) {
    val context = LocalContext.current

    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val supported = pm != null &&
            pm.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)

        val lock = if (supported) {
            runCatching {
                pm!!.newWakeLock(
                    android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "pismo:proximity",
                ).apply { acquire(60 * 60 * 1000L) }
            }.getOrNull()
        } else null

        onDispose { runCatching { if (lock?.isHeld == true) lock.release() } }
    }
}
