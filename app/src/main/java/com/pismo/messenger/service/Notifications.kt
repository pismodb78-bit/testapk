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
    /**
     * Канал фоновой работы. Идентификатор со суффиксом _quiet — новый:
     * важность у уже созданного канала программно не меняется (её решает
     * пользователь), поэтому «сделать тише» можно только заведя другой канал,
     * а старый удалив. См. createChannels.
     */
    const val CHANNEL_SERVICE = "pismo_service_quiet"

    /** Прежний канал фоновой работы — удаляем, чтобы не висел в настройках. */
    private const val CHANNEL_SERVICE_OLD = "pismo_service"

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
        // Фоновая проверка сообщений. IMPORTANCE_MIN, а не LOW: при LOW
        // Android держит значок в строке состояния, и «Проверка новых
        // сообщений» висела там постоянно. При MIN значка нет, звука нет, а
        // сама запись уезжает в самый низ шторки, в свёрнутые.
        //
        // Совсем убрать её нельзя: пока работает фоновый сервис, система
        // ОБЯЗАНА показывать уведомление — это её способ сообщить, что
        // приложение работает за спиной. Выключить целиком можно только
        // вместе с самой фоновой проверкой, переключателем в настройках.
        runCatching { nm.deleteNotificationChannel(CHANNEL_SERVICE_OLD) }
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE, "Фоновая работа", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Проверка новых сообщений, когда приложение свёрнуто"
                setShowBadge(false)
            }
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
        replies: Int = 0,
        preview: String = "Новое сообщение в канале",
    ) {
        if (!Prefs.notificationsEnabled) return
        if (!hasPermission(context)) return

        val extras = Bundle().apply { putInt("open_channel", channelId) }
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            // Разделяем поводы: упоминание, ответ на моё сообщение и просто
            // новое сообщение — это три разные причины отвлечь человека.
            .setContentTitle(
                when {
                    mentions > 0 -> "@ # $channelName"
                    replies > 0 -> "↩ # $channelName"
                    else -> "# $channelName"
                }
            )
            .setContentText(
                when {
                    mentions > 0 -> "Вас упомянули · $preview"
                    replies > 0 -> "Ответили на ваше сообщение · $preview"
                    else -> preview
                }
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(
                if (mentions > 0 || replies > 0) NotificationCompat.PRIORITY_HIGH
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

    /** Входящая заявка в друзья — на ПК о ней сообщает значок в списке. */
    fun showFriendRequest(context: Context, fromId: Int, fromName: String) {
        if (!Prefs.notificationsEnabled) return
        if (!hasPermission(context)) return

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Заявка в друзья")
            .setContentText("$fromName хочет добавить вас в друзья")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, Bundle().apply { putBoolean("open_friends", true) }))
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(ID_MESSAGE_BASE + 300_000 + fromId, notification)
        }
    }

    fun cancelMessage(context: Context, senderId: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_MESSAGE_BASE + senderId) }
    }

    /**
     * Уведомление фонового сервиса — то, что должно мозолить глаза как можно
     * меньше.
     *
     * Строчка «Проверка новых сообщений» убрана: она ничего не сообщала, а
     * висела всегда. Заголовок оставлен один — совсем без текста уведомление
     * показывать нельзя, да и понимать, чьё оно, всё-таки нужно.
     *
     * FOREGROUND_SERVICE_DEFERRED (Android 12 и новее) откладывает показ на
     * десять секунд: короткий заход в фон успевает закончиться раньше, чем
     * уведомление появится, и человек его не увидит вовсе.
     */
    fun serviceNotification(context: Context): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PISMO")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
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
