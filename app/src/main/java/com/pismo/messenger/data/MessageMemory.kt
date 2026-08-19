package com.pismo.messenger.data

import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.ReactionSummary
import com.pismo.messenger.data.model.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Последняя показанная лента каждого чата.
 *
 * Зачем. Открытие переписки всегда начиналось с пустого экрана и кружка:
 * лента едет из удалённой базы, и это заметная пауза на мобильной сети —
 * особенно если из чата вышли и тут же вернулись, хотя все сообщения были
 * на экране секунду назад. ПК от этого не страдает: там окно чата не
 * уничтожается при переключении, лента остаётся нарисованной, а PollTick
 * лишь дописывает новое.
 *
 * На Android экран уходит из композиции целиком, поэтому запомненную ленту
 * приходится держать отдельно. Показываем её сразу при открытии, а свежую
 * подтягиваем фоном и подменяем, когда она приедет.
 *
 * Что здесь НЕ хранится: байты вложений. Для них есть MediaCache со своим
 * диском и лимитом; складывать сюда ещё и картинки — верный способ съесть
 * всю память списком из полусотни чатов.
 *
 * ЗА ПРЕДЕЛАМИ ПРОЦЕССА память продолжается на диске (ChatDiskCache): после
 * свайпа из недавних приложение начинает с нуля, и без диска первый заход в
 * любой чат снова упирался бы в кружок. Здесь — быстрый слой, там — long-term;
 * промах этого слоя молча дочитывается оттуда.
 *
 * Чистится при смене пользователя — вместе с диском: чужая переписка после
 * входа под другим логином не должна мелькнуть даже на кадр.
 */
object MessageMemory {

    /**
     * Сколько чатов помним. Открытых подряд переписок редко бывает больше
     * десятка, а каждая — это сотня расшифрованных строк.
     */
    private const val MAX_CHATS = 12

    private class Entry(
        val messages: List<ChatMessage>,
        val reactions: Map<Int, List<ReactionSummary>>,
        val userId: Int,
    )

    // LinkedHashMap с порядком обращения — готовый LRU без своей возни.
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > MAX_CHATS
    }

    private fun key(scope: Scope, targetId: Int) = "${scope.name}/$targetId"

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Что показать сразу при открытии чата; null — ничего не помним. */
    fun peek(scope: Scope, targetId: Int): Pair<List<ChatMessage>, Map<Int, List<ReactionSummary>>>? {
        synchronized(cache) {
            val e = cache[key(scope, targetId)]
            // Запись, снятая под другим пользователем, не наша.
            if (e != null && e.userId == UserSession.effectiveId) {
                return e.messages to e.reactions
            }
        }

        // В памяти пусто — значит, приложение только запустили. Дочитываем с
        // диска и заодно кладём в память, чтобы второй заход был мгновенным.
        //
        // Чтение синхронное, прямо в композиции: это один небольшой файл, и
        // ради него городить асинхронную загрузку бессмысленно — она вернула
        // бы ленту кадром позже, то есть ровно с тем миганием пустого экрана,
        // от которого кеш и заводили.
        val fromDisk = ChatDiskCache.load(scope, targetId) ?: return null
        synchronized(cache) {
            cache[key(scope, targetId)] =
                Entry(fromDisk.first, fromDisk.second, UserSession.effectiveId)
        }
        return fromDisk
    }

    fun put(
        scope: Scope,
        targetId: Int,
        messages: List<ChatMessage>,
        reactions: Map<Int, List<ReactionSummary>>,
    ) {
        if (messages.isEmpty()) return
        synchronized(cache) {
            cache[key(scope, targetId)] = Entry(messages, reactions, UserSession.effectiveId)
        }
        // На диск — фоном: запись идёт после каждого опроса чата, и держать
        // ради неё поток интерфейса незачем.
        io.launch { ChatDiskCache.save(scope, targetId, messages, reactions) }
    }

    /** Забыть один чат — например, после очистки переписки. */
    fun invalidate(scope: Scope, targetId: Int) {
        synchronized(cache) { cache.remove(key(scope, targetId)) }
        io.launch { ChatDiskCache.invalidate(scope, targetId) }
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
        io.launch { ChatDiskCache.clear() }
    }
}
