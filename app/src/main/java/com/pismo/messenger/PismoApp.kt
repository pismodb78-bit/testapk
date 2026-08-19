package com.pismo.messenger

import android.app.Application
import android.content.Context
import com.pismo.messenger.call.IncomingCallMonitor
import com.pismo.messenger.core.EmojiCatalog
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.PresenceReporter
import com.pismo.messenger.data.ChatDiskCache
import com.pismo.messenger.data.ChatListMemory
import com.pismo.messenger.data.MediaCache
import com.pismo.messenger.data.ServerMemory
import com.pismo.messenger.service.Notifications
import com.pismo.messenger.ui.theme.PismoColors

class PismoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        MediaCache.init(this)
        // Кеш переписок и раскладки серверов теперь переживает закрытие
        // приложения: без диска первый заход в любой чат после запуска
        // снова упирался бы в кружок на всё время запроса к базе.
        ChatDiskCache.init(this)
        ServerMemory.init(this)
        ChatListMemory.init(this)
        EmojiCatalog.init(this)
        Notifications.createChannels(this)
        // Палитру ставим до первой отрисовки, иначе светлая тема мигнёт
        // тёмным кадром на старте.
        PismoColors.initFrom(this)

        // Слежение за входящими звонками запускаем на уровне процесса, а не
        // экрана: звонок должен показываться и из переписки, и когда
        // приложение свёрнуто.
        IncomingCallMonitor.start(this)

        // Heartbeat присутствия — тоже на уровне процесса. Раньше он жил в
        // списке чатов и молчал везде, кроме него.
        PresenceReporter.start(this)
    }

    companion object {
        lateinit var instance: PismoApp
            private set

        val appContext: Context get() = instance.applicationContext
    }
}
