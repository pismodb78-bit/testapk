package com.pismo.messenger.data

import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.ServerRepository
import com.pismo.messenger.net.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Отправка вложений — отдельно от экрана.
 *
 * Раньше файл уходил в области видимости самого чата. Стоило выйти из
 * переписки, как область закрывалась и дозапись обрывалась на середине:
 * строка сообщения к тому моменту уже вставлена (сначала метаданные, потом
 * тело файла порциями), поэтому у собеседника оставалось пустое сообщение
 * с именем файла и без самого файла. Теперь отправка живёт в области
 * процесса и выход из чата ей не мешает.
 *
 * Заодно отсюда видно, что именно сейчас грузится и сколько осталось, —
 * на ПК такой показ с отменой был, на телефоне не было ничего.
 *
 * Отмена удаляет и наполовину написанную строку: незачем оставлять в
 * переписке сообщение-пустышку.
 */
object Uploads {

    /** Один идущий файл. */
    data class Task(
        val id: Long,
        /** Куда грузим — чтобы показать полосу в нужной переписке. */
        val where: String,
        val fileName: String,
        val totalBytes: Long,
        val sentBytes: Long = 0L,
    ) {
        val progress: Float
            get() = if (totalBytes <= 0L) 0f
                    else (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    fun chatKey(isGroup: Boolean, target: Int): String =
        if (isGroup) "group:$target" else "dm:$target"

    fun channelKey(channelId: Int): String = "chan:$channelId"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<Long, Job>()
    /** Куда откатывать отмену: таблица и номер уже вставленной строки. */
    private val rows = HashMap<Long, Pair<String, Int>>()
    private var nextId = 1L

    private val _active = MutableStateFlow<List<Task>>(emptyList())
    val active: StateFlow<List<Task>> = _active

    /** Что сейчас грузится в эту переписку, если грузится. */
    fun of(where: String): Task? = _active.value.firstOrNull { it.where == where }

    /**
     * Личное сообщение или сообщение в группу. Возвращает номер задачи,
     * если файл достаточно велик, чтобы за ним стоило следить.
     */
    fun sendChat(
        scopeKind: Scope,
        target: Int,
        isGroup: Boolean,
        text: String,
        image: ByteArray?,
        file: ByteArray?,
        fileName: String?,
        replyToId: Int,
    ) {
        val where = chatKey(isGroup, target)
        launchUpload(where, fileName, file, scopeKind.table) { onRow, onBytes ->
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
    }

    /** Сообщение в текстовый канал сервера. */
    fun sendChannel(
        channelId: Int,
        text: String,
        replyToId: Int,
        image: ByteArray?,
        file: ByteArray?,
        fileName: String?,
    ) {
        val where = channelKey(channelId)
        launchUpload(where, fileName, file, Scope.SERVER.table) { onRow, onBytes ->
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
    }

    private fun launchUpload(
        where: String,
        fileName: String?,
        file: ByteArray?,
        table: String,
        body: suspend (onRow: (Int) -> Unit, onBytes: (Float) -> Unit) -> Unit,
    ) {
        val total = (file?.size ?: 0).toLong()
        val id = synchronized(this) { nextId++ }
        // Полосу показываем только для файлов: картинка уходит одним запросом
        // вместе со строкой, следить там не за чем.
        val tracked = total > 0
        if (tracked) {
            _active.value = _active.value + Task(id, where, fileName ?: "Файл", total)
        }

        val job = scope.launch {
            try {
                body(
                    { rowId -> if (rowId > 0) synchronized(this@Uploads) { rows[id] = table to rowId } },
                    { done -> if (tracked) update(id) { it.copy(sentBytes = (done * total).toLong()) } },
                )
            } catch (_: Throwable) {
                // Отмена приходит сюда же. Строку убирает cancel(); при других
                // сбоях она останется — как и на ПК, где неудачная отправка
                // тоже видна в переписке.
            } finally {
                finish(id)
            }
        }
        synchronized(this) { jobs[id] = job }
    }

    /** Отмена: обрываем дозапись и убираем наполовину написанную строку. */
    fun cancel(id: Long) {
        val job = synchronized(this) { jobs.remove(id) }
        val row = synchronized(this) { rows.remove(id) }
        job?.cancel()
        if (row != null) {
            scope.launch {
                withContext(NonCancellable) {
                    ChatRepository.deleteRowIn(row.first, row.second)
                }
            }
        }
        finish(id)
    }

    private fun finish(id: Long) {
        synchronized(this) { jobs.remove(id); rows.remove(id) }
        _active.value = _active.value.filterNot { it.id == id }
    }

    private inline fun update(id: Long, change: (Task) -> Task) {
        _active.value = _active.value.map { if (it.id == id) change(it) else it }
    }
}
