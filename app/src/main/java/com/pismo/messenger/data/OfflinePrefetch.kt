package com.pismo.messenger.data

import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Готовит переписки к работе без связи заранее — пока связь есть.
 *
 * Кеш переписки наполнялся только тем, что человек открыл сам: зашёл в чат —
 * страница осела на диске. В метро это и выходило боком: один-два чата, куда
 * успел заглянуть, открываются, а остальные встречают пустотой, хотя
 * переписка в них есть и занимает считаные килобайты.
 *
 * Поэтому последние сообщения всех чатов из списка подтягиваются фоном:
 * запросы лёгкие (только текст и метаданные), идут по одному с паузой и
 * начинаются не раньше, чем через полминуты после открытия списка — чтобы не
 * мешать тому, чем человек занят прямо сейчас, и не драться с ним за узкий
 * мобильный канал.
 *
 * Вложения сюда не входят намеренно: тянуть все фото и видео всех переписок
 * «на всякий случай» — это гигабайты трафика и места на телефоне. Что
 * открывали, то и останется доступным: у вложений свой кеш.
 */
object OfflinePrefetch {

    /** Сколько сообщений держим наготове на каждый чат. */
    private const val DEPTH = 40

    /** Пауза между чатами: это фоновая работа, а не гонка. */
    private const val GAP_MS = 500L

    /** Сколько ждать после открытия списка, прежде чем начать. */
    private const val DELAY_MS = 30_000L

    /** Как часто повторять обход. Чаще незачем: переписки не исчезают. */
    private const val PERIOD_MS = 15 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    @Volatile private var lastRun = 0L

    /**
     * Поставить обход в очередь. Зовётся после удачной загрузки списка чатов;
     * лишние вызовы отсекаются сами.
     */
    fun schedule(partnerIds: List<Int>, groupIds: List<Int>, serverIds: List<Int> = emptyList()) {
        if (UserSession.effectiveId <= 0) return
        if (partnerIds.isEmpty() && groupIds.isEmpty() && serverIds.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastRun < PERIOD_MS) return
        if (!running.compareAndSet(false, true)) return
        lastRun = now

        val dms = partnerIds.toList()
        val grps = groupIds.toList()
        val srvs = serverIds.toList()

        scope.launch {
            try {
                delay(DELAY_MS)
                for (id in dms) {
                    if (!Db.online.value) break          // связь ушла — не мешаем
                    grab(Scope.DM, id) { ChatRepository.loadDirectMessages(id, DEPTH) }
                    delay(GAP_MS)
                }
                for (id in grps) {
                    if (!Db.online.value) break
                    grab(Scope.GROUP, id) { ChatRepository.loadGroupMessages(id, DEPTH) }
                    delay(GAP_MS)
                }
                for (sid in srvs) {
                    if (!Db.online.value) break
                    // Каналы сервера: их список и ленты. Без списка сервер без
                    // связи выглядит пустым, даже когда сообщения уже лежат.
                    val channels = runCatching { ServerRepository.channels(sid) }.getOrNull()
                        ?: continue
                    for (ch in channels) {
                        if (!Db.online.value) break
                        if (ch.type != com.pismo.messenger.data.model.ChannelType.TEXT) continue
                        grab(Scope.SERVER, ch.id) {
                            ServerRepository.channelMessages(ch.id, DEPTH)
                        }
                        delay(GAP_MS)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                running.set(false)
            }
        }
    }

    /** Одна переписка: забрать страницу и положить в кеш (память + диск). */
    private suspend fun grab(
        scopeKind: Scope,
        targetId: Int,
        fetch: suspend () -> List<com.pismo.messenger.data.model.ChatMessage>,
    ) {
        runCatching {
            val messages = fetch()
            if (messages.isNotEmpty()) {
                MessageMemory.put(scopeKind, targetId, messages, emptyMap())
            }
        }
    }
}
