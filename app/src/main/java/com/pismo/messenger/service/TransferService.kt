package com.pismo.messenger.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pismo.messenger.R
import com.pismo.messenger.data.Transfers
import com.pismo.messenger.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.HashSet

/**
 * Служба на время передачи файлов.
 *
 * ЗАЧЕМ. Отправка жила в области процесса — и пока приложение на экране,
 * этого хватало. Стоило свернуть его, и через минуту-другую система
 * замораживала процесс как ненужный: соединение рвалось, отправка
 * «отваливалась» на середине, а человек узнавал об этом, вернувшись в
 * переписку. Для Android «работа, которую нельзя прерывать» — это
 * foreground-служба, и другого способа сказать ему об этом нет.
 *
 * Тип dataSync — ровно наш случай: передача файла на сервер и обратно. У
 * него на Android 15 есть суточный предел в шесть часов, но передача живёт
 * минуты, а не часы, и упереться в него нечем (опрос сообщений поэтому
 * живёт под другим типом — см. манифест).
 *
 * Заодно отсюда берётся то, чего раньше не было вовсе: видимый процент.
 * Пока окно свёрнуто, уведомление — единственное место, где вообще можно
 * показать, идёт отправка или давно встала.
 *
 * Служба сама себя и заканчивает: как только идущих передач не осталось,
 * она снимает уведомление и уходит. Держать её «на всякий случай» нельзя —
 * это чужая батарея.
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wake: PowerManager.WakeLock? = null
    private var running = false

    /**
     * Чьи передачи мы видели живыми. Нужно для сообщения о срыве: строка с
     * ошибкой живёт в списке и после конца передачи, и без этой отметки о
     * старом срыве сообщалось бы заново при каждом следующем файле.
     */
    private val seenLive = HashSet<Long>()
    private val reportedCancellationIds = HashSet<Long>()
    private val reportedFailureIds = HashSet<Long>()
    private var lastShownMs = 0L
    private var watcher: kotlinx.coroutines.Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true

        // Подняться надо СРАЗУ: на это система даёт секунды, а не минуты.
        // Отказ (например, запуск из фона на Android 12+) — не повод падать:
        // уходим тихо, передача продолжится как раньше.
        val ok = runCatching {
            val n = buildNotification(Transfers.active.value)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    Notifications.ID_TRANSFER, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(Notifications.ID_TRANSFER, n)
            }
        }.isSuccess
        if (!ok) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWake()
        watch()
        // Перезапускать нечего: байты держит та самая задача, которая умерла
        // бы вместе с процессом. START_STICKY поднял бы пустую службу.
        return START_NOT_STICKY
    }

    /**
     * Удержание процессора на время передачи.
     *
     * Foreground-служба спасает от того, что процесс сочтут ненужным, но не
     * от сна: при погашенном экране телефон засыпает между пробуждениями, и
     * отправка растягивается или встаёт. Держим, пока есть что передавать,
     * и отпускаем сразу после — с запасом по времени на случай, если
     * отпустить забудут.
     */
    private fun acquireWake() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pismo:transfer").apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L)
            }
        }
    }

    /** Удержание выдано на полчаса — у долгой передачи его надо продлевать. */
    private fun keepAwake() {
        val lock = wake ?: return
        if (!lock.isHeld) runCatching { lock.acquire(30 * 60 * 1000L) }
    }

    private fun watch() {
        watcher?.cancel()
        watcher = scope.launch {
            Transfers.active.collect { list ->
                val live = list.filter { it.error == null }
                live.forEach { seenLive.add(it.id) }
                reportFailures(list)

                if (live.isEmpty()) {
                    finish()
                    return@collect
                }

                // Доля меняется десятки раз в секунду, а уведомление столько
                // раз перерисовывать и незачем, и вредно: система начинает
                // придерживать слишком частые обновления.
                val now = System.currentTimeMillis()
                keepAwake()
                val n = buildNotification(live)
                if (now - lastShownMs >= 700L && Notifications.allowed(this@TransferService)) {
                    lastShownMs = now
                    runCatching {
                        NotificationManagerCompat.from(this@TransferService)
                            .notify(Notifications.ID_TRANSFER, n)
                    }
                }
            }
        }
    }

    /** Сорвавшееся — отдельным сообщением: о нём надо знать, а полосы уже нет. */
    private fun reportFailures(list: List<Transfers.Task>) {
        val failed = list.filter { it.error != null && !it.cancelled && it.id in seenLive && reportedFailureIds.add(it.id) }
        val cancelled = list.filter { it.cancelled && reportedCancellationIds.add(it.id) }
        if (cancelled.isNotEmpty() && Notifications.allowed(this)) {
            val text = cancelled.joinToString(", ") { it.fileName }
            runCatching {
                NotificationManagerCompat.from(this).notify(
                    Notifications.ID_TRANSFER_DONE,
                    NotificationCompat.Builder(this, Notifications.CHANNEL_TRANSFERS)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("Передача отменена")
                        .setContentText(text)
                        .setAutoCancel(true)
                        .setTimeoutAfter(10_000L)
                        .setContentIntent(openApp())
                        .build()
                )
            }
        }
        if (failed.isEmpty() || !Notifications.allowed(this)) return
        val title = if (failed.size == 1) {
            (if (failed[0].download) "Не удалось загрузить" else "Не удалось отправить")
        } else "Передачи не удались: ${failed.size}"
        val text = if (failed.size == 1) "${failed[0].fileName} — ${failed[0].error}"
        else failed.joinToString(", ") { it.fileName }

        runCatching {
            NotificationManagerCompat.from(this).notify(
                Notifications.ID_TRANSFER_DONE,
                NotificationCompat.Builder(this, Notifications.CHANNEL_TRANSFERS)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setContentIntent(openApp())
                    .build()
            )
        }
    }

    private fun finish() {
        // Сперва снимаемся с учёта и только потом просим себя остановить.
        //
        // Остановка не мгновенна, и в эту щель может прийти новая передача:
        // система тогда отдаёт запуск ЭТОМУ же экземпляру, а он уже не
        // foreground. Служба осталась бы работать без уведомления — то есть
        // ровно в том положении, из-за которого всё и затевалось. Сброшенный
        // признак заставляет её подняться заново, как при первом запуске.
        watcher?.cancel()
        watcher = null
        running = false
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun buildNotification(tasks: List<Transfers.Task>): Notification {
        val live = tasks.filter { it.error == null }
        val one = live.singleOrNull()

        val percent = live.map { it.progress }.takeIf { it.isNotEmpty() }
            ?.let { (it.sum() / it.size * 100).toInt().coerceIn(0, 100) } ?: 0
        val waiting = live.count { it.waiting }

        val title = when {
            one != null -> one.fileName
            live.isEmpty() -> "PISMO"
            else -> "Передачи файлов: ${live.size}"
        }
        val text = when {
            one != null && one.waiting -> "В очереди"
            one != null -> (if (one.download) "Загрузка" else "Отправка") +
                " · $percent %" + sizeTail(one)
            live.isEmpty() -> "Завершение"
            else -> "Идут $percent %" + (if (waiting > 0) " · в очереди: $waiting" else "")
        }

        val cancel = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, TransferActionReceiver::class.java)
                .setAction(TransferActionReceiver.ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Значок по направлению: стрелка вниз у загрузки, вверх у отправки.
        // В строке состояния видно только его, и он один отвечает на вопрос
        // «это моё уходит или ко мне идёт».
        val icon = if (live.isNotEmpty() && live.all { it.download })
            android.R.drawable.stat_sys_download
        else android.R.drawable.stat_sys_upload

        return NotificationCompat.Builder(this, Notifications.CHANNEL_TRANSFERS)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            // Полоса — та же, что в переписке. Пока все задачи ждут очереди,
            // показывать точный ноль нечестно: там бежит отрезок.
            .setProgress(100, percent, live.isNotEmpty() && live.all { it.waiting })
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, if (live.size > 1) "Отменить всё" else "Отменить", cancel)
            .setContentIntent(openApp())
            .build()
    }

    /** «12 МБ из 29 МБ» — только там, где размер известен заранее. */
    private fun sizeTail(task: Transfers.Task): String {
        if (task.totalBytes <= 0) return ""
        val done = (task.totalBytes * task.progress).toLong()
        return " · ${human(done)} из ${human(task.totalBytes)}"
    }

    private fun human(bytes: Long): String {
        if (bytes <= 0) return "0 Б"
        val units = arrayOf("Б", "КБ", "МБ", "ГБ")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
        return (if (i == 0) value.toInt().toString()
        else String.format(java.util.Locale.getDefault(), "%.1f", value)) + " " + units[i]
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        scope.cancel()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /**
         * Поднять службу. Зовётся из начала передачи, то есть с открытого
         * экрана, — именно поэтому Android и разрешает запуск: из фона
         * foreground-службу так просто не поднять.
         */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, TransferService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
