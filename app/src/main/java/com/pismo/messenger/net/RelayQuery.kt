package com.pismo.messenger.net

import com.pismo.messenger.core.NetInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Чтение переписки ЧЕРЕЗ РЕЛЕЙ — по тому же сокету, что и события.
 *
 * ПОЧЕМУ ЭТО БЫСТРЕЕ. Телефон ходит в MySQL напрямую, и дорого здесь не
 * количество байт, а количество КРУГОВ до сервера. Протокол MySQL
 * разговорчив: открыть соединение — несколько обменов с рукопожатием и
 * аутентификацией, потом запрос и ответ. На мобильной связи круг — сотни
 * миллисекунд, и один заход в чат складывается в секунды, сколько канал ни
 * расширяй.
 *
 * Релей же стоит рядом с базой: круг до неё — доли миллисекунды. Поэтому
 * спрашиваем не базу, а релей, по уже открытому сокету: один обмен вместо
 * десятка. Так устроены все мессенджеры — у клиента одно соединение со своим
 * сервером, а в базу ходит сервер.
 *
 * ЭТО НЕ ЗАМЕНА, А КОРОТКАЯ ДОРОГА. Сокета нет, релей старый, запрос не
 * поспел — возвращаем null, и вызывающий спокойно идёт в базу сам, как
 * раньше. Поэтому приложение работает и со старым сервером.
 */
object RelayQuery {

    /** Сколько ждём ответа. Дольше — проще спросить базу самим. */
    private const val TIMEOUT_MS = 6_000L

    /** Мобильная связь медленнее: даём ей больше времени, прежде чем сдаться. */
    private const val TIMEOUT_CELL_MS = 9_000L

    private val nextId = AtomicLong(1)
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    /**
     * Релей не ответил на столько запросов подряд — значит он эту работу не
     * умеет (старая версия) и спрашивать его бессмысленно до переподключения.
     */
    private const val STRIKES_OFF = 3
    @Volatile private var strikes = 0

    /** Сокет переподключился — даём релею ещё один шанс. */
    fun reset() { strikes = 0 }

    fun available(): Boolean = SignalingClient.isOpen() && strikes < STRIKES_OFF

    /**
     * Спросить релей. null — не вышло; вызывающий идёт в базу.
     */
    suspend fun ask(op: String, args: JSONObject = JSONObject()): JSONArray? {
        if (!available()) return null

        val qid = "q" + nextId.getAndIncrement()
        val slot = CompletableDeferred<JSONObject>()
        waiting[qid] = slot

        val sent = SignalingClient.sendRaw(
            JSONObject()
                .put("type", "q")
                .put("qid", qid)
                .put("op", op)
                .put("args", args)
                .toString()
        )
        if (!sent) {
            waiting.remove(qid)
            return null
        }

        return try {
            val timeout = if (NetInfo.isCellular()) TIMEOUT_CELL_MS else TIMEOUT_MS
            val res = withTimeout(timeout) { slot.await() }
            if (res.optString("type") == "q_ok") {
                strikes = 0
                res.optJSONArray("rows")
            } else {
                // Релей ответил отказом: операции он не знает или базы у него
                // нет. Считаем промахи — после нескольких перестаём дёргать.
                strikes++
                null
            }
        } catch (e: TimeoutCancellationException) {
            strikes++
            null
        } finally {
            waiting.remove(qid)
        }
    }

    /** Пришёл ответ — отдаём его тому, кто ждёт. */
    fun deliver(root: JSONObject) {
        val qid = root.optString("qid")
        if (qid.isEmpty()) return
        waiting.remove(qid)?.complete(root)
    }
}
