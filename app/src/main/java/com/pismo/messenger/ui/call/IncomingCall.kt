package com.pismo.messenger.ui.call

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.data.model.CallSessionRow
import com.pismo.messenger.data.repo.CallRepository
import com.pismo.messenger.net.SignalingClient
import com.pismo.messenger.ui.components.LetterAvatar
import com.pismo.messenger.ui.components.UserAvatar
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Слежение за входящими звонками — порт CheckIncomingCalls из
 * MainForm_MessageActions.cs.
 *
 * Опрос идёт раз в 1.5 секунды, как на ПК, и дополнительно
 * подхватывается событие incoming_call с ws-сервера. Показывается
 * только один звонок за раз.
 */
@Composable
fun IncomingCallWatcher() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var incoming by remember { mutableStateOf<CallSessionRow?>(null) }
    var lastCheckedId by remember { mutableStateOf(0) }

    suspend fun check() {
        if (incoming != null) return
        runCatching {
            val calls = CallRepository.incomingCalls(lastCheckedId)
            val first = calls.firstOrNull() ?: return@runCatching
            lastCheckedId = calls.maxOf { it.id }
            incoming = first
        }
    }

    LaunchedEffect(Unit) {
        // Стартовую отметку берём по текущему максимуму, чтобы старые
        // «звонящие» сессии не всплывали сразу после входа.
        runCatching {
            lastCheckedId = CallRepository.incomingCalls(0).maxOfOrNull { it.id } ?: 0
        }
        while (isActive) {
            delay(1500)
            check()
        }
    }

    DisposableEffect(Unit) {
        val listener: (String, Int, Int, String) -> Unit = { type, _, _, _ ->
            if (type == "incoming_call") scope.launch { check() }
        }
        SignalingClient.addListener(listener)
        onDispose { SignalingClient.removeListener(listener) }
    }

    val call = incoming ?: return

    AlertDialog(
        onDismissRequest = { },
        containerColor = PismoColors.BgSidebar,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()) {
                UserAvatar(call.callerId, call.callerName, 72.dp)
                Spacer(Modifier.height(12.dp))
                Text(call.callerName, color = Color.White, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold)
                Text(
                    if (call.hasVideo) "Входящий видеозвонок" else "Входящий звонок",
                    color = PismoColors.TextMuted, fontSize = 13.sp,
                )
            }
        },
        text = { },
        confirmButton = {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CircleAction(PismoColors.Red, Icons.Default.CallEnd, "Отклонить") {
                    scope.launch {
                        runCatching { CallRepository.reject(call.id) }
                        SignalingClient.send("call_status", call.callerId, call.id, "rejected")
                        incoming = null
                    }
                }
                CircleAction(PismoColors.Green, Icons.Default.Call, "Принять") {
                    scope.launch {
                        runCatching { CallRepository.accept(call.id) }
                        SignalingClient.send("call_status", call.callerId, call.id, "active")
                        openCall(context, call)
                        incoming = null
                    }
                }
            }
        },
    )
}

@Composable
private fun CircleAction(
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, label, tint = Color.White)
        }
    }
}

private fun openCall(context: Context, call: CallSessionRow) {
    val intent = Intent(context, CallActivity::class.java).apply {
        putExtra(CallActivity.EXTRA_SESSION_ID, call.id)
        putExtra(CallActivity.EXTRA_PEER_ID, call.callerId)
        putExtra(CallActivity.EXTRA_GROUP_ID, call.groupId ?: -1)
        putExtra(CallActivity.EXTRA_PEER_NAME, call.callerName)
        putExtra(CallActivity.EXTRA_WITH_VIDEO, call.hasVideo)
        putExtra(CallActivity.EXTRA_IS_CALLER, false)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
