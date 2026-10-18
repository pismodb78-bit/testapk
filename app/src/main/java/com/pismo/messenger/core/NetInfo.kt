package com.pismo.messenger.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Какая сейчас сеть и когда она сменилась.
 *
 * МОБИЛЬНАЯ СЕТЬ — НЕ ПРОСТО МЕДЛЕННЫЙ WI-FI. У оператора между телефоном и
 * сервером стоит NAT, и простаивающую TCP-сессию он выбрасывает через
 * десятки секунд — молча, не сообщая ни одной из сторон. Соединение,
 * пролежавшее в пуле минуту, на вид живое, а на деле его уже нет: запрос
 * уходит в никуда и висит до таймаута. Дома роутер держит сессии минутами,
 * поэтому на Wi-Fi та же программа работает безупречно.
 *
 * Так же устроены и мессенджеры: держат одно соединение и постоянно
 * подтверждают его жизнь пингами, чтобы NAT считал сессию живой. Здесь по
 * этому признаку настраиваются пул соединений и частота подтверждений.
 *
 * Смена сети (Wi-Fi ↔ мобильная, переход между вышками) меняет исходящий
 * адрес, и все прежние соединения становятся мусором. Поэтому о смене нужно
 * знать сразу, а не выяснять её таймаутами.
 */
object NetInfo {

    @Volatile
    private var cm: ConnectivityManager? = null

    @Volatile
    private var cellular = false

    /** Счётчик смен сети: вырос — прежние соединения пора выбросить. */
    @Volatile
    var epoch: Int = 0
        private set

    /** Мобильная ли сеть сейчас. До инициализации считаем, что нет. */
    fun isCellular(): Boolean = cellular

    fun init(context: Context) {
        if (cm != null) return
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        cm = manager
        refresh(manager)

        runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = bump(manager)
                    override fun onLost(network: Network) = bump(manager)
                    override fun onCapabilitiesChanged(
                        network: Network,
                        caps: NetworkCapabilities,
                    ) {
                        val now = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        if (now != cellular) { cellular = now; epoch++ }
                    }
                },
            )
        }
    }

    private fun bump(manager: ConnectivityManager) {
        refresh(manager)
        epoch++
    }

    private fun refresh(manager: ConnectivityManager) {
        cellular = runCatching {
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }.getOrDefault(false)
    }
}
