package com.pismo.messenger.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.pismo.messenger.BuildConfig
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.db.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Адрес этого телефона для push-уведомлений.
 *
 * ЗАЧЕМ ЭТО ВСЁ. Фоновая проверка держит постоянное уведомление, потому что
 * держит фоновую службу, — иначе Android не даёт работать за спиной. Push
 * решает задачу с другой стороны: сообщение приносит сама система, служба не
 * нужна, уведомления в шторке нет, батарея не тратится. Приходит даже когда
 * приложение выгружено.
 *
 * ЧТО НУЖНО, ЧТОБЫ ЗАРАБОТАЛО. Файл google-services.json рядом с модулем
 * (его выдаёт консоль Firebase) и отправка со стороны ws-сервера. Ни того,
 * ни другого в репозитории нет: первое приватно, второе живёт на VPS.
 * Поэтому здесь всё построено так, чтобы отсутствие настроек НЕ ломало
 * ничего — приложение просто остаётся на фоновой проверке, как раньше.
 *
 * ГДЕ ЛЕЖИТ АДРЕС. В таблице device_tokens (миграция 22) — по ней сервер
 * узнаёт, куда слать. Ключ таблицы — сам токен, а не пользователь: на одном
 * телефоне могут по очереди войти двое, и тогда строка обязана переехать к
 * новому, а не размножиться.
 */
object PushTokens {

    private const val TAG = "Push"

    /** Собрано ли приложение с настройками Firebase. */
    val configured: Boolean get() = BuildConfig.HAS_FIREBASE

    @Volatile private var lastToken: String? = null

    /**
     * Просит у Firebase адрес этого устройства и записывает его за текущим
     * пользователем. Вызывать после входа.
     */
    fun register() {
        if (!configured) return
        if (UserSession.effectiveId <= 0) return
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> save(token) }
                .addOnFailureListener { e -> Log.w(TAG, "токен не выдан: ${e.message}") }
        }.onFailure {
            // Firebase не поднялся (нет настроек) — это не ошибка, а
            // ожидаемое состояние сборки без google-services.json.
            Log.i(TAG, "push недоступен: ${it.message}")
        }
    }

    /** Токен сменился сам — Firebase их иногда обновляет. */
    fun onNewToken(token: String) = save(token)

    private fun save(token: String) {
        if (token.isBlank()) return
        val me = UserSession.effectiveId
        if (me <= 0) return
        lastToken = token
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                // REPLACE, а не INSERT: если этот телефон раньше принадлежал
                // другому аккаунту, строка должна переехать, а не остаться
                // указывать на прежнего — иначе его сообщения посыплются
                // сюда.
                Db.exec(
                    "REPLACE INTO device_tokens (token, user_id, platform) VALUES (?, ?, 'android')",
                    token, me
                )
            }.onFailure { Log.w(TAG, "не записали токен: ${it.message}") }
        }
    }

    /**
     * Выход из аккаунта: убираем свой адрес, иначе на этот телефон будут
     * приходить чужие сообщения после смены пользователя.
     */
    fun unregister() {
        val token = lastToken ?: return
        lastToken = null
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { Db.exec("DELETE FROM device_tokens WHERE token=?", token) }
        }
    }
}
