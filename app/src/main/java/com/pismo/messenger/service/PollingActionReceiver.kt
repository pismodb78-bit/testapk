package com.pismo.messenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pismo.messenger.core.Prefs

/**
 * Кнопка «Отключить» на уведомлении фоновой проверки.
 *
 * Зачем прямо в уведомлении. Само уведомление убрать нельзя: пока работает
 * фоновая служба, Android ОБЯЗАН его показывать — это его способ сообщить,
 * что программа работает за спиной, и скрыть его приложению не дано. Значит
 * единственный честный ответ раздражённому человеку — дать выключить саму
 * фоновую проверку, и дать это одним нажатием там же, где раздражает, а не
 * отправлять искать переключатель в настройках.
 */
class PollingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISABLE) return
        runCatching {
            Prefs.init(context.applicationContext)
            Prefs.backgroundPolling = false
            PollingService.stop(context.applicationContext)
        }
    }

    companion object {
        const val ACTION_DISABLE = "com.pismo.messenger.POLLING_DISABLE"
    }
}
