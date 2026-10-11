package com.pismo.messenger.ui.servers

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import com.pismo.messenger.data.ServerMemory
import com.pismo.messenger.data.repo.GroupRepository
import com.pismo.messenger.data.repo.ServerRepository
import com.pismo.messenger.net.SignalingClient
import com.pismo.messenger.ui.theme.PismoColors

/**
 * Сообщение о бане или исключении — с сервера или из группы.
 *
 * Живёт в корне приложения, рядом с окном входящего звонка, и по той же
 * причине: выгнать могут на любом экране. Раньше про это знал только
 * список серверов, и то строкой на пустом месте — если человек в этот
 * момент читал канал или сидел в другой вкладке, он не узнавал ничего,
 * просто переставало отправляться. ПК показывает окно, теперь и телефон.
 *
 * [onLeaveServerScreens] уводит с экранов сервера: оставаться в канале,
 * откуда тебя выгнали, незачем.
 */
@Composable
fun ServerKickDialog(onLeaveServerScreens: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    var banned by remember { mutableStateOf(false) }
    var isGroup by remember { mutableStateOf(false) }
    var targetId by remember { mutableIntStateOf(0) }
    var targetName by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val listener: (String, Int, Int, String) -> Unit = { type, _, session, payload ->
            // Релей шлёт это адресно: дошло — значит про меня.
            if (type == "server_kick" || type == "group_kick") {
                isGroup = type == "group_kick"
                targetId = session
                banned = payload == "ban"
                targetName = ""
                show = true
            }
        }
        SignalingClient.addListener(listener)
        onDispose { SignalingClient.removeListener(listener) }
    }

    LaunchedEffect(show, targetId, isGroup) {
        if (!show || targetId <= 0) return@LaunchedEffect
        targetName = if (isGroup) {
            runCatching { GroupRepository.groupName(targetId) }.getOrNull().orEmpty()
        } else {
            // Сервер уже не наш — чистим его из памяти вкладки, чтобы он не
            // всплыл в рельсе при следующем открытии.
            ServerMemory.invalidate(targetId)
            runCatching { ServerRepository.serverInfo(targetId)?.first }.getOrNull().orEmpty()
        }
    }

    if (!show) return

    fun close() {
        show = false
        onLeaveServerScreens()
    }

    val what = if (isGroup) "группе" else "сервере"
    val where = if (targetName.isBlank()) what else "$what «$targetName»"
    AlertDialog(
        onDismissRequest = { close() },
        containerColor = PismoColors.BgSidebar,
        title = {
            Text(
                if (banned) "Вас забанили" else "Вас исключили",
                color = PismoColors.TextPrimary,
            )
        },
        text = {
            Text(
                when {
                    banned && isGroup ->
                        "Вас забанили в $where. Обратно не добавят, пока бан не снимут."
                    banned ->
                        "Вас забанили на $where. Вернуться по ID не получится, " +
                                "пока бан не снимут."
                    isGroup ->
                        "Вас исключили из $where. Вернуться можно, если вас добавят снова."
                    else ->
                        "Вас исключили с $where. Вернуться можно по ID сервера."
                },
                color = PismoColors.TextSecondary, fontSize = 13.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = { close() }) { Text("Понятно", color = PismoColors.Cyan) }
        },
    )
}
