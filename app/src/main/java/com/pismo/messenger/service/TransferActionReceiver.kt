package com.pismo.messenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pismo.messenger.data.Transfers

/**
 * Кнопка «Отменить» на уведомлении о передаче файлов.
 *
 * Отменять приходится там же, где об этом вспомнил: в шторке, не открывая
 * приложение. Отмена доводится до конца обычным путём — рвёт соединение и
 * убирает наполовину написанную строку сообщения (см. Transfers.cancel).
 */
class TransferActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_ALL) return
        runCatching { Transfers.cancelAll() }
    }

    companion object {
        const val ACTION_CANCEL_ALL = "com.pismo.messenger.TRANSFER_CANCEL"
    }
}
