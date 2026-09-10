package com.pismo.messenger.ui.chat

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pismo.messenger.core.VideoLinks

/**
 * Проигрыватель видео по ссылке — во весь экран, не уходя из переписки.
 *
 * Прямой файл играем сами, тем же VideoView, что и вложения. Страницу
 * службы (YouTube, RUTUBE) — её собственным встроенным проигрывателем в
 * WebView: разбирать их внутренние потоки нельзя, да и незачем, встраивание
 * они предлагают сами.
 *
 * Заметное ограничение: без сети или при заблокированной службе окно
 * останется пустым — мы показываем чужую страницу и не знаем, что у неё
 * внутри. На этот случай в меню ссылки остаётся «Открыть» в браузере.
 */
@Composable
fun LinkVideoDialog(playable: VideoLinks.Playable, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when (playable) {
                is VideoLinks.Playable.Direct -> DirectPlayer(playable.url)
                is VideoLinks.Playable.Embed -> EmbedPlayer(playable.url, playable.origin)
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(Icons.Default.Close, "Закрыть", tint = Color.White)
            }
        }
    }
}

@Composable
private fun DirectPlayer(url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = remember(url) { VideoView(context) }

    // Проигрыватель держит декодер: если не остановить его при закрытии,
    // звук продолжает идти поверх переписки.
    DisposableEffect(url) {
        onDispose { runCatching { view.stopPlayback() } }
    }

    AndroidView(
        factory = {
            view.apply {
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setVideoURI(android.net.Uri.parse(url))
                setOnPreparedListener { it.isLooping = false; start() }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbedPlayer(url: String, origin: String) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Кому возвращать поворот экрана после полного экрана. Ищем окно по
    // цепочке обёрток: у диалога это не сам Activity, а обёртка вокруг него.
    val activity = remember(context) {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>()
            .firstOrNull()
    }
    val savedOrientation = remember(context) {
        activity?.requestedOrientation
            ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // Рамка вокруг страницы. Она нужна не для порядка: кнопка «на весь
    // экран» внутри проигрывателя просит у приложения ОТДЕЛЬНОЕ
    // представление, и если положить его некуда, нажатие просто ничего не
    // делает — ровно так это и выглядело.
    val host = remember(url) { android.widget.FrameLayout(context) }

    val web = remember(url) {
        val view = WebView(context)
        view.apply {
            settings.javaScriptEnabled = true          // без него проигрыватель не запустится
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            setBackgroundColor(android.graphics.Color.BLACK)
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                private var custom: android.view.View? = null

                override fun onShowCustomView(
                    view: android.view.View?,
                    callback: CustomViewCallback?,
                ) {
                    if (view == null) return
                    if (custom != null) { callback?.onCustomViewHidden(); return }
                    custom = view
                    host.addView(
                        view,
                        android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    view.visibility = android.view.View.GONE
                    // Видео шире, чем выше: в полном экране разворачиваем
                    // телефон, как это делает любой проигрыватель.
                    activity?.requestedOrientation =
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }

                override fun onHideCustomView() {
                    custom?.let { host.removeView(it) }
                    custom = null
                    view.visibility = android.view.View.VISIBLE
                    activity?.requestedOrientation = savedOrientation
                }
            }

            // Страницу с рамкой собираем САМИ и отдаём от имени домена службы.
            //
            // Прямая загрузка адреса встраивания приходит без источника, и
            // проигрыватель отказывается работать: YouTube отвечает на это
            // ошибкой 153 «Video player configuration error». Базовый адрес в
            // loadDataWithBaseURL и есть тот источник, которого ему не хватало.
            //
            // referrerpolicy — вторая половина того же лечения. С конца 2025
            // года YouTube требует, чтобы страница-хозяин называла себя
            // заголовком Referer; если его нет или он вырезан, настройка
            // проигрывателя срывается с той же ошибкой. Значение отдаёт только
            // домен и ничего сверх него.
            val html = """
                <!doctype html>
                <html><head><meta name="viewport"
                    content="width=device-width, initial-scale=1, viewport-fit=cover">
                <meta name="referrer" content="strict-origin-when-cross-origin"></head>
                <body style="margin:0;background:#000;height:100vh">
                <iframe src="$url" style="border:0;width:100%;height:100%"
                        referrerpolicy="strict-origin-when-cross-origin"
                        allow="autoplay; encrypted-media; fullscreen; picture-in-picture"
                        allowfullscreen></iframe>
                </body></html>
            """.trimIndent()
            loadDataWithBaseURL(origin, html, "text/html", "utf-8", null)
        }
        view
    }

    // Останавливаем звук, снимаем страницу и возвращаем поворот экрана при
    // закрытии: WebView иначе продолжает играть в фоне, а телефон — лежать
    // на боку.
    DisposableEffect(url) {
        onDispose {
            runCatching {
                activity?.requestedOrientation = savedOrientation
                web.loadUrl("about:blank")
                web.onPause()
                host.removeAllViews()
                web.destroy()
            }
        }
    }

    AndroidView(
        factory = {
            host.addView(
                web,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            host
        },
        modifier = Modifier.fillMaxSize(),
    )
}
