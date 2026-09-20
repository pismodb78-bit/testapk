package com.pismo.messenger.desktop

import android.content.Context
import com.pismo.messenger.core.CrashLog
import com.pismo.messenger.core.EmojiCatalog
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.data.ChatDiskCache
import com.pismo.messenger.data.ChatListMemory
import com.pismo.messenger.data.LinkPreviews
import com.pismo.messenger.data.MediaCache
import com.pismo.messenger.data.ServerMemory

/**
 * Подготовка процесса — настольный аналог PismoApp.onCreate.
 *
 * Порядок тот же, что на телефоне, и по той же причине: запись падений
 * ставится первой, иначе падение самой подготовки записать будет некому,
 * а человек останется с пустым окном и без объяснений.
 */
object Platform {

    /** Единственный «контекст» на процесс: пути и настройки. */
    val context: Context = Context()

    @Volatile private var ready = false

    @Synchronized
    fun init() {
        if (ready) return
        CrashLog.install(context)
        Prefs.init(context)
        MediaCache.init(context)
        LinkPreviews.init(context)
        ChatDiskCache.init(context)
        ServerMemory.init(context)
        ChatListMemory.init(context)
        EmojiCatalog.init(context)
        ready = true
    }
}
