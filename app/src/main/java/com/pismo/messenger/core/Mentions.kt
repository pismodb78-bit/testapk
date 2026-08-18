package com.pismo.messenger.core

/**
 * Разбор @упоминаний — порт MentionsMe и ResolveMentionedUserIds из
 * ServersForm.cs.
 *
 * Правила должны совпадать с ПК побуквенно: упоминание уходит в базу
 * обычным текстом, и оба клиента разбирают одну и ту же строку. Разойдись
 * условия — и сообщение, подсвеченное на компьютере, на телефоне выглядело
 * бы рядовым (или наоборот).
 *
 * Общими считаются @все, @all, @everyone, @here и @здесь; личными — @логин и
 * @название-роли.
 */
object Mentions {

    private val ALL_TOKENS = setOf("все", "all", "everyone", "here", "здесь")

    /** Все токены после «@» в тексте, приведённые к нижнему регистру. */
    fun tokens(text: String): Set<String> {
        if (text.isEmpty() || '@' !in text) return emptySet()
        // Тот же шаблон, что на ПК: @ и всё до пробела или следующей собаки.
        // Хвостовая пунктуация отбрасывается — «@petrov,» это тоже обращение.
        return Regex("@([^\\s@]+)").findAll(text)
            .map { it.groupValues[1].lowercase().trim('.', ',', '!', '?', ':') }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** Упоминание, адресованное всем участникам. */
    fun mentionsEveryone(text: String): Boolean = tokens(text).any { it in ALL_TOKENS }

    /**
     * Упоминают ли здесь меня. [myLogin] и [myRoleName] пустыми быть могут —
     * тогда соответствующая проверка просто не срабатывает.
     */
    fun mentionsMe(text: String, myLogin: String, myRoleName: String): Boolean {
        if (text.isEmpty()) return false
        val t = tokens(text)
        if (t.isEmpty()) return false
        if (t.any { it in ALL_TOKENS }) return true
        if (myLogin.isNotBlank() && myLogin.lowercase() in t) return true
        if (myRoleName.isNotBlank() && myRoleName.lowercase() in t) return true
        return false
    }

    /**
     * Границы всех @упоминаний в тексте — чтобы подсветить их при отрисовке.
     * Возвращает пары «начало, конец» в координатах исходной строки.
     */
    fun spans(text: String): List<IntRange> =
        if (text.isEmpty() || '@' !in text) emptyList()
        else Regex("@([^\\s@]+)").findAll(text).map { it.range }.toList()
}
