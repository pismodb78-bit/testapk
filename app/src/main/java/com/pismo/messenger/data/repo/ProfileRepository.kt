package com.pismo.messenger.data.repo

import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.db.str
import com.pismo.messenger.data.model.UserProfile

/**
 * Профиль пользователя, аватар и баннер — порт ProfileRepository.cs и
 * AvatarStore.cs. Картинки лежат в users.avatar_data / users.banner_data.
 */
object ProfileRepository {

    /** Аватары меняются редко — держим в памяти, чтобы не дёргать БД на каждый список. */
    private val avatarCache = HashMap<Int, ByteArray?>()

    suspend fun load(userId: Int): UserProfile? = runCatching {
        Db.queryFirst(
            "SELECT Name, Surname, login, about, social_links FROM users WHERE id=?", userId
        ) { rs ->
            UserProfile(
                id = userId,
                name = rs.str("Name"),
                surname = rs.str("Surname"),
                login = rs.str("login"),
                about = rs.getString("about").orEmpty(),
                socialLinks = rs.getString("social_links").orEmpty(),
            )
        }
    }.getOrElse {
        // На базах без колонок about/social_links берём урезанный вариант.
        Db.queryFirst("SELECT Name, Surname, login FROM users WHERE id=?", userId) { rs ->
            UserProfile(userId, rs.str("Name"), rs.str("Surname"), rs.str("login"), "", "")
        }
    }

    suspend fun isLoginTaken(login: String, exceptId: Int): Boolean =
        Db.scalarInt("SELECT COUNT(*) FROM users WHERE login=? AND id<>?", login, exceptId) > 0

    suspend fun save(profile: UserProfile): Boolean {
        val full = runCatching {
            Db.exec(
                "UPDATE users SET Name=?, Surname=?, login=?, about=?, social_links=? WHERE id=?",
                profile.name, profile.surname, profile.login,
                profile.about, profile.socialLinks, profile.id
            )
        }
        if (full.isSuccess) return true

        // Колонок about/social_links может не быть — сохраняем что можем.
        return runCatching {
            Db.exec(
                "UPDATE users SET Name=?, Surname=?, login=? WHERE id=?",
                profile.name, profile.surname, profile.login, profile.id
            )
        }.isSuccess
    }

    // ── Аватар ────────────────────────────────────────────────────────

    suspend fun avatar(userId: Int): ByteArray? {
        avatarCache[userId]?.let { return it }
        if (avatarCache.containsKey(userId)) return null   // знаем, что аватара нет

        val data = runCatching {
            Db.scalarBytes("SELECT avatar_data FROM users WHERE id=?", userId)
        }.getOrNull()
        avatarCache[userId] = data
        return data
    }

    /**
     * Пакетная загрузка аватарок одним запросом.
     *
     * Без неё список из полусотни диалогов давал бы полсотни отдельных
     * обращений к удалённой базе — на мобильной сети это секунды ожидания
     * вместо одного round-trip.
     */
    suspend fun prefetchAvatars(userIds: Collection<Int>) {
        val missing = userIds.distinct().filter { !avatarCache.containsKey(it) }
        if (missing.isEmpty()) return

        runCatching {
            Db.use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT id, avatar_data FROM users WHERE id IN (${missing.joinToString(",")})"
                    ).use { rs ->
                        while (rs.next()) {
                            avatarCache[rs.getInt("id")] = rs.getBytes("avatar_data")
                        }
                    }
                }
            }
        }
        // Тем, у кого колонки нет или строка не вернулась, ставим null —
        // иначе на каждый кадр отрисовки уходил бы повторный запрос.
        missing.forEach { avatarCache.putIfAbsent(it, null) }
    }

    /** Синхронное чтение из памяти — для отрисовки без обращения к БД. */
    fun cachedAvatar(userId: Int): ByteArray? = avatarCache[userId]

    suspend fun setAvatar(data: ByteArray?): Boolean {
        val id = UserSession.effectiveId
        val ok = runCatching {
            Db.exec("UPDATE users SET avatar_data=? WHERE id=?", data, id)
        }.isSuccess
        if (ok) avatarCache[id] = data
        return ok
    }

    suspend fun banner(userId: Int): ByteArray? = runCatching {
        Db.scalarBytes("SELECT banner_data FROM users WHERE id=?", userId)
    }.getOrNull()

    suspend fun setBanner(data: ByteArray?): Boolean = runCatching {
        Db.exec("UPDATE users SET banner_data=? WHERE id=?", data, UserSession.effectiveId)
    }.isSuccess

    fun clearAvatarCache() = avatarCache.clear()
}
