package com.pismo.messenger.service

import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.FriendsRepository
import com.pismo.messenger.data.repo.ServerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Фоновая проверка новых сообщений, когда приложение свёрнуто — замена
 * трею ПК-версии с его балунами.
 *
 * Push-инфраструктуры (FCM) в проекте нет, а база опрашивается напрямую,
 * поэтому единственный способ узнать о новом сообщении в фоне — тот же
 * опрос, что и на ПК. Интервал здесь больше (10 с против 2.5 с), чтобы
 * не сажать батарею.
 */
class PollingService : LifecycleService() {

    private val previousUnread = HashMap<Int, Int>()
    private val previousGroupMax = HashMap<Int, Int>()
    private val previousChannelMax = HashMap<Int, Int>()
    private var groupBaselineReady = false
    private var channelBaselineReady = false
    private val knownFriendRequests = HashSet<Int>()
    private var friendBaselineReady = false

    /**
     * Отметки «о чём уже сообщали» переживают перезапуск процесса.
     *
     * Пока они жили только в памяти сервиса, первый проход после запуска
     * считал ЛЮБОЕ накопившееся уже известным — и сообщения, написанные
     * пока приложение было закрыто, не давали уведомлений вообще. Ровно тот
     * случай, ради которого уведомления и нужны.
     */
    private fun restoreBaselines() {
        parseMap(Prefs.notifyBaselineDm).let { if (it.isNotEmpty()) previousUnread.putAll(it) }
        parseMap(Prefs.notifyBaselineGroup).let {
            if (it.isNotEmpty()) {
                previousGroupMax.putAll(it)
                groupBaselineReady = true
            }
        }
        parseMap(Prefs.notifyBaselineChannel).let {
            if (it.isNotEmpty()) {
                previousChannelMax.putAll(it)
                channelBaselineReady = true
            }
        }
        val friends = Prefs.notifyBaselineFriends
            .split(',').mapNotNull { it.trim().toIntOrNull() }
        if (friends.isNotEmpty()) {
            knownFriendRequests.addAll(friends)
            friendBaselineReady = true
        }
    }

    private fun saveBaselines() {
        Prefs.notifyBaselineDm = formatMap(previousUnread)
        Prefs.notifyBaselineGroup = formatMap(previousGroupMax)
        Prefs.notifyBaselineChannel = formatMap(previousChannelMax)
        Prefs.notifyBaselineFriends = knownFriendRequests.joinToString(",")
    }

    private fun parseMap(raw: String): Map<Int, Int> =
        raw.split(',').mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size != 2) return@mapNotNull null
            val k = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
            val v = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
            k to v
        }.toMap()

    private fun formatMap(map: Map<Int, Int>): String =
        map.entries.joinToString(",") { "${it.key}:${it.value}" }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Переход в передний план может НЕ РАЗРЕШИТЬ система — и это не
        // исключительный случай, а обычная жизнь на свежих Android.
        //
        // Служба возвращает START_STICKY, то есть система сама поднимает её
        // после того, как прибила. Поднимает из фона, а из фона переводить
        // службу в передний план разрешено далеко не всегда; сверх того, на
        // Android 15 у типа dataSync есть суточный предел в шесть часов, и
        // после него запуск отвергается. Отказ прилетал исключением прямо в
        // onStartCommand — то есть падением всего приложения. Именно его вы и
        // прислали: «Unable to start service PollingService».
        //
        // Отказ — не повод падать: тихо уходим. Службу поднимет следующий
        // запуск приложения или приёмник загрузки.
        val ok = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(
                    Notifications.ID_SERVICE,
                    Notifications.serviceNotification(this),
                    foregroundType(),
                )
            } else {
                startForeground(Notifications.ID_SERVICE, Notifications.serviceNotification(this))
            }
        }.isSuccess
        if (!ok) {
            stopSelf()
            return START_NOT_STICKY
        }

        restoreBaselines()

        lifecycleScope.launch {
            while (isActive) {
                if (UserSession.effectiveId > 0 && Prefs.backgroundPolling) {
                    runCatching { pollOnce() }
                }
                delay(10_000)
            }
        }
        return START_STICKY
    }

    private suspend fun pollOnce() {
        // Игнорируемых спрашиваем один раз на проход: список местный, лежит
        // в настройках, и дёргать его по каждому отправителю незачем.
        val ignored = Prefs.ignoredUsers()

        // Личные сообщения.
        val unread = ChatRepository.unreadBySender()

        for ((senderId, count) in unread) {
            val before = previousUnread[senderId] ?: 0
            // Базовую отметку двигаем в любом случае, даже у заглушённых:
            // иначе снятый мьют вывалил бы разом всё накопленное за неделю.
            if (count > before && senderId !in ignored) {
                val name = runCatching {
                    com.pismo.messenger.data.repo.AuthRepository.loadUser(senderId)?.first
                }.getOrNull() ?: "Пользователь #$senderId"
                // Показываем, ЧТО прислали: текст, фото, кружок, документ,
                // архив. Раньше в шторке было безликое «Новых сообщений: N».
                val preview = ChatRepository.previewOfLatestFrom(senderId)
                val text = if (count > 1) "$preview  ·  ещё ${count - 1}" else preview
                Notifications.showMessage(this, senderId, name, text)
            }
        }
        previousUnread.clear()
        previousUnread.putAll(unread)

        // Групповые: у групп нет отметки прочтения на пользователя, поэтому
        // базовую точку держим в памяти — как _prevGroupMax на ПК.
        val groupMax = ChatRepository.groupMaxIncoming()
        if (!groupBaselineReady) {
            // Первый проход только запоминает состояние, иначе при каждом
            // запуске сервиса сыпались бы уведомления о давно прочитанном.
            // Раньше здесь стоял return, и он обрывал ВЕСЬ обход: каналы и
            // заявки в друзья на первом тике не опрашивались вовсе.
            groupMax.forEach { (gid, v) -> previousGroupMax[gid] = v.first }
            groupBaselineReady = true
        } else {
            for ((gid, value) in groupMax) {
                val (maxId, name) = value
                val before = previousGroupMax[gid] ?: 0
                if (maxId > before) {
                    val preview = ChatRepository.previewOfLatestInGroup(gid)
                    Notifications.showGroupMessage(this, gid, name, preview)
                }
                previousGroupMax[gid] = maxId
            }
        }

        pollChannels()
        pollFriendRequests()
        // Сохраняем в конце прохода, одной записью: промежуточные состояния
        // на диск класть незачем, а вот пережить убийство процесса отметки
        // обязаны.
        saveBaselines()
    }

    /**
     * Заявки в друзья. Раньше о них не сообщалось вообще: узнать о заявке
     * можно было, только зайдя на вкладку «Друзья» и увидев там цифру.
     */
    private suspend fun pollFriendRequests() {
        val incoming = runCatching { FriendsRepository.incomingRequests() }
            .getOrDefault(emptyList())

        val ids = incoming.map { it.userId }.toSet()
        if (!friendBaselineReady) {
            knownFriendRequests.addAll(ids)
            friendBaselineReady = true
            return
        }

        for (entry in incoming) {
            if (!knownFriendRequests.add(entry.userId)) continue
            Notifications.showFriendRequest(this, entry.userId, entry.name)
        }
        // Отозванные и принятые заявки забываем, иначе повторная заявка от
        // того же человека уже не покажется.
        knownFriendRequests.retainAll(ids)
    }

    /**
     * Каналы серверов. Раньше их здесь не было вовсе — сообщение в канале
     * не давало уведомления ни в фоне, ни свёрнутым, хотя красная цифра в
     * списке серверов появлялась.
     *
     * Считаем по тем же бейджам, что рисует список серверов: отдельный
     * запрос «что нового» дал бы расхождение между цифрой и уведомлением.
     */
    private suspend fun pollChannels() {
        // Триггер — простой запрос максимальных id, а НЕ бейджи. Бейджи
        // собирают тяжёлый SQL с проверками необязательных колонок, и если он
        // падает, уведомления о каналах пропадают целиком и молча — ровно то,
        // что и наблюдалось: из личных чатов уведомления шли, из каналов нет.
        val maxIds = ServerRepository.maxIncomingPerChannel()
        if (maxIds.isEmpty()) return

        // Первый проход только запоминает состояние: иначе при каждом
        // запуске сервиса сыпались бы уведомления о давно прочитанном.
        if (!channelBaselineReady) {
            previousChannelMax.putAll(maxIds)
            channelBaselineReady = true
            return
        }

        val fresh = maxIds.filter { (channelId, maxId) ->
            maxId > (previousChannelMax[channelId] ?: 0)
        }
        previousChannelMax.putAll(maxIds)
        if (fresh.isEmpty()) return

        val muted = ServerRepository.mutedChannelIds()
        val names = runCatching { ServerRepository.channelNames() }.getOrDefault(emptyMap())
        val badges = runCatching { ServerRepository.badges() }
            .getOrDefault(emptyList())

        for ((channelId, _) in fresh) {
            if (channelId in muted) continue

            val badge = badges.firstOrNull { it.channelId == channelId }
            // Упоминания: сначала бейдж (таблица server_mentions), иначе —
            // разбор расшифрованного текста. Отдельно считаем ответы на мои
            // сообщения: на ПК это тоже упоминание, но повод другой, и в
            // шторке разница видна.
            val mentions = badge?.mentions?.takeIf { it > 0 }
                ?: runCatching { ServerRepository.mentionsAmongNew(channelId, 0) }.getOrDefault(0)
            val replies = runCatching { ServerRepository.repliesToMeAmongNew(channelId) }
                .getOrDefault(0)

            val preview = runCatching { ServerRepository.previewOfLatestInChannel(channelId) }
                .getOrDefault("Новое сообщение")

            Notifications.showChannelMessage(
                this,
                channelId = channelId,
                channelName = names[channelId] ?: "Канал",
                mentions = mentions,
                replies = replies,
                preview = preview,
            )
        }
    }

    /**
     * Под каким типом проситься в передний план.
     *
     * НЕ dataSync, начиная с Android 14. У dataSync с Android 15 есть суточный
     * предел работы в шесть часов; когда он выбран, система вызывает onTimeout
     * и требует остановиться, а если приложение не успело — убивает его с
     * ForegroundServiceDidNotStopInTimeException. Ровно это и происходило
     * ночью: телефон присылал отчёт о падении, хотя человек ничего не делал.
     *
     * Подходящего типа для «мессенджер без своего push-сервера» в списке нет,
     * и specialUse заведён ровно для таких случаев. Предела у него нет.
     * На Android 13 и старше specialUse ещё не существует — там остаётся
     * dataSync, но там нет и предела.
     */
    private fun foregroundType(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

    /**
     * Система решила, что служба работает слишком долго.
     *
     * С типом specialUse этого быть не должно, но обработчик обязан быть
     * всё равно: если предел когда-нибудь применят и к нему, молчание здесь
     * снова кончится убийством приложения. Останавливаемся сами — тихо и
     * сразу, — а через полчаса пробуем подняться заново.
     */
    override fun onTimeout(startId: Int) {
        stopByTimeout()
    }

    private fun stopByTimeout() {
        runCatching { saveBaselines() }
        scheduleRetry(30)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    /** Будильник «попробовать снова»: сама по себе остановленная служба не вернётся. */
    private fun scheduleRetry(minutes: Long) {
        runCatching {
            val ctx = applicationContext
            val intent = Intent(ctx, BootReceiver::class.java)
                .setAction(BootReceiver.ACTION_RETRY_POLLING)
            val pi = android.app.PendingIntent.getBroadcast(
                ctx, 71, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            // Неточный будильник: точный требует отдельного разрешения, а
            // получасовая погрешность здесь ничего не решает.
            am.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + minutes * 60_000,
                pi,
            )
        }
    }

    override fun onDestroy() {
        // Отметки «о чём уже сообщали» обязаны пережить остановку: иначе после
        // возврата службы посыплется разом всё, что накопилось.
        runCatching { saveBaselines() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        fun start(context: Context) {
            if (!Prefs.backgroundPolling) return
            runCatching {
                val intent = Intent(context, PollingService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PollingService::class.java)) }
        }
    }
}
