package com.pismo.messenger.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Открывает ссылку: сначала в приложении службы, если оно стоит, иначе в
 * браузере.
 *
 * Instagram, TikTok и им подобные закрыли встраивание — показать их ролик у
 * себя нельзя, не обходя их же ограничение доступа. Зато у человека почти
 * наверняка стоит их приложение, где он уже вошёл: туда и отправляем.
 *
 * Как отличаем «есть приложение» от «есть только браузер». Не списком
 * пакетов — он устареет через месяц и на Android 11+ ещё и требует
 * перечислять всех наперёд в манифесте. Вместо этого просим систему открыть
 * ссылку ЛЮБЫМ обработчиком, кроме браузера; если такого нет, она честно
 * отвечает отказом, и мы открываем браузер.
 *
 * На Android 10 и старше такого запрета нет — там просто обычное открытие,
 * то есть либо приложение, либо выбор из списка, как и всегда.
 */
object LinkOpener {

    fun open(context: Context, url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase()
        // Запускать из чужого сообщения что угодно нельзя: только веб-адреса.
        if (scheme != "http" && scheme != "https") return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val inApp = Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
            try {
                context.startActivity(inApp)
                return
            } catch (_: ActivityNotFoundException) {
                // Приложения для этой ссылки нет — идём в браузер.
            }
        }

        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
