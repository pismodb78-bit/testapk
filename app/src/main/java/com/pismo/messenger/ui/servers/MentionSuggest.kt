package com.pismo.messenger.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.data.repo.ServerRepository
import com.pismo.messenger.ui.components.parseHexColor
import com.pismo.messenger.ui.theme.PismoColors

/** Один вариант подсказки: что подставить, что показать и чем это является. */
data class MentionOption(
    val token: String,
    val display: String,
    val description: String,
    val colorHex: String? = null,
)

/**
 * Разбор строки ввода: открыто ли сейчас упоминание и что уже набрано после «@».
 *
 * Правила те же, что на ПК (OnChatInputKeyDown/BuildMentionItems): подсказка
 * живёт, пока после «@» идут «словесные» символы, и гаснет на пробеле —
 * иначе она висела бы над половиной сообщения. «@» считается началом
 * упоминания только в начале строки или после пробела, чтобы почтовый адрес
 * не превращался в обращение к участнику.
 */
fun mentionPrefix(text: String, cursor: Int): Pair<Int, String>? {
    if (cursor <= 0 || cursor > text.length) return null
    var i = cursor - 1
    while (i >= 0) {
        val ch = text[i]
        if (ch == '@') {
            val before = if (i == 0) ' ' else text[i - 1]
            if (!before.isWhitespace()) return null
            return i to text.substring(i + 1, cursor).lowercase()
        }
        if (ch.isWhitespace()) return null
        i--
    }
    return null
}

/**
 * Подставляет выбранное упоминание вместо набранного «@...».
 * Возвращает новый текст и позицию курсора после него.
 */
fun applyMention(text: String, atPos: Int, cursor: Int, token: String): Pair<String, Int> {
    val inserted = "@$token "
    val head = text.substring(0, atPos)
    val tail = text.substring(cursor)
    return (head + inserted + tail) to (head.length + inserted.length)
}

/**
 * Список подсказок над строкой ввода — порт всплывающего окна @упоминаний
 * из ServersForm.
 *
 * Состав тот же и в том же порядке: @everyone, @here, роли сервера,
 * участники. Совпадение важно не для вида: упоминание уходит в базу текстом,
 * и разбирает его потом общий код уведомлений — набор того, что вообще можно
 * упомянуть, обязан быть одинаковым на обоих клиентах.
 */
@Composable
fun MentionSuggestions(
    serverId: Int,
    partial: String,
    onPick: (MentionOption) -> Unit,
) {
    var all by remember(serverId) { mutableStateOf<List<MentionOption>>(emptyList()) }

    LaunchedEffect(serverId) {
        val list = ArrayList<MentionOption>()
        list.add(MentionOption("everyone", "@everyone", "Оповестить всех участников канала"))
        list.add(MentionOption("here", "@here", "Оповестить тех, кто сейчас в сети"))
        runCatching {
            ServerRepository.roles(serverId).forEach { r ->
                if (r.name.isNotBlank()) {
                    list.add(MentionOption(r.name, "@${r.name}", "Оповестить роль", r.colorHex))
                }
            }
        }
        runCatching {
            ServerRepository.members(serverId).forEach { m ->
                if (m.login.isNotBlank()) {
                    val display =
                        if (m.name.isBlank() || m.name == m.login) "@${m.login}"
                        else "@${m.login} (${m.name})"
                    list.add(MentionOption(m.login, display, "Участник"))
                }
            }
        }
        all = list
    }

    val shown = remember(all, partial) {
        if (partial.isEmpty()) all
        else all.filter {
            it.token.lowercase().contains(partial) || it.display.lowercase().contains(partial)
        }
    }
    if (shown.isEmpty()) return

    LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 190.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PismoColors.BgElevated)
            .padding(vertical = 4.dp),
    ) {
        items(shown, key = { it.display }) { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(option) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(0.dp).weight(1f)) {
                    Text(
                        option.display,
                        // Роль подсвечивается своим цветом — так же, как она
                        // будет выглядеть в списке участников.
                        color = option.colorHex?.let { parseHexColor(it) }
                            ?: PismoColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(option.description, color = PismoColors.TextMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}
