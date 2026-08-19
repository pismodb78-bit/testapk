package com.pismo.messenger.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.ui.theme.PismoColors

/**
 * Заставка «Нет связи с сервером» — порт ConnectionGuard.cs.
 *
 * Появляется, когда запрос к базе не прошёл дважды подряд, и пропадает сама,
 * как только связь вернулась: Db в это время стучится в базу раз в 2,5
 * секунды. На телефоне это не редкость, а норма — лифт, метро, переход
 * между вышками; без заставки приложение выглядело просто зависшим, а
 * нажатия молча уходили в никуда.
 *
 * Как и на ПК, это НЕ отдельное окно, а панель поверх содержимого: она
 * перекрывает интерфейс целиком и глотает касания, поэтому под ней ничего
 * не нажимается. Ставится в корне, рядом с навигацией, чтобы переживать
 * переходы между экранами.
 *
 * Поверх ЗВОНКА не показывается: разговор идёт через LiveKit и потери базы
 * не замечает, а закрывать собеседника заставкой из-за упавшего SQL —
 * ровно тот случай, когда лечение хуже болезни.
 *
 * [enabled] — где заставку показывать вообще. На ПК ConnectionGuard живёт
 * в MainForm, то есть форму входа не закрывает; здесь то же самое и по той
 * же причине, только причина ещё острее: экран настроек — единственное
 * место, где чинят адрес базы, и запереть его заставкой «нет связи»
 * значило бы сделать неверную настройку неисправимой.
 */
@Composable
fun ConnectionOverlay(enabled: Boolean) {
    val online by Db.online.collectAsState()

    AnimatedVisibility(
        visible = enabled && !online,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(PismoColors.BgMain)
                // Глотаем касания: под панелью интерфейс жив и по нему
                // по-прежнему можно попасть пальцем, а каждое такое нажатие
                // — ещё один запрос в недоступную базу.
                .pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp),
            ) {
                CircularProgressIndicator(
                    color = PismoColors.Blurple,
                    strokeWidth = 5.dp,
                    modifier = Modifier.size(44.dp),
                )
                Box(Modifier.height(24.dp))
                Text(
                    "Нет связи с сервером",
                    color = PismoColors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Box(Modifier.height(6.dp))
                Text(
                    "Переподключение…",
                    color = PismoColors.TextMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
