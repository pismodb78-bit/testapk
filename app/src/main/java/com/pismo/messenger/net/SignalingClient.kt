package com.pismo.messenger.net

import android.util.Log
import com.pismo.messenger.core.JwtAuth
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Клиент ws-сервера сигналинга — порт WebSocketSignalingClient.cs.
 *
 * Через него ходят только события (new_message, incoming_call, call_status,
 * присутствие). Медиа звонка идёт мимо, через LiveKit.
 *
 * Сервер необязателен: если он недоступен, приложение продолжает работать
 * на опросе БД — ровно как десктоп.
 */
object SignalingClient {

    private const val TAG = "WS"
    private const val RECONNECT_DELAY_MS = 3000L

    /** type, senderUserId, sessionId, payload */
    private val listeners = mutableListOf<(String, Int, Int, String) -> Unit>()

    private var ws: WebSocket? = null
    private val connecting = AtomicBoolean(false)
    private var myUserId = 0
    private var wantConnection = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)     // держим соединение открытым
        .pingInterval(20, TimeUnit.SECONDS)        // ping/pong как на ПК
        .retryOnConnectionFailure(true)
        .build()

    val isConnected: Boolean get() = ws != null

    fun addListener(listener: (String, Int, Int, String) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: (String, Int, Int, String) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun emit(type: String, sender: Int, session: Int, payload: String) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { runCatching { it(type, sender, session, payload) } }
    }

    fun connect(userId: Int) {
        if (!Prefs.wsEnabled) return
        if (connecting.getAndSet(true)) return
        if (ws != null) { connecting.set(false); return }

        myUserId = userId
        wantConnection = true

        runCatching {
            val request = Request.Builder().url(Prefs.wsUrl).build()
            ws = http.newWebSocket(request, Listener())
        }.onFailure {
            Log.w(TAG, "не удалось подключиться: ${it.message}")
            connecting.set(false)
            scheduleReconnect()
        }
    }

    fun disconnect() {
        wantConnection = false
        runCatching { ws?.close(1000, "bye") }
        ws = null
        connecting.set(false)
    }

    /** Отправка события. Если сокета нет — тихо игнорируем, как на ПК. */
    fun send(type: String, targetUserId: Int, sessionId: Int, payload: String) {
        val socket = ws ?: return
        runCatching {
            val json = JSONObject()
                .put("type", type)
                .put("userId", myUserId)
                .put("targetUserId", targetUserId)
                .put("sessionId", sessionId)
                .put("payload", payload)
            socket.send(json.toString())
        }
    }

    private fun scheduleReconnect() {
        if (!wantConnection) return
        Thread {
            Thread.sleep(RECONNECT_DELAY_MS)
            if (wantConnection && ws == null) connect(myUserId)
        }.apply { isDaemon = true }.start()
    }

    private class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            connecting.set(false)
            Log.d(TAG, "подключено к ${Prefs.wsUrl}")

            // Пакет регистрации с JWT — сервер сверяет подпись тем же секретом.
            runCatching {
                val token = JwtAuth.create(myUserId, UserSession.userName)
                val reg = JSONObject()
                    .put("type", "register")
                    .put("userId", myUserId)
                    .put("token", token)
                webSocket.send(reg.toString())
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val root = JSONObject(text)
                val type = root.optString("type")
                if (type.isEmpty() || type == "pong") return

                emit(
                    type,
                    root.optInt("userId", 0),
                    root.optInt("sessionId", 0),
                    root.optString("payload"),
                )
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "обрыв: ${t.message}")
            ws = null
            connecting.set(false)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ws = null
            connecting.set(false)
            scheduleReconnect()
        }
    }
}
