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

    /**
     * Аватары меняются редко — держим в памяти, чтобы не дёргать БД на
     * каждый список.
     *
     * У кэша ОБЯЗАТЕЛЕН срок годности. Раньше его не было, и запись жила до
     * перезапуска приложения: сменивший аватар видел новый сразу (свою запись
     * мы перезаписываем сами), а все остальные — старый, пока не закроют
     * приложение. Со стороны это выглядело так, будто аватар вообще не
     * уходит на сервер, хотя в БД он лежал.
     */
    private const val AVATAR_TTL_MS = 3 * 60 * 1000L

    private val avatarCache = HashMap<Int, ByteArray?>()
    private val avatarLoadedAt = HashMap<Int, Long>()

    private fun isFresh(userId: Int): Boolean {
        val at = avatarLoadedAt[userId] ?: return false
        return System.currentTimeMillis() - at < AVATAR_TTL_MS
    }

    private fun remember(userId: Int, data: ByteArray?) {
        avatarCache[userId] = data
        avatarLoadedAt[userId] = System.currentTimeMillis()
    }

    /** Забыть аватар пользователя — при следующем показе он перечитается. */
    fun invalidateAvatar(userId: Int) {
        avatarCache.remove(userId)
        avatarLoadedAt.remove(userId)
    }

    /**
     * Память профилей.
     *
     * Профиль — это четыре текстовых поля, которые почти не меняются, но за
     * каждым открытием чужой карточки шёл запрос к удалённой базе, и
     * карточка секунду висела пустой. Держим последнее известное значение и
     * показываем его сразу, а свежее подтягиваем фоном.
     */
    private val profileCache = HashMap<Int, UserProfile>()

    fun cachedProfile(userId: Int): UserProfile? = synchronized(profileCache) {
        profileCache[userId]
    }

    fun clearProfileCache() = synchronized(profileCache) { profileCache.clear() }

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
        runCatching {
            Db.queryFirst("SELECT Name, Surname, login FROM users WHERE id=?", userId) { rs ->
                UserProfile(userId, rs.str("Name"), rs.str("Surname"), rs.str("login"), "", "")
            }
        // Связь моргнула — отдаём последнее известное, а не пустоту.
        }.getOrNull() ?: cachedProfile(userId)
    }?.also { loaded ->
        synchronized(profileCache) { profileCache[userId] = loaded }
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
        if (full.isSuccess) {
            synchronized(profileCache) { profileCache[profile.id] = profile }
            return true
        }

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
        if (avatarCache.containsKey(userId) && isFresh(userId)) return avatarCache[userId]

        val result = runCatching {
            Db.scalarBytes("SELECT avatar_data FROM users WHERE id=?", userId)
        }
        // Обрыв связи — не повод забывать то, что уже нарисовано: отдаём
        // старое изображение и пробуем ещё раз на следующем показе.
        val data = result.getOrElse { return avatarCache[userId] }
        remember(userId, data)
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
        val missing = userIds.distinct().filter { !avatarCache.containsKey(it) || !isFresh(it) }
        if (missing.isEmpty()) return

        runCatching {
            Db.use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT id, avatar_data FROM users WHERE id IN (${missing.joinToString(",")})"
                    ).use { rs ->
                        while (rs.next()) {
                            remember(rs.getInt("id"), rs.getBytes("avatar_data"))
                        }
                    }
                }
            }
        }
        // Тем, у кого колонки нет или строка не вернулась, ставим null —
        // иначе на каждый кадр отрисовки уходил бы повторный запрос.
        missing.forEach { if (!isFresh(it)) remember(it, avatarCache[it]) }
    }

    /** Синхронное чтение из памяти — для отрисовки без обращения к БД. */
    fun cachedAvatar(userId: Int): ByteArray? = avatarCache[userId]

    suspend fun setAvatar(data: ByteArray?): Boolean {
        val id = UserSession.effectiveId
        val ok = runCatching {
            Db.exec("UPDATE users SET avatar_data=? WHERE id=?", data, id)
        }.isSuccess
        if (ok) remember(id, data)
        return ok
    }

    private val bannerCache = HashMap<Int, ByteArray?>()

    /**
     * Баннер профиля. Кэшируем так же, как аватар: это картинка, и без
     * памяти она перекачивалась при каждом открытии карточки.
     */
    suspend fun banner(userId: Int): ByteArray? {
        synchronized(bannerCache) { if (bannerCache.containsKey(userId)) return bannerCache[userId] }
        val data = runCatching {
            Db.scalarBytes("SELECT banner_data FROM users WHERE id=?", userId)
        }.getOrElse { return synchronized(bannerCache) { bannerCache[userId] } }
        synchronized(bannerCache) { bannerCache[userId] = data }
        return data
    }

    suspend fun setBanner(data: ByteArray?): Boolean = runCatching {
        Db.exec("UPDATE users SET banner_data=? WHERE id=?", data, UserSession.effectiveId)
    }.isSuccess.also { ok ->
        if (ok) synchronized(bannerCache) { bannerCache[UserSession.effectiveId] = data }
    }

    fun clearAvatarCache() {
        avatarCache.clear()
        avatarLoadedAt.clear()
        synchronized(bannerCache) { bannerCache.clear() }
        clearProfileCache()
    }
}
