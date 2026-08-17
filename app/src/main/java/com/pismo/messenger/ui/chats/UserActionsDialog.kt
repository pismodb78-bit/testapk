package com.pismo.messenger.ui.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.model.Conversation
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.FriendsRepository
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.launch

/**
 * Действия над собеседником по долгому нажатию — порт контекстного меню
 * карточки диалога из MainForm_MessageActions.cs
 * (AttachConversationContextMenu) и админского меню из AddAdminUserCard.
 */
@Composable
fun UserActionsDialog(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var blocked by remember { mutableStateOf(false) }
    var relation by remember { mutableStateOf(FriendsRepository.Relation.NONE) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(conversation.userId) {
        runCatching {
            blocked = ChatRepository.isBlocked(UserSession.effectiveId, conversation.userId)
            relation = FriendsRepository.relation(UserSession.effectiveId, conversation.userId)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = PismoColors.BgSidebar,
            title = { Text("Очистить переписку?", color = PismoColors.TextPrimary) },
            text = {
                Text(
                    "Вся переписка с «${conversation.name}» будет удалена у обеих сторон. " +
                            "Это действие нельзя отменить.",
                    color = PismoColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { ChatRepository.deleteConversation(conversation.userId) }
                        confirmClear = false
                        onChanged()
                    }
                }) { Text("Удалить", color = PismoColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Отмена", color = PismoColors.TextMuted)
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PismoColors.BgSidebar,
        title = { Text(conversation.name, color = PismoColors.TextPrimary) },
        text = {
            Column {
                Action("💬  Написать", onClick = onOpenChat)

                when (relation) {
                    FriendsRepository.Relation.NONE -> Action("➕  Добавить в друзья") {
                        scope.launch {
                            FriendsRepository.sendRequest(conversation.userId)
                            onChanged()
                        }
                    }
                    FriendsRepository.Relation.INCOMING_PENDING -> Action("✅  Принять заявку") {
                        scope.launch {
                            FriendsRepository.accept(conversation.userId)
                            onChanged()
                        }
                    }
                    FriendsRepository.Relation.OUTGOING_PENDING ->
                        Text("Заявка отправлена, ожидает ответа.",
                            color = PismoColors.TextMuted, fontSize = 13.sp)
                    FriendsRepository.Relation.FRIEND -> Action("➖  Удалить из друзей") {
                        scope.launch {
                            FriendsRepository.remove(conversation.userId)
                            onChanged()
                        }
                    }
                }

                Action(
                    if (blocked) "✅  Разблокировать" else "🚫  Заблокировать",
                    color = if (blocked) PismoColors.Green else PismoColors.Yellow,
                ) {
                    scope.launch {
                        if (blocked) ChatRepository.unblock(conversation.userId)
                        else ChatRepository.block(conversation.userId)
                        onChanged()
                    }
                }

                Action("🗑  Очистить переписку", color = PismoColors.Red) {
                    confirmClear = true
                }

                // Режим «войти за пользователя» — только для администратора,
                // ровно как DoImpersonate на ПК.
                if (UserSession.isAdmin && conversation.userId != UserSession.userId) {
                    Action("👤  Войти за пользователя", color = PismoColors.Cyan) {
                        UserSession.impersonatedId = conversation.userId
                        UserSession.impersonatedName = conversation.name
                        onChanged()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = PismoColors.TextMuted) }
        },
    )
}

@Composable
private fun Action(
    label: String,
    color: Color = PismoColors.TextPrimary,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier) {
        Text(label, color = color, fontSize = 15.sp)
    }
}
