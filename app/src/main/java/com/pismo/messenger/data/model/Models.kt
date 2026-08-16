package com.pismo.messenger.data.model

/**
 * Область сообщения. Числовые значения обязаны совпадать с ПК-версией:
 * колонка `scope` в message_reactions / pinned_messages / message_edits.
 */
enum class Scope(val db: Int) {
    DM(0), GROUP(1), SERVER(2);

    val table: String
        get() = when (this) {
            DM -> "messages"
            GROUP -> "group_messages"
            SERVER -> "server_messages"
        }

    companion object {
        fun of(db: Int): Scope = entries.firstOrNull { it.db == db } ?: DM
    }
}

data class UserBrief(
    val id: Int,
    val name: String,
    val login: String,
    val role: String = "",
)

/** Строка списка личных диалогов. */
data class Conversation(
    val userId: Int,
    val name: String,
    val lastMessage: String,
    val lastTimeMs: Long?,
    val unread: Int,
)

/** Строка списка групп. */
data class GroupSummary(
    val id: Int,
    val name: String,
    val lastMessage: String,
    val memberCount: Int,
    val avatarColorHex: String,
    val lastTimeMs: Long?,
    val unread: Int = 0,
)

/**
 * Сообщение в любом из трёх контекстов. BLOB-ы намеренно не тянутся вместе
 * со списком — только флаги наличия; сами байты догружаются по месту через
 * MediaCache (так же устроен MainForm_CachePatch.cs на ПК).
 */
data class ChatMessage(
    val id: Int,
    val senderId: Int,
    val senderName: String,
    val text: String,
    val createdAtMs: Long,
    val replyToId: Int = 0,
    val isDeleted: Boolean = false,
    val isEdited: Boolean = false,
    val hasImage: Boolean = false,
    val hasAudio: Boolean = false,
    val hasVideo: Boolean = false,
    val hasFile: Boolean = false,
    val fileName: String? = null,
    val scope: Scope = Scope.DM,
    val isRead: Boolean = true,
    val reactions: List<ReactionSummary> = emptyList(),
    val isPinned: Boolean = false,
) {
    val isMine: Boolean
        get() = senderId == com.pismo.messenger.core.UserSession.effectiveId

    val hasAnyMedia: Boolean
        get() = hasImage || hasAudio || hasVideo || hasFile
}

/** Мини-цитата ответа внутри пузыря. */
data class ReplyQuote(
    val messageId: Int,
    val sender: String,
    val text: String,
)

data class GroupMember(
    val userId: Int,
    val name: String,
    val isAdmin: Boolean,
)

// ── Серверы (Discord-стиль) ────────────────────────────────────────────

data class ServerSummary(
    val id: Int,
    val name: String,
    val ownerId: Int,
    val unread: Int = 0,
    val mentions: Int = 0,
)

enum class ChannelType { TEXT, VOICE;
    companion object {
        fun of(s: String?) = if (s.equals("voice", true)) VOICE else TEXT
    }
    val db: String get() = if (this == VOICE) "voice" else "text"
}

data class ServerChannel(
    val id: Int,
    val serverId: Int,
    val name: String,
    val type: ChannelType,
    val position: Int = 0,
    /** 0 — без ограничения вместимости (миграция 14). */
    val userLimit: Int = 0,
    val unread: Int = 0,
    val mentions: Int = 0,
)

data class ServerRole(
    val id: Int,
    val name: String,
    val colorHex: String,
    val canBan: Boolean,
    val canKick: Boolean,
    val canMute: Boolean,
    val canManage: Boolean,
    val position: Int = 0,
)

data class ServerMemberRow(
    val userId: Int,
    val name: String,
    val login: String,
    val roleId: Int?,
    val roleName: String,
    val isOwner: Boolean,
)

/** Права текущего пользователя на сервере. */
data class ServerPermissions(
    val isOwner: Boolean = false,
    val canBan: Boolean = false,
    val canKick: Boolean = false,
    val canMute: Boolean = false,
    val canManage: Boolean = false,
    val mutedNotifications: Boolean = false,
) {
    val isAdminLike: Boolean get() = isOwner || canManage
}

/** Участник голосового канала «в эфире» (voice_presence). */
data class VoiceParticipant(
    val userId: Int,
    val name: String,
    val streaming: Boolean = false,
    val micMuted: Boolean = false,
    val deafened: Boolean = false,
)

// ── Звонки ─────────────────────────────────────────────────────────────

data class CallSessionRow(
    val id: Int,
    val callerId: Int,
    val callerName: String,
    val calleeId: Int?,
    val groupId: Int?,
    val status: String,
    val hasVideo: Boolean,
)

// ── Прочее ─────────────────────────────────────────────────────────────

data class ReactionSummary(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

data class FriendEntry(
    val userId: Int,
    val name: String,
    val login: String,
)

/** 0 = все, 1 = только друзья (user_prefs.dm_privacy). */
enum class DmPrivacy(val db: Int) { EVERYONE(0), FRIENDS_ONLY(1);
    companion object { fun of(v: Int) = if (v == 1) FRIENDS_ONLY else EVERYONE }
}

data class UserProfile(
    val id: Int,
    val name: String,
    val surname: String,
    val login: String,
    val about: String,
    val socialLinks: String,
)

/** Онлайн-статус: сколько секунд назад пользователь был виден/активен. */
data class Presence(
    val userId: Int,
    val seenAgoSec: Int,
    val activeAgoSec: Int,
) {
    val isOnline: Boolean get() = seenAgoSec in 0..60
    val isIdle: Boolean get() = isOnline && activeAgoSec > 300
}

/** Вложение, подготовленное к отправке. */
enum class AttachKind { IMAGE, GIF, FILE, AUDIO, VIDEO_CIRCLE }

data class PendingAttachment(
    val data: ByteArray,
    val fileName: String,
    val kind: AttachKind,
) {
    override fun equals(other: Any?): Boolean =
        other is PendingAttachment && fileName == other.fileName &&
                kind == other.kind && data.contentEquals(other.data)

    override fun hashCode(): Int =
        31 * (31 * data.contentHashCode() + fileName.hashCode()) + kind.hashCode()
}
