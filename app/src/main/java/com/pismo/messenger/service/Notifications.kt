package com.pismo.messenger.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pismo.messenger.R
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.ui.MainActivity

/**
 * Уведомления — Android-замена трея и FlashWindow из ПК-версии.
 */
object Notifications {

    const val CHANNEL_MESSAGES = "pismo_messages"
    const val CHANNEL_CALLS = "pismo_calls"
    const val CHANNEL_SERVICE = "pismo_service"

    const val ID_SERVICE = 1001
    const val ID_CALL = 1002
    const val ID_SCREEN = 1003
    private const val ID_MESSAGE_BASE = 2000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Новые личные и групповые сообщения" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLS, "Звонки", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Входящие звонки"
                setBypassDnd(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE, "Фоновая работа", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Проверка новых сообщений, когда приложение свёрнуто" }
        )
    }

    private fun openAppIntent(context: Context, extras: Bundle? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            extras?.let { putExtras(it) }
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun showMessage(context: Context, senderId: Int, senderName: String, preview: String) {
        if (!Prefs.notificationsEnabled) return
        if (!hasPermission(context)) return

        val extras = Bundle().apply {
            putInt("open_chat_with", senderId)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(senderName)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, extras))
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(ID_MESSAGE_BASE + senderId, notification)
        }
    }

    fun showGroupMessage(context: Context, groupId: Int, groupName: String, preview: String) {
        if (!Prefs.notificationsEnabled) return
        if (!hasPermission(context)) return

        val extras = Bundle().apply { putInt("open_group", groupId) }
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("👥 $groupName")
            .setContentText(preview)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, extras))
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(ID_MESSAGE_BASE + 100_000 + groupId, notification)
        }
    }

    /** Сообщение в канале сервера. Упоминание — отдельным текстом, как на ПК. */
    fun showChannelMessage(
        context: Context,
        channelId: Int,
        channelName: String,
        mentions: Int,
        preview: String = "Новое сообщение в канале",
    ) {
        if (!Prefs.notificationsEnabled) return
        if (!hasPermission(context)) return

        val extras = Bundle().apply { putInt("open_channel", channelId) }
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (mentions > 0) "@ # $channelName" else "# $channelName")
            .setContentText(if (mentions > 0) "@ Вас упомянули · $preview" else preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(
                if (mentions > 0) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, extras))
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(ID_MESSAGE_BASE + 200_000 + channelId, notification)
        }
    }

    fun cancelMessage(context: Context, senderId: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_MESSAGE_BASE + senderId) }
    }

    fun serviceNotification(context: Context): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PISMO")
            .setContentText("Проверка новых сообщений")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openAppIntent(context))
            .build()

    /**
     * Уведомление для foreground-сервиса демонстрации экрана.
     *
     * LiveKit умеет собрать своё, но у него нет иконки, а startForeground
     * без setSmallIcon падает исключением прямо в момент старта демки.
     */
    fun screenShareNotification(context: Context): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PISMO")
            .setContentText("Идёт демонстрация экрана")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openAppIntent(context))
            .build()

    private fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
