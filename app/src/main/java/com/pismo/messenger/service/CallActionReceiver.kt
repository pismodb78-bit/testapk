package com.pismo.messenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pismo.messenger.call.IncomingCallMonitor

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

        // Только отклонение. «Принять» ведёт напрямую в активити своим
        // PendingIntent: запускать её отсюда нельзя — с Android 10 старт
        // активити из BroadcastReceiver считается фоновым и блокируется.
        if (intent.action == CallNotifier.ACTION_REJECT) {
            IncomingCallMonitor.rejected(context, call)
        }
    }
}
