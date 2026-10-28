package com.pismo.messenger.ui.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class SavedDraft(val text: String, val files: List<PendingFile>)

/** Локальные черновики; вложения лежат в приватной папке приложения, не в Preferences. */
internal object DraftStore {
    private const val PREFS = "chat_drafts_v1"
    private val fileSignatures = mutableMapOf<String, String>()
    private fun id(key: String) = key.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    /**
     * Счётчик изменений: списки чатов читают его, чтобы метка «Черновик»
     * появлялась и пропадала сразу после выхода из чата, а не при следующей
     * случайной перерисовке.
     */
    val changes = androidx.compose.runtime.mutableIntStateOf(0)

    fun has(context: Context, key: String): Boolean = context.getSharedPreferences(PREFS, 0).contains(id(key))
    fun load(context: Context, key: String): SavedDraft {
        val raw = context.getSharedPreferences(PREFS, 0).getString(id(key), null) ?: return SavedDraft("", emptyList())
        return runCatching {
            val json = JSONObject(raw)
            val dir = File(context.filesDir, "chat-drafts/${id(key)}")
            val files = buildList {
                val arr = json.optJSONArray("files") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val data = File(dir, item.getString("data")).takeIf { it.isFile }?.readBytes() ?: continue
                    val coverName = item.optString("cover").takeIf { it.isNotBlank() }
                    val cover = coverName?.let { name -> File(dir, name).takeIf { it.isFile }?.readBytes() }
                    add(PendingFile(data, item.optString("name"), item.optBoolean("image"), cover))
                }
            }
            SavedDraft(json.optString("text"), files)
        }.getOrDefault(SavedDraft("", emptyList()))
    }
    fun save(context: Context, key: String, text: String, files: List<PendingFile>) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val k = id(key)
        if (text.isBlank() && files.isEmpty()) {
            val had = prefs.contains(k)
            prefs.edit().remove(k).apply()
            File(context.filesDir, "chat-drafts/$k").deleteRecursively()
            synchronized(fileSignatures) { fileSignatures.remove(k) }
            if (had) changes.intValue++
            return
        }
        runCatching {
            val dir = File(context.filesDir, "chat-drafts/$k").apply { mkdirs() }
            val signature = files.joinToString("|") { "${System.identityHashCode(it.bytes)}:${it.bytes.size}:${it.fileName}:${System.identityHashCode(it.cover)}" }
            val writeFiles = synchronized(fileSignatures) { fileSignatures[k] != signature }
            val arr = JSONArray()
            files.forEachIndexed { i, f ->
                val dataName = "file_$i.bin"
                if (writeFiles || !File(dir, dataName).isFile) File(dir, dataName).writeBytes(f.bytes)
                val coverName = if (f.cover != null) "cover_$i.bin" else ""
                if (f.cover != null && (writeFiles || !File(dir, coverName).isFile)) File(dir, coverName).writeBytes(f.cover)
                arr.put(JSONObject().put("name", f.fileName).put("image", f.isImage)
                    .put("data", dataName).put("cover", coverName))
            }
            val had = prefs.contains(k)
            prefs.edit().putString(k, JSONObject().put("text", text).put("files", arr).toString()).apply()
            synchronized(fileSignatures) { fileSignatures[k] = signature }
            if (!had) changes.intValue++
        }
    }
}
