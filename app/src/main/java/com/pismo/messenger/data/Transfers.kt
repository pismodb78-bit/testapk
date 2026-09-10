package com.pismo.messenger.data

import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.ServerRepository
import com.pismo.messenger.net.SignalingClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Отправка и получение вложений — отдельно от экрана.
 *
 * Живёт в области процесса, поэтому выход из переписки ничего не обрывает:
 * раньше и отправка, и скачивание шли в области видимости самого экрана, и
 * стоило выйти, как всё прекращалось на середине. У отправки это оставляло
 * собеседнику пустое сообщение с одним именем файла.
 *
 * Идут ПО ОЧЕРЕДИ. Три файла разом раньше запускались параллельно, и это
 * ломалось сразу с трёх сторон: общий список задач правился без всякой
 * защиты и куски правок затирали друг друга (полосы просто пропадали),
 * каждая отправка держала своё соединение из четырёх, а байты всех файлов
 * лежали в памяти одновременно. Теперь очередь: остальные ждут и видны в
 * списке ожидающими.
 *
 * Отмена отправки удаляет и наполовину написанную строку: незачем
 * оставлять в переписке сообщение-пустышку.
 */
object Transfers {

    /** Одна идущая передача — отправка или скачивание. */
    data class Task(
        val id: Long,
        /** Куда/откуда — чтобы показать полосу в нужной переписке. */
        val where: String,
        val fileName: String,
        /** Размер, если известен заранее. У скачивания — 0: его знает сервер. */
        val totalBytes: Long,
        /** Доля от нуля до единицы. */
        val progress: Float = 0f,
        val download: Boolean = false,
        /** Ещё не начали — стоит в очереди за предыдущим файлом. */
        val waiting: Boolean = true,
        /** Заполнено — сорвалось, и полоса показывает причину. */
        val error: String? = null,
    )

    fun chatKey(isGroup: Boolean, target: Int): String =
        if (isGroup) "group:$target" else "dm:$target"

    fun channelKey(channelId: Int): String = "chan:$channelId"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Очередь. Отправки и скачивания разведены: качать, стоя за собственной
     * многоминутной отправкой, было бы странно, а двух одновременных
     * соединений пул из четырёх выдерживает спокойно.
     */
    private val uploadGate = Mutex()
    private val downloadGate = Mutex()

    private val jobs = HashMap<Long, Job>()
    /** Куда откатывать отмену: таблица и номер уже вставленной строки. */
    private val rows = HashMap<Long, Pair<String, Int>>()
    private var nextId = 1L

    private val _active = MutableStateFlow<List<Task>>(emptyList())
    val active: StateFlow<List<Task>> = _active

    // ── отправка ──────────────────────────────────────────────────────

    /** Личное сообщение или сообщение в группу. */
    fun sendChat(
        scopeKind: Scope,
        target: Int,
        isGroup: Boolean,
        text: String,
        image: ByteArray?,
        file: ByteArray?,
        fileName: String?,
        replyToId: Int,
    ) = launch(
        where = chatKey(isGroup, target),
        fileName = fileName ?: "Фото",
        total = ((file ?: image)?.size ?: 0).toLong(),
        download = false,
        gate = uploadGate,
        table = scopeKind.table,
    ) { onRow, onBytes ->
        ChatRepository.sendMessage(
            scope = scopeKind,
            target = target,
            text = text,
            image = image,
            file = file,
            fileName = fileName,
            replyToId = replyToId,
            onRowCreated = onRow,
            onProgress = onBytes,
        )
        if (isGroup) SignalingClient.send("new_message", 0, target, "group")
        else SignalingClient.send("new_message", 0, target, "direct")
    }

    /** Сообщение в текстовый канал сервера. */
    fun sendChannel(
        channelId: Int,
        text: String,
        replyToId: Int,
        image: ByteArray?,
        file: ByteArray?,
        fileName: String?,
    ) = launch(
        where = channelKey(channelId),
        fileName = fileName ?: "Фото",
        total = ((file ?: image)?.size ?: 0).toLong(),
        download = false,
        gate = uploadGate,
        table = Scope.SERVER.table,
    ) { onRow, onBytes ->
        ServerRepository.sendChannelMessage(
            channelId = channelId,
            text = text,
            replyToId = replyToId,
            image = image,
            file = file,
            fileName = fileName,
            onRowCreated = onRow,
            onProgress = onBytes,
        )
        SignalingClient.send("new_message", 0, channelId, "server")
    }

    // ── скачивание ────────────────────────────────────────────────────

    /**
     * Тянет файл сообщения. Байты кладутся в кеш, поэтому уход из чата
     * скачивание не прерывает: вернувшись, файл открывается сразу.
     *
     * [onReady] зовётся только при успехе и только если экран ещё жив —
     * открывать файл поверх чужого чата не стоит.
     */
    fun download(
        where: String,
        msgId: Int,
        scopeKind: Scope,
        fileName: String?,
        onReady: suspend (ByteArray) -> Unit,
    ) = launch(
        where = where,
        fileName = fileName ?: "Файл",
        total = 0L,                       // размер знает сервер, полосу ведём по доле
        download = true,
        gate = downloadGate,
        table = scopeKind.table,
    ) { _, onBytes ->
        val data = ChatRepository.loadFileTracked(msgId, scopeKind, fileName, onBytes)
        if (data == null || data.isEmpty()) error("файл не найден на сервере")
        onReady(data)
    }

    // ── общая часть ───────────────────────────────────────────────────

    private fun launch(
        where: String,
        fileName: String,
        total: Long,
        download: Boolean,
        gate: Mutex,
        table: String,
        body: suspend (onRow: (Int) -> Unit, onBytes: (Float) -> Unit) -> Unit,
    ) {
        if (!download && total <= 0L) return        // следить не за чем
        val id = synchronized(this) { nextId++ }
        _active.update { it + Task(id, where, fileName, total, download = download) }

        // Ленивый запуск: сначала кладём задачу в список, потом стартуем.
        // Иначе быстрая передача успевала бы завершиться и вычеркнуть себя
        // раньше, чем мы её вообще записали, — и запись осталась бы навсегда.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                gate.withLock {
                    update(id) { it.copy(waiting = false) }
                    body(
                        { rowId ->
                            if (rowId > 0) synchronized(this@Transfers) { rows[id] = table to rowId }
                        },
                        { done -> update(id) { it.copy(progress = done.coerceIn(0f, 1f)) } },
                    )
                }
                finish(id)
            } catch (e: CancellationException) {
                throw e                     // отмену доводит до конца cancel()
            } catch (e: Throwable) {
                // Молча гасить сбой нельзя: со стороны это выглядит как
                // «нажал, и ничего не произошло». Полоса остаётся и
                // показывает причину, а недописанную строку убираем — иначе
                // в переписке останется сообщение с именем файла без файла.
                if (!download) dropRow(id)
                update(id) {
                    it.copy(error = e.message ?: if (download) "не удалось скачать"
                                                 else "не удалось отправить")
                }
                synchronized(this@Transfers) { jobs.remove(id) }
            }
        }
        synchronized(this) { jobs[id] = job }
        job.start()
    }

    /** Отмена: обрываем передачу и убираем наполовину написанную строку. */
    fun cancel(id: Long) {
        val job = synchronized(this) { jobs.remove(id) }
        job?.let {
            // Рвём соединение НАСИЛЬНО, а не просто отменяем.
            //
            // Отмена корутины не прерывает уже начатый запрос к базе: поток
            // сидит внутри блокирующей записи до сетевого таймаута и всё это
            // время держит очередь — следующий файл просто не начинается.
            ChatRepository.abortTransfer(it)
            it.cancel()
        }

        val row = synchronized(this) { rows.remove(id) }
        scope.launch {
            if (row != null) {
                withContext(NonCancellable) { ChatRepository.deleteRowIn(row.first, row.second) }
            }
            // Список правим ПОСЛЕ удаления строки: экран перечитывает
            // переписку, как только задача из него пропадёт, и раньше успевал
            // сделать это до удаления — пустой пузырь оставался висеть.
            finish(id)
        }
    }

    /** Убирает строку сообщения, если она уже вставлена. */
    private fun dropRow(id: Long) {
        val row = synchronized(this) { rows.remove(id) } ?: return
        scope.launch {
            withContext(NonCancellable) { ChatRepository.deleteRowIn(row.first, row.second) }
        }
    }

    private fun finish(id: Long) {
        synchronized(this) { jobs.remove(id); rows.remove(id) }
        _active.update { list -> list.filterNot { it.id == id } }
    }

    /**
     * Правка списка — только так. Раньше здесь было чтение и запись
     * подряд, и при нескольких файлах разом правки затирали друг друга:
     * полосы пропадали, будто отправка умерла.
     */
    private fun update(id: Long, change: (Task) -> Task) {
        _active.update { list -> list.map { if (it.id == id) change(it) else it } }
    }
}
