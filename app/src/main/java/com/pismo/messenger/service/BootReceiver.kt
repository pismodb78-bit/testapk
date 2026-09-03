package com.pismo.messenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.repo.AuthRepository
import com.pismo.messenger.net.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Поднимает фоновую службу после перезагрузки телефона и после обновления
 * приложения.
 *
 * Без этого PISMO молчала до первого ручного открытия: службу запускала
 * только MainActivity, а после перезагрузки её никто не запускал. Человек
 * при этом уверен, что мессенджер работает — он ведь установлен и был
 * включён.
 *
 * Ждём именно BOOT_COMPLETED, который приходит после разблокировки. До неё
 * хранилище настроек ещё зашифровано, а нам нужны и сохранённый вход, и база,
 * и сеть — то есть делать всё равно нечего.
 *
 * Чего это НЕ чинит: «Остановить принудительно» в настройках. Оттуда Android
 * переводит приложение в остановленное состояние, и широковещательные события
 * ему не доставляются вовсе — этот приёмник тоже не сработает. Лечится только
 * настоящим push через сервис Google, где соединение держит не наш процесс.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val app = context.applicationContext
        Prefs.init(app)
        if (!Prefs.backgroundPolling) return

        // Служба без входа бесполезна: опрашивать нечего. Вход восстанавливаем
        // теми же сохранёнными данными, что и при обычном запуске, и уже потом
        // поднимаем службу и сокет.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ok = runCatching { AuthRepository.autoLogin() }.getOrDefault(false)
                if (ok) {
                    runCatching { SignalingClient.connect(UserSession.effectiveId) }
                    PollingService.start(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
