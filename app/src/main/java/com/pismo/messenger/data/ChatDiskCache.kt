package com.pismo.messenger.data

import android.content.Context
import com.pismo.messenger.core.Crypto
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.ReactionSummary
import com.pismo.messenger.data.model.Scope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Постоянный кеш переписок на диске — порт MessageCache.cs.
 *
 * Память процесса (MessageMemory) переживает переход между экранами, но не
 * закрытие приложения: после свайпа из недавних любой чат снова открывался
 * пустым экраном и кружком на всё время запроса к удалённой базе. На ПК
 * этой паузы нет — там переписка лежит на диске и показывается сразу, а
 * свежее подтягивается фоном.
 *
 * ТЕКСТ НА ДИСКЕ ОСТАЁТСЯ ЗАШИФРОВАННЫМ — тем же ключом и в том же виде,
 * что в базе. Ровно как на ПК, и по той же причине: расшифрованная
 * переписка в файле живёт дольше и достаётся проще, чем в памяти процесса.
 * Вложения сюда не кладём: у них свой кеш со своим лимитом (MediaCache).
 *
 * Кеш не критичен: любая ошибка чтения или записи молча означает «кеша
 * нет», и данные приедут из базы, как раньше.
 */
object ChatDiskCache {

    /** Сколько сообщений храним на чат. Больше одного экрана истории ни к чему. */
    private const val MAX_MESSAGES = 200

    /** Сколько переписок держим на диске. Дальше — самые старые вытесняются. */
    private const val MAX_FILES = 40

    private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "msgcache").apply { runCatching { mkdirs() } }
    }

    // Ключи те же, что на ПК, — чтобы имена файлов читались одинаково.
    private fun key(scope: Scope, targetId: Int): String = when (scope) {
        Scope.DM -> "d_${UserSession.effectiveId}_$targetId"
        Scope.GROUP -> "g_$targetId"
        Scope.SERVER -> "s_$targetId"
    }

    private fun fileFor(scope: Scope, targetId: Int): File? =
        dir?.let { File(it, key(scope, targetId) + ".json") }

    /** Что показать сразу при открытии чата; null — на диске ничего нет. */
    fun load(
        scope: Scope,
        targetId: Int,
    ): Pair<List<ChatMessage>, Map<Int, List<ReactionSummary>>>? = runCatching {
        val f = fileFor(scope, targetId) ?: return null
        if (!f.exists()) return null

        val root = JSONObject(f.readText())
        // Чужой кеш не наш: под другим логином переписка не должна всплыть
        // даже на кадр. Для личек это уже зашито в имя файла, для групп и
        // каналов — только здесь.
        if (root.optInt("uid") != UserSession.effectiveId) return null

        val arr = root.optJSONArray("messages") ?: return null
        val messages = ArrayList<ChatMessage>(arr.length())
        for (i in 0 until arr.length()) {
            messages.add(readMessage(arr.getJSONObject(i), scope) ?: continue)
        }
        if (messages.isEmpty()) return null

        val reactions = HashMap<Int, List<ReactionSummary>>()
        root.optJSONObject("reactions")?.let { obj ->
            obj.keys().forEach { k ->
                val id = k.toIntOrNull() ?: return@forEach
                val list = obj.optJSONArray(k) ?: return@forEach
                val out = ArrayList<ReactionSummary>(list.length())
                for (i in 0 until list.length()) {
                    val r = list.getJSONObject(i)
                    out.add(
                        ReactionSummary(
                            emoji = r.optString("e"),
                            count = r.optInt("c"),
                            mine = r.optBoolean("m"),
                        )
                    )
                }
                reactions[id] = out
            }
        }

        messages as List<ChatMessage> to reactions as Map<Int, List<ReactionSummary>>
    }.getOrNull()

    fun save(
        scope: Scope,
        targetId: Int,
        messages: List<ChatMessage>,
        reactions: Map<Int, List<ReactionSummary>>,
    ) {
        if (messages.isEmpty()) return
        runCatching {
            val f = fileFor(scope, targetId) ?: return
            val tail = messages.takeLast(MAX_MESSAGES)

            val arr = JSONArray()
            tail.forEach { arr.put(writeMessage(it)) }

            val rea = JSONObject()
            val kept = tail.map { it.id }.toSet()
            reactions.forEach { (id, list) ->
                if (id !in kept || list.isEmpty()) return@forEach
                val a = JSONArray()
                list.forEach { r ->
                    a.put(
                        JSONObject()
                            .put("e", r.emoji)
                            .put("c", r.count)
                            .put("m", r.mine)
                    )
                }
                rea.put(id.toString(), a)
            }

            val root = JSONObject()
                .put("uid", UserSession.effectiveId)
                .put("messages", arr)
                .put("reactions", rea)

            f.parentFile?.mkdirs()
            // Пишем через временный файл: убитый посреди записи процесс
            // иначе оставил бы обрезанный json, и чат открывался бы пустым
            // до первой удачной перезаписи.
            val tmp = File(f.absolutePath + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(root.toString())
                tmp.delete()
            }
            prune()
        }
    }

    fun invalidate(scope: Scope, targetId: Int) {
        runCatching { fileFor(scope, targetId)?.delete() }
    }

    /** Всё стереть — при смене пользователя и по кнопке «очистить кеш». */
    fun clear() {
        runCatching { dir?.listFiles()?.forEach { it.delete() } }
    }

    fun sizeBytes(): Long =
        runCatching { dir?.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    /** Вытесняем самые давние переписки, а не самые большие: помним последние. */
    private fun prune() {
        runCatching {
            val files = dir?.listFiles()?.filter { it.isFile } ?: return
            if (files.size <= MAX_FILES) return
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_FILES)
                .forEach { it.delete() }
        }
    }

    // ── Сериализация ────────────────────────────────────────────────────
    //
    // Поля короткими именами: файл читает только этот класс, а на сотне
    // сообщений разница в размере уже заметна.

    private fun writeMessage(m: ChatMessage): JSONObject = JSONObject()
        .put("i", m.id)
        .put("s", m.senderId)
        .put("n", m.senderName)
        // Шифруем обратно тем же ключом: на диск текст ложится в том же
        // виде, что лежит в базе.
        .put("t", Crypto.enc(m.text))
        .put("c", m.createdAtMs)
        .put("r", m.replyToId)
        .put("d", m.isDeleted)
        .put("e", m.isEdited)
        .put("img", m.hasImage)
        .put("aud", m.hasAudio)
        .put("vid", m.hasVideo)
        .put("fil", m.hasFile)
        .put("fn", m.fileName ?: JSONObject.NULL)
        .put("rd", m.isRead)
        .put("pin", m.isPinned)

    private fun readMessage(o: JSONObject, scope: Scope): ChatMessage? = runCatching {
        ChatMessage(
            id = o.getInt("i"),
            senderId = o.optInt("s"),
            senderName = o.optString("n"),
            text = Crypto.dec(o.optString("t")),
            createdAtMs = o.optLong("c"),
            replyToId = o.optInt("r"),
            isDeleted = o.optBoolean("d"),
            isEdited = o.optBoolean("e"),
            hasImage = o.optBoolean("img"),
            hasAudio = o.optBoolean("aud"),
            hasVideo = o.optBoolean("vid"),
            hasFile = o.optBoolean("fil"),
            fileName = if (o.isNull("fn")) null else o.optString("fn"),
            scope = scope,
            isRead = o.optBoolean("rd", true),
            isPinned = o.optBoolean("pin"),
        )
    }.getOrNull()
}
