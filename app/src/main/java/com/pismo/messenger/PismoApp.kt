package com.pismo.messenger

import android.app.Application
import android.content.Context
import com.pismo.messenger.call.IncomingCallMonitor
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.data.MediaCache
import com.pismo.messenger.service.Notifications

class PismoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        MediaCache.init(this)
        Notifications.createChannels(this)

        // Слежение за входящими звонками запускаем на уровне процесса, а не
        // экрана: звонок должен показываться и из переписки, и когда
        // приложение свёрнуто.
        IncomingCallMonitor.start(this)
    }

    companion object {
        lateinit var instance: PismoApp
            private set

        val appContext: Context get() = instance.applicationContext
    }
}
