package com.pismo.messenger.ui.call

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.call.IncomingCallMonitor
import com.pismo.messenger.data.model.CallSessionRow
import com.pismo.messenger.ui.components.UserAvatar
import com.pismo.messenger.ui.theme.PismoColors

/**
 * Окно входящего звонка.
 *
 * Сам опрос живёт в IncomingCallMonitor — синглтоне уровня приложения.
 * Здесь только отрисовка, поэтому окно одинаково всплывает над любым
 * экраном: списком чатов, перепиской, каналом сервера.
 *
 * Вешать этот composable нужно ОДИН раз, в корне MainActivity, а не на
 * каждом экране — иначе на переходах будет по два окна на один звонок.
 */
@Composable
fun IncomingCallDialog() {
    val context = LocalContext.current
    val call by IncomingCallMonitor.incoming.collectAsState()
    val current = call ?: return

    AlertDialog(
        onDismissRequest = { },
        containerColor = PismoColors.BgSidebar,
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                UserAvatar(current.callerId, current.callerName, 72.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    current.callerName, color = PismoColors.TextPrimary, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (current.hasVideo) "Входящий видеозвонок" else "Входящий звонок",
                    color = PismoColors.TextMuted, fontSize = 13.sp,
                )
            }
        },
        text = { },
        confirmButton = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CircleAction(PismoColors.Red, Icons.Default.CallEnd, "Отклонить") {
                    IncomingCallMonitor.rejected(context, current)
                }
                CircleAction(PismoColors.Green, Icons.Default.Call, "Принять") {
                    IncomingCallMonitor.accepted(context, current)
                    openCall(context, current)
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
    Box(
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
