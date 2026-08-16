package com.pismo.messenger.ui.call

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.pismo.messenger.call.CallEngine
import com.pismo.messenger.call.LiveKitToken
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.repo.CallRepository
import com.pismo.messenger.data.repo.PresenceRepository
import com.pismo.messenger.net.SignalingClient
import com.pismo.messenger.ui.components.LetterAvatar
import com.pismo.messenger.ui.components.UserAvatar
import com.pismo.messenger.ui.theme.PismoColors
import com.pismo.messenger.ui.theme.PismoTheme
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    }

    private lateinit var engine: CallEngine
    private var sessionId = -1
    private var channelId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = CallEngine(applicationContext, lifecycleScope)

        val peerId = intent.getIntExtra(EXTRA_PEER_ID, -1)
        val groupId = intent.getIntExtra(EXTRA_GROUP_ID, -1)
        val peerName = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        val withVideo = intent.getBooleanExtra(EXTRA_WITH_VIDEO, false)
        val isCaller = intent.getBooleanExtra(EXTRA_IS_CALLER, false)
        sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        channelId = intent.getIntExtra(EXTRA_CHANNEL_ID, -1)

        setContent {
            PismoTheme {
                CallScreen(
                    engine = engine,
                    peerName = peerName,
                    onHangup = { finishCall() },
                )
            }
        }

        lifecycleScope.launch {
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
            engine.join(room, withVideo)
        }

        // Присутствие в голосовом канале — heartbeat, как на ПК.
        lifecycleScope.launch {
            while (isActive) {
                if (channelId > 0) {
                    PresenceRepository.voiceHeartbeat(
                        channelId,
                        streaming = engine.screenSharing.value || engine.cameraOn.value,
                        micMuted = engine.micMuted.value,
                        deafened = engine.deafened.value,
                    )
                }
                delay(10_000)
            }
        }
    }

    private fun finishCall() {
        lifecycleScope.launch {
            runCatching {
                if (channelId > 0) PresenceRepository.voiceLeave(channelId)
                if (sessionId > 0) CallRepository.leave(sessionId)
            }
            engine.leave()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.leave()
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
    val cameraOn by engine.cameraOn.collectAsState()
    val sharing by engine.screenSharing.collectAsState()
    val state by engine.state.collectAsState()
    val error by engine.error.collectAsState()

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            scope.launch { engine.startScreenShare(result.data!!) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(PismoColors.BgDarkest)
            .padding(12.dp)
    ) {
        Text(
            peerName.ifBlank { "Звонок" },
            color = Color.White,
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

        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(if (participants.size <= 2) 1 else 2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(participants, key = { it.identity }) { p ->
                ParticipantTile(engine, p)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CallButton(
                icon = if (micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                active = !micMuted,
                label = "Микрофон",
            ) { scope.launch { engine.toggleMic() } }

            CallButton(
                icon = if (deafened) Icons.Default.HeadsetOff else Icons.Default.Headset,
                active = !deafened,
                label = "Звук",
            ) { scope.launch { engine.toggleDeafen() } }

            CallButton(
                icon = if (cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                active = cameraOn,
                label = "Камера",
            ) { scope.launch { engine.toggleCamera() } }

            CallButton(
                icon = if (sharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                active = sharing,
                label = "Экран",
            ) {
                if (sharing) {
                    scope.launch { engine.stopScreenShare() }
                } else {
                    val mgr = context.getSystemService(MediaProjectionManager::class.java)
                    mgr?.createScreenCaptureIntent()?.let { screenCaptureLauncher.launch(it) }
                }
            }

            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(PismoColors.Red),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onHangup) {
                    Icon(Icons.Default.CallEnd, "Завершить", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ParticipantTile(engine: CallEngine, p: CallEngine.ParticipantState) {
    val track = p.screenTrack ?: p.cameraTrack

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (track != null) 16f / 9f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(PismoColors.BgSidebar),
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

@Composable
private fun CallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (active) PismoColors.BgHover else PismoColors.BgElevated),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, label, tint = if (active) Color.White else PismoColors.TextMuted)
        }
    }
}
