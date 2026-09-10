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
                is VideoLinks.Playable.Embed -> EmbedPlayer(playable.url)
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
private fun EmbedPlayer(url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val web = remember(url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true          // без него проигрыватель не запустится
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            setBackgroundColor(android.graphics.Color.BLACK)
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl(url)
        }
    }

    // Останавливаем звук и снимаем страницу при закрытии: WebView иначе
    // продолжает играть в фоне.
    DisposableEffect(url) {
        onDispose {
            runCatching {
                web.loadUrl("about:blank")
                web.onPause()
                web.destroy()
            }
        }
    }

    AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
}
