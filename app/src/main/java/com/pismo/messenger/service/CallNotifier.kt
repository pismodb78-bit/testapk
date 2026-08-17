package com.pismo.messenger.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pismo.messenger.R
import com.pismo.messenger.data.model.CallSessionRow
import com.pismo.messenger.ui.call.CallActivity

/**
 * Уведомление о входящем звонке.
 *
 * На ПК окно входящего просто всплывает поверх всего — там процесс всегда
 * на экране. На телефоне приложение может быть свёрнуто или экран погашен,
 * поэтому нужен full-screen intent: система сама поднимет CallActivity
 * поверх блокировки, а если это запрещено настройками — покажет
 * «плавающее» уведомление с кнопками принять/отклонить.
 */
object CallNotifier {

    const val ACTION_ACCEPT = "com.pismo.messenger.CALL_ACCEPT"
    const val ACTION_REJECT = "com.pismo.messenger.CALL_REJECT"
    const val EXTRA_CALL_ID = "call_id"

    private const val ID_INCOMING = 1010

    fun showIncoming(context: Context, call: CallSessionRow) {
        val full = Intent(context, CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_SESSION_ID, call.id)
            putExtra(CallActivity.EXTRA_PEER_ID, call.callerId)
            putExtra(CallActivity.EXTRA_GROUP_ID, call.groupId ?: -1)
            putExtra(CallActivity.EXTRA_PEER_NAME, call.callerName)
            putExtra(CallActivity.EXTRA_WITH_VIDEO, call.hasVideo)
            putExtra(CallActivity.EXTRA_IS_CALLER, false)
            putExtra(CallActivity.EXTRA_RINGING, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullPending = PendingIntent.getActivity(
            context, call.id, full,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_CALLS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(call.callerName)
            .setContentText(if (call.hasVideo) "Входящий видеозвонок" else "Входящий звонок")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullPending)
            .setFullScreenIntent(fullPending, true)
            .addAction(
                R.mipmap.ic_launcher, "Отклонить",
                broadcast(context, ACTION_REJECT, call.id)
            )
            // «Принять» — ИМЕННО activity-PendingIntent, а не broadcast.
            // Начиная с Android 10 запуск активити из BroadcastReceiver
            // запрещён как фоновый: приёмник срабатывал, звонок помечался
            // принятым, а экран звонка не открывался — и подключения не
            // происходило. Работало только когда приложение уже на экране.
            // У уведомления такое право есть, поэтому ведём прямо в активити.
            .addAction(R.mipmap.ic_launcher, "Принять", acceptPending(context, call))

        runCatching {
            NotificationManagerCompat.from(context).notify(ID_INCOMING, builder.build())
        }
        startRinging(context)
    }

    fun cancelIncoming(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_INCOMING) }
        stopRinging()
    }

    /** Открывает экран звонка сразу принятым — минуя экран «вам звонят». */
    private fun acceptPending(context: Context, call: CallSessionRow): PendingIntent {
        val intent = Intent(context, CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_SESSION_ID, call.id)
            putExtra(CallActivity.EXTRA_PEER_ID, call.callerId)
            putExtra(CallActivity.EXTRA_GROUP_ID, call.groupId ?: -1)
            putExtra(CallActivity.EXTRA_PEER_NAME, call.callerName)
            putExtra(CallActivity.EXTRA_WITH_VIDEO, call.hasVideo)
            putExtra(CallActivity.EXTRA_IS_CALLER, false)
            putExtra(CallActivity.EXTRA_ACCEPT_NOW, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context, call.id * 2 + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun broadcast(context: Context, action: String, callId: Int): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_CALL_ID, callId)
        return PendingIntent.getBroadcast(
            context, callId * 2 + action.hashCode().and(1), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ── Рингтон и вибрация ────────────────────────────────────────────
    //
    // Звук уведомления канала играть нельзя: он одноразовый, а звонок
    // должен звонить, пока его не возьмут. Поэтому крутим системный
    // рингтон в цикле сами — это же делает Sounds.cs на ПК.

    private var ringtone: android.media.Ringtone? = null
    private var vibrator: Vibrator? = null

    private fun startRinging(context: Context) {
        if (ringtone?.isPlaying == true) return
        runCatching {
            val uri: Uri = RingtoneManager.getActualDefaultRingtoneUri(
                context, RingtoneManager.TYPE_RINGTONE
            ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }

        runCatching {
            vibrator = context.getSystemService(Vibrator::class.java)
            val pattern = longArrayOf(0, 700, 900)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    /** Уведомление для foreground-сервиса идущего звонка. */
    fun ongoing(context: Context, title: String): Notification =
        NotificationCompat.Builder(context, Notifications.CHANNEL_CALLS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title.ifBlank { "Звонок PISMO" })
            .setContentText("Идёт разговор")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
}
