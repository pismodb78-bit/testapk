package com.pismo.messenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pismo.messenger.call.IncomingCallMonitor
import com.pismo.messenger.ui.call.CallActivity

/**
 * Кнопки «Принять» / «Отклонить» прямо в шторке — чтобы не нужно было
 * сначала разблокировать телефон и найти приложение.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getIntExtra(CallNotifier.EXTRA_CALL_ID, -1)
        val call = IncomingCallMonitor.incoming.value
        if (callId <= 0 || call == null || call.id != callId) {
            CallNotifier.cancelIncoming(context)
            return
        }

        when (intent.action) {
            CallNotifier.ACTION_REJECT -> IncomingCallMonitor.rejected(context, call)

            CallNotifier.ACTION_ACCEPT -> {
                IncomingCallMonitor.accepted(context, call)
                context.startActivity(
                    Intent(context, CallActivity::class.java).apply {
                        putExtra(CallActivity.EXTRA_SESSION_ID, call.id)
                        putExtra(CallActivity.EXTRA_PEER_ID, call.callerId)
                        putExtra(CallActivity.EXTRA_GROUP_ID, call.groupId ?: -1)
                        putExtra(CallActivity.EXTRA_PEER_NAME, call.callerName)
                        putExtra(CallActivity.EXTRA_WITH_VIDEO, call.hasVideo)
                        putExtra(CallActivity.EXTRA_IS_CALLER, false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            }
        }
    }
}
