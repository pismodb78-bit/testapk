package com.pismo.messenger.data

import android.content.Context
import com.pismo.messenger.core.Crypto
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.model.Conversation
import com.pismo.messenger.data.model.GroupSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Список чатов и групп — то, что видно первым при запуске.
 *
 * Он собирается двумя тяжёлыми запросами к удалённой базе, и до их ответа
 * экран стоял пустым. Обиднее всего это как раз на старте приложения: сам
 * список меняется редко, а ждать его приходилось каждый раз заново.
 *
 * Как и переписки, живёт в памяти процесса и продолжается на диске, а текст
 * последних сообщений хранится ЗАШИФРОВАННЫМ — тем же ключом, что в базе.
 * Счётчики непрочитанного не сохраняем: они протухают за минуты, и вчерашние
 * цифры хуже, чем никаких.
 */
object ChatListMemory {

    /** Сколько строк помним. Дальше список всё равно листают поиском. */
    private const val MAX_ROWS = 200

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var file: File? = null
    private var conversations: List<Conversation> = emptyList()
    private var groups: List<GroupSummary> = emptyList()

    /**
     * Под каким пользователем диск уже прочитан. Именно id, а не флаг: на
     * старте процесса сессии ещё нет, и «уже пробовали» навсегда запомнило
     * бы неудачную попытку до логина.
     */
    private var loadedFor = -1

    fun init(context: Context) {
        file = File(context.filesDir, "chatlist.json")
    }

    @Synchronized
    fun peek(): Pair<List<Conversation>, List<GroupSummary>> {
        ensureLoaded()
        return conversations to groups
    }

    @Synchronized
    fun put(conversations: List<Conversation>, groups: List<GroupSummary>) {
        if (conversations.isEmpty() && groups.isEmpty()) return
        this.conversations = conversations.take(MAX_ROWS)
        this.groups = groups.take(MAX_ROWS)
        loadedFor = UserSession.effectiveId
        persist()
    }

    @Synchronized
    fun clear() {
        conversations = emptyList()
        groups = emptyList()
        loadedFor = -1
        runCatching { file?.delete() }
    }

    fun sizeBytes(): Long = runCatching { file?.length() ?: 0L }.getOrDefault(0L)

    private fun ensureLoaded() {
        val me = UserSession.effectiveId
        if (me <= 0 || loadedFor == me) return
        loadedFor = me
        runCatching {
            val f = file ?: return
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            if (root.optInt("uid") != me) return

            root.optJSONArray("chats")?.let { arr ->
                val list = ArrayList<Conversation>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        Conversation(
                            userId = o.optInt("u"),
                            name = o.optString("n"),
                            lastMessage = Crypto.dec(o.optString("m")),
                            lastTimeMs = if (o.isNull("t")) null else o.optLong("t"),
                            unread = 0,
                            login = o.optString("l"),
                        )
                    )
                }
                conversations = list
            }

            root.optJSONArray("groups")?.let { arr ->
                val list = ArrayList<GroupSummary>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        GroupSummary(
                            id = o.optInt("i"),
                            name = o.optString("n"),
                            lastMessage = Crypto.dec(o.optString("m")),
                            memberCount = o.optInt("c"),
                            avatarColorHex = o.optString("col"),
                            lastTimeMs = if (o.isNull("t")) null else o.optLong("t"),
                            unread = 0,
                        )
                    )
                }
                groups = list
            }
        }
    }

    private fun persist() {
        val f = file ?: return
        val chats = conversations
        val grps = groups
        val uid = UserSession.effectiveId

        io.launch {
            runCatching {
                val ca = JSONArray()
                chats.forEach { c ->
                    ca.put(
                        JSONObject()
                            .put("u", c.userId)
                            .put("n", c.name)
                            .put("m", Crypto.enc(c.lastMessage))
                            .put("t", c.lastTimeMs ?: JSONObject.NULL)
                            .put("l", c.login)
                    )
                }

                val ga = JSONArray()
                grps.forEach { g ->
                    ga.put(
                        JSONObject()
                            .put("i", g.id)
                            .put("n", g.name)
                            .put("m", Crypto.enc(g.lastMessage))
                            .put("c", g.memberCount)
                            .put("col", g.avatarColorHex)
                            .put("t", g.lastTimeMs ?: JSONObject.NULL)
                    )
                }

                val root = JSONObject()
                    .put("uid", uid)
                    .put("chats", ca)
                    .put("groups", ga)

                // Через временный файл: убитый посреди записи процесс иначе
                // оставил бы обрезанный json, и список открывался бы пустым
                // до первой удачной перезаписи.
                val tmp = File(f.absolutePath + ".tmp")
                tmp.writeText(root.toString())
                if (!tmp.renameTo(f)) {
                    f.writeText(root.toString())
                    tmp.delete()
                }
            }
        }
    }
}
