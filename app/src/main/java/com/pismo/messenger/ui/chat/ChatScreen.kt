package com.pismo.messenger.ui.chat

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pismo.messenger.core.PresenceReporter
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.core.ellipsize
import com.pismo.messenger.core.fileBadge
import com.pismo.messenger.core.fileColor
import com.pismo.messenger.core.formatDateSeparator
import com.pismo.messenger.core.formatDuration
import com.pismo.messenger.core.formatTime
import com.pismo.messenger.data.MediaCache
import com.pismo.messenger.data.MessageMemory
import com.pismo.messenger.data.db.Db
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.Conversation
import com.pismo.messenger.data.model.Presence
import com.pismo.messenger.data.model.ReactionSummary
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.model.headerText
import com.pismo.messenger.data.Transfers
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.PinsRepository
import com.pismo.messenger.data.repo.PresenceRepository
import com.pismo.messenger.data.repo.ReactionsRepository
import com.pismo.messenger.media.Sounds
import com.pismo.messenger.media.WavPlayer
import com.pismo.messenger.media.WavRecorder
import com.pismo.messenger.net.SignalingClient
import com.pismo.messenger.ui.components.DateSeparator
import com.pismo.messenger.ui.components.FileBadge
import com.pismo.messenger.ui.chats.UserActionsDialog
import com.pismo.messenger.ui.components.GroupAvatar
import com.pismo.messenger.ui.components.LetterAvatar
import com.pismo.messenger.ui.components.UserAvatar
import com.pismo.messenger.ui.components.VoiceDraftBar
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Потолок длительности голосового — 3 минуты. Запись целиком держится в
 * памяти и уходит в БД одним BLOB'ом, поэтому верхняя граница обязательна.
 */
/**
 * Потолок длительности голосового. internal, а не private: тем же числом
 * ограничены голосовые в каналах серверов — пусть оно будет одно на всех.
 */
internal const val VOICE_MAX_SECONDS = 180

/**
 * Потолок размера вложения.
 *
 * Сверху его задаёт сервер: max_allowed_packet на нашей базе — около
 * 256 МБ, больше одной строкой не примут. Снизу — сам телефон: файл
 * читается в память целиком, а JDBC и шифрование делают ещё копии, так что
 * на 250 МБ уйдёт под гигабайт кучи. Поэтому берём заведомо проходимые
 * 128 МБ и говорим об этом вслух, а не отбрасываем выбор молча, как было.
 */
/**
 * Потолок вложения — 200 МБ, как на ПК после его последних правок. Сам файл
 * уходит в базу порциями по 4 МБ, поэтому упирается он не в пакет, а в
 * терпение: на мобильной сети это минуты.
 */
internal const val MAX_ATTACH_BYTES = 200L * 1024 * 1024

/** Насколько расширяется страница за одну догрузку старых сообщений. */
private const val PAGE_STEP = 40

/** Пауза между подгрузками старых сообщений — как 800 мс на ПК. */
// Пауза между подгрузками. Была 800 мс: каждая стоила похода к серверу, и
// частые срабатывания сливались в рывки. Теперь порция чаще всего уже
// привезена заранее, и держать человека дольше незачем.
private const val PAGE_COOLDOWN_MS = 200L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    targetId: Int,
    title: String,
    isGroup: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scopeKind = if (isGroup) Scope.GROUP else Scope.DM

    // Лента берётся из памяти СИНХРОННО, до первого кадра. Раньше открытие
    // чата всегда начиналось с пустого экрана и кружка, даже если из него
    // вышли секунду назад: экран уходит из композиции целиком, и всё
    // грузилось заново. На ПК окно чата не уничтожается вовсе, поэтому там
    // такого нет.
    val remembered = remember(targetId, isGroup) {
        MessageMemory.peek(if (isGroup) Scope.GROUP else Scope.DM, targetId)
    }
    var messages by remember(targetId) {
        mutableStateOf(remembered?.first ?: emptyList())
    }
    var reactions by remember(targetId) {
        mutableStateOf(remembered?.second ?: emptyMap<Int, List<ReactionSummary>>())
    }
    var input by remember { mutableStateOf("") }
    // Кружок нужен, только когда показать нечего.
    var loading by remember(targetId) { mutableStateOf(remembered == null) }
    var sending by remember { mutableStateOf(false) }
    /**
     * Что мы уже знаем о переписке НА СЕРВЕРЕ: самый большой id, сколько
     * всего сообщений и сколько моих ещё не прочитано. Опрос сравнивает
     * свежие ответы именно с этим.
     *
     * ЗДЕСЬ И БЫЛА ПОСТОЯННАЯ НАГРУЗКА. Раньше вместо этих трёх чисел жило
     * одно — размер загруженной СТРАНИЦЫ (сорок сообщений), — а сравнивали
     * его с общим числом сообщений в переписке. На любой переписке длиннее
     * страницы они не совпадают никогда, поэтому «что-то изменилось»
     * срабатывало КАЖДЫЕ 2,5 СЕКУНДЫ и лента перечитывалась целиком без
     * всякого повода. Отсюда же и «долистал до начала чата — нагрузка
     * спала»: когда страница дорастала до всей истории, числа наконец
     * сходились и опрос затихал.
     *
     * −1 значит «ещё не спрашивали»: первое измерение ничего не сравнивает,
     * иначе открытие чата сразу тянуло бы вторую перезагрузку.
     */
    var lastMaxId by remember(targetId) { mutableIntStateOf(-1) }
    var lastTotal by remember(targetId) { mutableIntStateOf(-1) }
    var lastUnreadToPartner by remember(targetId) { mutableIntStateOf(-1) }
    var tick by remember(targetId) { mutableIntStateOf(0) }
    /** Отпечаток закрепов на прошлом опросе; null — ещё не мерили. */
    var lastPins by remember(targetId) { mutableStateOf<String?>(null) }

    // Режимы ответа и редактирования — как панель над строкой ввода на ПК.
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }

    // Блокировки: чат переходит в режим «только чтение».
    var iBlocked by remember { mutableStateOf(false) }
    var theyBlocked by remember { mutableStateOf(false) }

    // ЛЕНТА ПЕРЕВЁРНУТА — см. reverseLayout у LazyColumn ниже. Нулевой
    // элемент это самое свежее сообщение, и стоит он у НИЖНЕГО края. Поэтому
    // начальную позицию задавать больше не нужно: чат открывается на
    // последнем сообщении сам.
    //
    // И это же лечит «телепорт на стыках прогруженного». Обычный список при
    // изменении данных держится за ПЕРВЫЙ ВИДИМЫЙ элемент, то есть за верхний
    // край окна, а порция старых сообщений ложится ВЫШЕ него — значит всё, что
    // над якорем, надо пересчитать и переложить. Любая мелочь в этой кладке
    // уводила взгляд: склеившаяся черта с датой (у самого старого сообщения
    // она была, а с приходом порции стала не нужна), картинка, вставшая в свой
    // настоящий размер вместо заготовки, потерянный после большого сдвига
    // ключ. Отсюда и дёрганье ровно на границах страниц.
    //
    // В перевёрнутом списке якорь стоит у нижнего края, старые сообщения
    // приходят в КОНЕЦ данных — то есть за верхний край экрана, — и
    // перекладывать над якорем нечего. Двигать взгляд попросту некуда.
    val listState = rememberLazyListState()
    // KeepListAboveKeyboard здесь больше не нужен: перевёрнутая лента прижата
    // к низу окна, и когда клавиатура поднимает нижний край, последние
    // сообщения поднимаются вместе с ним сами.

    val recorder = remember { WavRecorder(scope) }
    var recording by remember { mutableStateOf(false) }

    /**
     * Записанное, но ещё НЕ отправленное голосовое.
     *
     * Раньше остановка записи сразу отправляла её собеседнику: ни послушать
     * себя, ни передумать, ни перезаписать. Теперь записанное ждёт здесь, а
     * над строкой ввода появляется полоса — прослушать, перезаписать,
     * выбросить. Уходит оно обычной кнопкой отправки, вместе с текстом, если
     * его набрали.
     */
    var pendingVoice by remember(targetId) { mutableStateOf<ByteArray?>(null) }
    // Меню действий с собеседником — по нажатию на аватар/имя в шапке. Ровно то
    // же меню, что по долгому нажатию на чат в списке: раньше из самого чата к
    // нему было не добраться.
    var showUserActions by remember(targetId) { mutableStateOf(false) }
    // Управление группой прямо из переписки: раньше до него надо было выйти
    // в список чатов и нажать на группу там.
    var showGroupManage by remember(targetId) { mutableStateOf(false) }
    // Почему не ушло последнее сообщение. Раньше текстовая отправка глотала
    // отказ молча: поле очищалось в любом случае, и набранное пропадало без
    // следа — человек узнавал об этом, только не найдя сообщение в ленте.
    var sendError by remember(targetId) { mutableStateOf("") }
    // Счётчик правок оформления группы: по нему значок в шапке перечитывает
    // картинку. Забыть её из памяти мало — значок читает заново только когда
    // меняются ключи его эффекта.
    var groupLook by remember(targetId) { mutableIntStateOf(0) }
    var recordSeconds by remember { mutableStateOf(0) }
    var showCircleRecorder by remember { mutableStateOf(false) }
    var showGifPicker by remember { mutableStateOf(false) }
    // Свой лист вложений вместо системного выбора файлов: плитка галереи,
    // кружки с номерами, просмотр по нажатию. См. AttachSheet.
    var showAttach by remember { mutableStateOf(false) }
    // Открытый просмотр медиа: храним id сообщения, лента собирается из
    // загруженной переписки — из неё же работают переходы стрелками.
    var mediaViewerAt by remember(targetId) { mutableStateOf<Int?>(null) }

    // Режим множественного выделения — порт «Выбрано: N» с ПК.
    var selectMode by remember(targetId) { mutableStateOf(false) }
    var selectedIds by remember(targetId) { mutableStateOf(setOf<Int>()) }
    var forwardBatch by remember(targetId) { mutableStateOf<List<ForwardItem>>(emptyList()) }
    // Полноэкранное видео живёт на уровне экрана — переживает и прокрутку,
    // и поворот, при которых пузырь уничтожается.
    var fullscreenVideo by remember(targetId) { mutableStateOf<FullscreenVideo?>(null) }
    var showPins by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    // Статус собеседника в шапке — «в сети» / «бездействует N» / «был в сети N».
    // Стартуем с того, что уже знает общая память: список чатов опросил
    // статусы секунду назад, и заново ждать ответа базы, показывая пустой
    // подзаголовок, незачем.
    var peerPresence by remember(targetId) {
        mutableStateOf(PresenceRepository.cached(targetId))
    }
    var jumpNote by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    // Прикреплённый, но ещё не отправленный файл — аналог «подготовки к
    // отправке» на ПК.
    // Вложений может быть несколько: файлы выбираются пачкой и уходят
    // отдельными сообщениями — по одному файлу на строку, как того требует
    // схема базы. Подпись достаётся первому.
    var pending by remember(targetId) { mutableStateOf<List<PendingFile>>(emptyList()) }
    /** Сообщение, к которому только что прыгнули по цитате: мигает и гаснет. */
    var flashId by remember(targetId) { mutableIntStateOf(0) }

    // Сколько сообщений тянуть. Переход к дате расширяет страницу ровно так
    // же, как _dmLimit на ПК: лента грузится с конца, и без расширения
    // прыжок в прошлый месяц упирался бы в незагруженную историю.
    var pageLimit by remember(targetId) { mutableStateOf(0) }
    // Догрузка старых сообщений порциями — как постраничная лента на ПК.
    // 0 в pageLimit означает «размер по умолчанию», поэтому первую порцию
    // считаем от него же.
    var loadingOlder by remember(targetId) { mutableStateOf(false) }
    var noMoreOlder by remember(targetId) { mutableStateOf(false) }

    /*
     * Порция «про запас» — следующая страница, привезённая заранее.
     *
     * Пока человек читает только что приехавшие сообщения, канал до базы
     * свободен, и это лучшее время, чтобы съездить за следующей. Когда он
     * домотает доверху, она уже лежит готовой, вместе с картинками, и
     * подгрузка обходится БЕЗ похода к серверу.
     *
     * reserveBefore — курсор, от которого её брали: если лента успела
     * измениться, запас не подойдёт, и это видно по одному сравнению.
     */
    var reserve by remember(targetId) { mutableStateOf<List<ChatMessage>?>(null) }
    var reserveBefore by remember(targetId) { mutableIntStateOf(0) }
    var reserveBusy by remember(targetId) { mutableStateOf(false) }

    /**
     * Кто сейчас печатает в этом чате и когда об этом сообщили. Порт
     * индикатора «печатает…» с ПК: собственный статус уходит по вебсокету
     * не чаще раза в две секунды, чужой держится на экране четыре.
     */
    var typingName by remember(targetId) { mutableStateOf("") }
    var typingAt by remember(targetId) { mutableLongStateOf(0L) }
    var typingSentAt by remember(targetId) { mutableLongStateOf(0L) }

    /**
     * Самое свежее ЧУЖОЕ сообщение, о котором уже отзвучали. −1 — ленту ещё
     * не видели: на первой загрузке звучать нечему, иначе открытие любого
     * чата встречало бы «плипом».
     */
    var lastHeardId by remember(targetId) { mutableIntStateOf(-1) }

    // force = «прокрути вниз обязательно». Обычный scrollToEnd уважает
    // положение пользователя в истории (чтобы чужое сообщение не выдёргивало
    // из середины), но СВОЮ отправку показать нужно всегда.
    suspend fun reload(scrollToEnd: Boolean = false, force: Boolean = false) {
        runCatching {
            val loaded = if (isGroup) {
                if (pageLimit > 0) ChatRepository.loadGroupMessages(targetId, pageLimit)
                else ChatRepository.loadGroupMessages(targetId)
            } else {
                if (pageLimit > 0) ChatRepository.loadDirectMessages(targetId, pageLimit)
                else ChatRepository.loadDirectMessages(targetId)
            }
            // Присваиваем ТОЛЬКО при реальном изменении: одинаковый по
            // содержимому список всё равно заставил бы перекомпоновать все
            // пузыри, а это заметный рывок посреди прокрутки.
            // Закрепы приходят отдельным запросом и проставляются на
            // сообщения: в самих таблицах сообщений закрепа нет, он лежит в
            // pinned_messages и общий для чата.
            val pinned = runCatching { PinsRepository.pinnedIds(scopeKind) }
                .getOrDefault(emptySet())
            val marked = if (pinned.isEmpty()) loaded
                         else loaded.map { it.copy(isPinned = it.id in pinned) }

            // ДОЛИСТАННОЕ НЕ ТЕРЯЕМ.
            //
            // Опрос забирает последние pageLimit сообщений, и лимит он
            // прочитал ДО запроса. Пока запрос ехал, человек мог долистать
            // ещё страницу истории — и простое присваивание выбрасывало её:
            // сообщение, за которое держалась лента, исчезало из списка, и
            // она оказывалась на страницу ниже. Это и есть «быстро листаешь
            // плюс прогруз — телепортирует».
            //
            // Поэтому всё, что СТАРШЕ первого пришедшего, оставляем как
            // было: свежая выборка про него ничего не знает, но на экране он
            // есть и держит положение.
            val oldestFresh = marked.firstOrNull()?.id
            val kept = if (oldestFresh == null) emptyList()
                       else messages.takeWhile { it.id < oldestFresh }
            val merged = if (kept.isEmpty()) marked else kept + marked
            if (merged.size != messages.size ||
                merged.zip(messages).any { (a, b) -> a != b }
            ) {
                messages = merged
            }
            // И лимит поднимаем до показанного, иначе следующий же опрос
            // сократит ленту обратно.
            if (merged.size > pageLimit) pageLimit = merged.size
            // Максимум ленты — это и есть максимум переписки: страница
            // грузится с конца. Записываем его здесь, чтобы опрос не считал
            // только что показанное сообщение новостью.
            lastMaxId = loaded.maxOfOrNull { it.id } ?: -1

            // «Плип» на чужое сообщение в открытом чате — как на ПК. Системное
            // уведомление сюда не доходит: приложение на экране, и звук —
            // единственный признак, что пришло что-то новое, если человек
            // смотрит в другой конец переписки.
            val newestIncoming = loaded.filterNot { it.isMine }.maxOfOrNull { it.id } ?: -1
            if (lastHeardId >= 0 && newestIncoming > lastHeardId) Sounds.message()
            if (newestIncoming > lastHeardId) lastHeardId = newestIncoming

            // Реакции и память — по ВСЕМУ показанному, а не только по
            // свежей выборке: иначе у долистанных сообщений реакции
            // пропадали бы на каждом опросе, а кеш забывал бы историю.
            reactions = ReactionsRepository.forMessages(merged.map { it.id }, scopeKind)
            MessageMemory.put(scopeKind, targetId, merged, reactions)
            ChatRepository.prefetchPageMedia(loaded, scopeKind)
            if (!isGroup) {
                val state = ChatRepository.blockState(targetId)
                iBlocked = state.first
                theyBlocked = state.second
                // Читать за пользователя можно только когда он и правда
                // смотрит на экран. Опрос composable продолжает крутиться и
                // со свёрнутым приложением (активити остановлена, но не
                // уничтожена), и раньше он молча помечал всё прочитанным —
                // из-за этого от собеседника, чат с которым остался открыт,
                // уведомления не приходили вообще.
                //
                // И только если есть что отмечать. UPDATE без строк всё равно
                // стоит серверу прохода по сообщениям собеседника, а уходил
                // он на каждую перезагрузку ленты.
                val unreadIncoming = loaded.any { !it.isMine && !it.isRead }
                if (PresenceReporter.isForeground && unreadIncoming) {
                    ChatRepository.markAsRead(targetId)
                }
            }
        }
        loading = false
        // Прокручиваем вниз только если пользователь и так был у конца ленты:
        // иначе новое сообщение выдёргивало бы его из середины истории.
        //
        // В перевёрнутой ленте «у конца» считается одной строкой: нулевой
        // элемент — самое свежее сообщение, и стоит он у нижнего края ровно
        // тогда, когда смещение прокрутки нулевое. Пара пикселей допуска — на
        // дрожь пальца.
        val atBottom = listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset <= 8
        // И НЕ ТРОГАЕМ ленту, пока её листает пользователь. scrollToItem
        // мгновенно обрывает инерцию, а опрос приходит раз в две с половиной
        // секунды — попасть им в разгон пальца проще простого. Отсюда и
        // «скролл может застопиться» на пути от старых сообщений к новым.
        val userScrolling = listState.isScrollInProgress
        // И НЕ ТРОГАЕМ, пока смотрят видео. Опрос приходит раз в пару
        // секунд, и доматывание вниз выдёргивает кадр из-под пальца ровно
        // в тот момент, когда ролик перематывают: со стороны это выглядит
        // как «ленту телепортирует».
        val watching = com.pismo.messenger.ui.chat.VideoPlayback.active
        if (messages.isNotEmpty() && !userScrolling && !watching &&
            (force || (scrollToEnd && atBottom))
        ) {
            // Конец перевёрнутой ленты — это её нулевой элемент.
            listState.scrollToItem(0)
        }
    }

    /**
     * Съездить за следующей порцией заранее, в фоне. Повторно не ездим:
     * запас либо уже привезён под этот курсор, либо как раз едет.
     */
    suspend fun warmReserve() {
        if (reserveBusy || noMoreOlder) return
        val oldest = messages.firstOrNull()?.id ?: return
        if (reserve != null && reserveBefore == oldest) return
        reserveBusy = true
        val got = runCatching {
            if (isGroup) ChatRepository.loadGroupMessages(targetId, PAGE_STEP, oldest)
            else ChatRepository.loadDirectMessages(targetId, PAGE_STEP, oldest)
        }.getOrDefault(emptyList())
        if (got.isNotEmpty()) {
            // Картинки тоже заранее: иначе пузыри встанут пустыми и
            // подрастут потом, дёрнув ленту под пальцем.
            runCatching { ChatRepository.prefetchPageMedia(got, scopeKind) }
            reserve = got
            reserveBefore = oldest
        }
        reserveBusy = false
    }

    /**
     * Остановили запись — оставляем записанное в полосе предпрослушивания.
     *
     * Меньше 4 КБ (примерно десятая доля секунды) — это случайный двойной тап
     * по кнопке, а не голосовое: молча выбрасываем.
     */
    fun keepVoice(wav: ByteArray?) {
        recording = false
        WavPlayer.stop()
        if (wav == null || wav.size <= 4000) return
        pendingVoice = wav
    }

    fun sendVoice(wav: ByteArray?) {
        recording = false
        // Запись останавливают и кнопкой, и таймером на три минуты. Если оба
        // сработают в один момент, без защёлки уйдут два голосовых.
        if (wav == null || sending) return
        sending = true
        scope.launch {
            runCatching {
                ChatRepository.sendMessage(
                    scope = scopeKind,
                    target = targetId,
                    text = "",
                    audio = wav,
                    replyToId = replyTo?.id ?: 0,
                )
                notifyPeers(isGroup, targetId)
            }
            replyTo = null
            sending = false
            reload(scrollToEnd = true, force = true)
        }
    }

    // Таймер записи и жёсткий потолок в 3 минуты. Голосовое целиком лежит в
    // памяти и уходит в БД одним BLOB'ом, поэтому без верхней границы
    // забытая запись раздувает и приложение, и таблицу.
    LaunchedEffect(recording) {
        if (!recording) {
            recordSeconds = 0
            return@LaunchedEffect
        }
        recordSeconds = 0
        while (isActive && recording && recordSeconds < VOICE_MAX_SECONDS) {
            delay(1000)
            recordSeconds++
        }
        if (recording && recordSeconds >= VOICE_MAX_SECONDS) keepVoice(recorder.stop())
    }

    // reconnects в ключе: после восстановления связи с базой экран
    // перезагружается сам. Иначе первый неудачный запрос оставлял пустую
    // переписку до перезапуска приложения — опрос ниже сверяет только
    // количество и на нуле ничего не менял.
    val reconnects by Db.reconnects.collectAsState()

    // Счётчик переподключений монотонный, поэтому «был ли это реконнект»
    // определяется сравнением с запомненным значением, а не проверкой > 0:
    // иначе после первого же обрыва связи кружок появлялся бы навсегда.
    var seenReconnects by remember(targetId) { mutableStateOf(reconnects) }

    LaunchedEffect(targetId, reconnects) {
        val isReconnect = reconnects != seenReconnects
        seenReconnects = reconnects
        // Кружок ставим, только если показать нечего: с запомненной лентой
        // он перекрыл бы уже нарисованные сообщения ради того же результата.
        // После переподключения — ставим всегда, лента могла устареть.
        if (remembered == null || isReconnect) loading = true
        reload(scrollToEnd = true)
    }

    // Поочерёдная догрузка старых сообщений — как постраничная лента на ПК.
    // Дотянув прокрутку до верха, пользователь получает следующую порцию, а
    // не всю историю разом: тянуть тысячу расшифровок ради одного взгляда
    // назад незачем.
    LaunchedEffect(targetId) {
        var lastLoadAt = 0L

        // Признак «ленту ведут пальцем» вместо сравнения номеров.
        //
        // Раньше подгрузка требовала, чтобы номер первого видимого стал МЕНЬШЕ
        // предыдущего — то есть настоящего движения вверх. Это отсеивало ложные
        // выдачи потока (он срабатывает и на перекладке списка, и на тапе), но
        // ломалось сразу после подгрузки: если лента стояла у самого верха,
        // номер оставался нулём, уменьшаться ему было некуда, и следующая порция
        // не приходила, пока не проскроллишь вниз и обратно вверх.
        //
        // isScrollInProgress отвечает ровно на нужный вопрос — тянут ленту прямо
        // сейчас или нет. Тап её не поднимает, перекладка списка тоже.
        // Считаем, сколько элементов осталось до самого СТАРОГО загруженного.
        // В перевёрнутом списке номера растут вверх, поэтому верхний видимый
        // элемент — последний в visibleItemsInfo (он всегда отсортирован по
        // номеру), а «дотянули до верха» значит «до конца данных осталась
        // пара штук».
        snapshotFlow {
            val info = listState.layoutInfo
            val topMost = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            (info.totalItemsCount - 1 - topMost) to listState.isScrollInProgress
        }
            .collect { (left, scrolling) ->
                if (!scrolling) return@collect
                if (loading || loadingOlder || noMoreOlder) return@collect
                if (messages.isEmpty()) return@collect
                // Запас начинаем везти ЗАДОЛГО до порога. Почти вся пауза при
                // подгрузке — это поход к серверу, а не работа; сходить заранее,
                // пока человек ещё читает, и есть самый дешёвый способ её убрать.
                if (left <= 12) scope.launch { warmReserve() }
                if (left > 2) return@collect

                // Пауза между подгрузками: пока предыдущая порция укладывается,
                // номер первого видимого успевает дёрнуться ещё несколько раз.
                val now = System.currentTimeMillis()
                if (now - lastLoadAt < PAGE_COOLDOWN_MS) return@collect
                lastLoadAt = now

                loadingOlder = true
                // Берём ТОЛЬКО следующую порцию старых, а не всю страницу заново.
                //
                // Раньше здесь рос pageLimit и звался reload(), то есть на третьем
                // шаге с сервера снова ехали все восемьдесят сообщений, снова
                // расшифровывались все восемьдесят текстов и заново собирался весь
                // список — отсюда и ощущение, что каждая следующая порция идёт
                // тяжелее предыдущей. Теперь запрос идёт по курсору id < самого
                // старого показанного и возвращает ровно новую двадцатку.
                val oldestId = messages.firstOrNull()?.id ?: 0
                // Запас подошёл — за сообщениями ехать уже не надо.
                val fromReserve = reserve?.takeIf { reserveBefore == oldestId && it.isNotEmpty() }
                if (fromReserve != null) { reserve = null; reserveBefore = 0 }
                val older = fromReserve ?: runCatching {
                    if (isGroup) ChatRepository.loadGroupMessages(targetId, PAGE_STEP, oldestId)
                    else ChatRepository.loadDirectMessages(targetId, PAGE_STEP, oldestId)
                }.getOrDefault(emptyList())

                if (older.isEmpty()) {
                    // История кончилась — больше не дёргаем.
                    noMoreOlder = true
                } else {
                    // pageLimit держим в актуальном размере: полная перезагрузка
                    // (её делает опрос, когда лента и правда изменилась) должна
                    // вернуть всё показанное, а не схлопнуть ленту до сорока.
                    pageLimit = (if (pageLimit > 0) pageLimit else messages.size) + older.size
                    // Отметку закрепа ставим и на порцию: она приходит из базы
                    // без неё, и без этого закреплённое старое сообщение
                    // выглядело бы обычным.
                    val pinnedNow = runCatching { PinsRepository.pinnedIds(scopeKind) }
                        .getOrDefault(emptySet())
                    messages = (if (pinnedNow.isEmpty()) older
                                else older.map { it.copy(isPinned = it.id in pinnedNow) }) + messages
                    reactions = reactions + ReactionsRepository.forMessages(
                        older.map { it.id }, scopeKind
                    )
                    MessageMemory.put(scopeKind, targetId, messages, reactions)
                    if (fromReserve == null) ChatRepository.prefetchPageMedia(older, scopeKind)
                    // И сразу везём следующую порцию: человек читает
                    // приехавшее, канал свободен — к его приходу наверх она
                    // будет лежать готовой, и подгрузка обойдётся без запроса.
                    warmReserve()
                }
                // Поправлять позицию руками НЕ НУЖНО и вредно. Лента
                // перевёрнута: порция старых сообщений уходит в КОНЕЦ данных,
                // за верхний край экрана, и на уже разложенное не влияет
                // вовсе. А ручной scrollToItem поверх этого обрывал бы
                // инерцию — палец в этот момент как раз ведёт ленту вверх.
                loadingOlder = false
            }
    }

    // Индикатор набора гаснет сам: сигнал «перестал печатать» протокол не
    // предусматривает ни здесь, ни на ПК — там ровно тот же таймер на 4 с.
    LaunchedEffect(typingAt) {
        if (typingAt == 0L) return@LaunchedEffect
        delay(4000)
        if (System.currentTimeMillis() - typingAt >= 4000) typingName = ""
    }

    // Присутствие собеседника — отдельным лёгким запросом раз в 6 секунд,
    // тот же период, что у _presenceTimer на ПК. В общую перезагрузку его
    // класть нельзя: она идёт только при изменении числа сообщений.
    LaunchedEffect(targetId, isGroup) {
        if (isGroup) return@LaunchedEffect
        while (isActive) {
            runCatching { peerPresence = PresenceRepository.presenceOf(targetId) }
            delay(6000)
        }
    }

    // Приход по сокету — подпись в шапке меняется сразу, а не со следующей
    // сверкой: собеседник отошёл от компьютера, и это видно тут же.
    LaunchedEffect(targetId, isGroup) {
        if (isGroup) return@LaunchedEffect
        PresenceRepository.updates.collect {
            PresenceRepository.cached(targetId)?.let { peerPresence = it }
        }
    }

    // Опрос по числу сообщений — тот же приём, что PollTick на ПК.
    //
    // Плюс отдельно следим за числом СВОИХ непрочитанных: прочтение количества
    // сообщений не меняет, поэтому синие галочки появлялись только тогда,
    // когда кто-нибудь напишет следующее сообщение. Запрос ложится на индекс
    // целиком и стоит копейки.
    LaunchedEffect(targetId, isGroup) {
        while (isActive) {
            delay(2500)
            runCatching {
                // Каждый тик — только дешёвое: MAX(id) это одно движение по
                // индексу, сколько бы сообщений ни накопилось.
                val maxId = if (isGroup) ChatRepository.groupMaxId(targetId)
                else ChatRepository.directMaxId(targetId)

                val mineUnread =
                    if (isGroup) -1 else ChatRepository.unreadToPartner(targetId)

                // Счёт всех строк оставлен только затем, чтобы заметить
                // УДАЛЁННОЕ: при удалении максимум не меняется. Но идёт он по
                // всей переписке, поэтому спрашиваем раз в восемь тиков —
                // двадцать секунд, — а не каждые две с половиной.
                tick++
                val total = if (tick % 8 != 0) lastTotal
                else if (isGroup) ChatRepository.groupMessageCount(targetId)
                else ChatRepository.directMessageCount(targetId)

                // Закрепы. Теперь они видны прямо на пузыре, так что сверять
                // их стало зачем. Событие по ws может и не дойти — клиент мог
                // быть не на связи в этот момент, — поэтому отпечаток сверяем
                // и опросом. Только по ОТКРЫТОМУ чату: общий по всей таблице
                // срабатывал на чужие закрепы в чужих переписках.
                val pins = runCatching {
                    PinsRepository.fingerprint(scopeKind, targetId)
                }.getOrDefault("")
                val pinsChanged = lastPins != null && pins.isNotEmpty() && pins != lastPins
                if (pins.isNotEmpty()) lastPins = pins

                // Каждое сравнение — только против ранее ИЗМЕРЕННОГО значения
                // того же рода. Первое измерение (−1) поводом не считается.
                val changed = (lastMaxId >= 0 && maxId != lastMaxId) ||
                    (lastTotal >= 0 && total != lastTotal) ||
                    (!isGroup && lastUnreadToPartner >= 0 &&
                            mineUnread != lastUnreadToPartner)

                lastMaxId = maxId
                lastTotal = total
                lastUnreadToPartner = mineUnread
                if (changed) reload(scrollToEnd = true)
                // Закреп ленту вниз не гонит: человек мог читать старое.
                else if (pinsChanged) reload()
            }
        }
    }

    DisposableEffect(targetId) {
        val listener: (String, Int, Int, String) -> Unit = { type, sender, session, payload ->
            if (type == "new_message") scope.launch { reload(scrollToEnd = true) }

            // Закрепили или открепили — перечитываем: отметка закрепа теперь
            // стоит на самом пузыре. Событие шлёт и ПК, и мы сами
            // (см. PinsRepository.toggle).
            if (type == "pin") scope.launch { reload() }

            // Собеседник прочитал мои сообщения — обновляем галочки сразу, не
            // дожидаясь опроса. Событие шлёт и ПК, и мы сами (см. markAsRead):
            // в поле userId едет тот, КТО прочитал.
            if (type == "read" && !isGroup && sender == targetId) {
                scope.launch { reload() }
            }

            // Группу удалили — оставаться в её переписке незачем.
            if (type == "group_members" && payload == "deleted" &&
                isGroup && session == targetId
            ) {
                onBack()
            }
            if (type == "group_look" && isGroup && session == targetId) {
                com.pismo.messenger.data.repo.GroupRepository.forgetLook(targetId)
                groupLook++
            }

            // «печатает…». Раскладка полей взята с ПК один в один, иначе
            // индикатор не совпадёт между телефоном и компьютером: в группе
            // адресат 0, а в sessionId едет id группы; в личке наоборот —
            // адресат это собеседник, в sessionId id отправителя.
            if (type == "typing" && sender != UserSession.effectiveId) {
                val mine = if (payload == "group") isGroup && session == targetId
                else !isGroup && (sender == targetId || session == targetId)
                if (mine) {
                    typingName = messages.lastOrNull { it.senderId == sender }?.senderName
                        ?.takeIf { it.isNotBlank() } ?: "Собеседник"
                    typingAt = System.currentTimeMillis()
                }
            }
        }
        SignalingClient.addListener(listener)
        onDispose {
            SignalingClient.removeListener(listener)
            WavPlayer.stop()
            recorder.cancel()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            // Слишком большие пропускаем поимённо, а не бросаем всю пачку:
            // из-за одного лишнего файла терять остальные обидно.
            val tooBig = mutableListOf<String>()
            val added = mutableListOf<PendingFile>()
            // Всё приложенное лежит в памяти до отправки, поэтому предел общий,
            // а не на каждый файл: иначе пачкой можно набрать столько, что
            // приложению не хватит памяти и оно просто закроется.
            var used = pending.sumOf { it.bytes.size.toLong() }
            for (uri in uris) {
                runCatching {
                    val name = queryFileName(context, uri)

                    // Размер узнаём ДО чтения: файл на 300 МБ иначе успел бы
                    // положить приложение ещё до проверки.
                    val declared = fileSizeOf(context, uri)
                    if (declared > 0 && used + declared > MAX_ATTACH_BYTES) {
                        tooBig += name; return@runCatching
                    }

                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching
                    if (used + bytes.size > MAX_ATTACH_BYTES) { tooBig += name; return@runCatching }
                    used += bytes.size

                    val isImage = com.pismo.messenger.core.isImageName(name) ||
                            com.pismo.messenger.core.isGifName(name)

                    // Картинку приводим к формату, который прочтут все клиенты.
                    // Телефон открывает WebP и HEIC сам, а ПК — нет, и там она
                    // превращалась в «Не удалось загрузить изображение».
                    val (outBytes, outName) =
                        if (isImage) com.pismo.messenger.core.toPortableImage(bytes, name)
                        else bytes to name

                    // Файл НЕ отправляем сразу. На ПК вложение сначала
                    // прикрепляется, к нему можно дописать текст, и уходит всё
                    // одним сообщением; здесь же получалось два — сначала файл,
                    // потом отдельно подпись.
                    added += PendingFile(bytes = outBytes, fileName = outName, isImage = isImage)
                }
            }
            if (added.isNotEmpty()) pending = pending + added
            if (tooBig.isNotEmpty()) {
                jumpNote = "Не приложено — всего можно ${MAX_ATTACH_BYTES / 1024 / 1024} МБ за раз: " +
                        tooBig.joinToString(", ")
            }
        }
    }

    // Снимок с камеры прикрепляется так же, как выбранный файл: к нему
    // можно дописать подпись, и уходит всё одним сообщением.
    val takePhoto = rememberCameraShot { bytes, name ->
        pending = pending + PendingFile(bytes = bytes, fileName = name, isImage = true)
    }

    fun send() {
        val text = input.trim()
        val attach = pending
        val edit = editing
        if (edit != null) {
            if (text.isEmpty() || sending) return
            sending = true
            scope.launch {
                runCatching { ChatRepository.editMessage(scopeKind, edit.id, text) }
                editing = null
                input = ""
                sending = false
                reload()
            }
            return
        }
        // Записанное голосовое уходит отсюда же — вместе с текстом, если он
        // набран. Забираем его до проверки на пустоту: подпись необязательна.
        val voice = pendingVoice
        if (voice != null) {
            if (sending) return
            sending = true
            WavPlayer.stop()
            scope.launch {
                val sent = runCatching {
                    val id = ChatRepository.sendMessage(
                        scope = scopeKind,
                        target = targetId,
                        text = text,
                        audio = voice,
                        replyToId = replyTo?.id ?: 0,
                    )
                    if (id > 0) notifyPeers(isGroup, targetId)
                    id > 0
                }.getOrDefault(false)
                sending = false

                // Запись не ушла — не выбрасываем её: переписать голосовое
                // заново человек точно не захочет.
                if (!sent) {
                    sendError = "Голосовое не отправлено: нет связи с сервером."
                    return@launch
                }
                sendError = ""
                input = ""
                replyTo = null
                pendingVoice = null
                reload(scrollToEnd = true, force = true)
            }
            return
        }

        // Без вложения пустой текст отправлять нечего; с вложением —
        // наоборот, подпись необязательна.
        if ((text.isEmpty() && attach.isEmpty()) || sending) return

        // Вложения уходят НЕ в области экрана: выход из чата закрывал её и
        // обрывал дозапись, оставляя собеседнику пустое сообщение с одним
        // именем файла. Заодно за такой отправкой видно, сколько осталось,
        // и её можно отменить.
        //
        // Каждое вложение — отдельное сообщение: в строке базы ровно одно
        // место под файл. Подпись достаётся первому, иначе она повторилась
        // бы под каждым.
        if (attach.isNotEmpty()) {
            attach.forEachIndexed { i, att ->
                Transfers.sendChat(
                    scopeKind = scopeKind,
                    target = targetId,
                    isGroup = isGroup,
                    text = if (i == 0) text else "",
                    image = att.bytes.takeIf { att.isImage },
                    file = att.bytes.takeIf { !att.isImage },
                    // Имя файла — ТОЛЬКО для не-картинок. Пока оно писалось
                    // и для изображений, собеседник на ПК видел под фото
                    // вторую строку — карточку «нажмите для загрузки», хотя
                    // это одно и то же вложение. ПК при отправке картинки
                    // тоже передаёт file_name как null.
                    fileName = att.fileName.takeIf { !att.isImage },
                    replyToId = if (i == 0) replyTo?.id ?: 0 else 0,
                )
            }
            input = ""
            replyTo = null
            pending = emptyList()
            return
        }

        sending = true
        scope.launch {
            val sent = runCatching {
                val id = ChatRepository.sendMessage(
                    scope = scopeKind,
                    target = targetId,
                    text = text,
                    replyToId = replyTo?.id ?: 0,
                )
                if (id > 0) notifyPeers(isGroup, targetId)
                id > 0
            }.getOrDefault(false)
            sending = false

            // Не ушло — оставляем набранное и ответ на месте: заставлять
            // человека набирать заново из-за моргнувшей связи нельзя.
            if (!sent) {
                sendError = "Не отправлено: нет связи с сервером. Нажмите отправку ещё раз."
                return@launch
            }
            sendError = ""
            input = ""
            replyTo = null
            reload(scrollToEnd = true, force = true)
        }
    }

    // Отправка закончилась (или отменена) — перечитываем: сообщение с файлом
    // появилось целиком, отменённое исчезло.
    val uploadCount = Transfers.active.collectAsState().value
        .count { it.where == Transfers.chatKey(isGroup, targetId) }
    LaunchedEffect(uploadCount) {
        if (uploadCount == 0) reload(scrollToEnd = true, force = true)
    }

    val readOnly = !isGroup && (iBlocked || theyBlocked)

    /**
     * Номер ЭЛЕМЕНТА ЛЕНТЫ для сообщения, подходящего под условие.
     *
     * Лента перевёрнута, поэтому номер в хронологическом списке и номер
     * элемента списка — разные вещи: нулевой элемент это самое свежее
     * сообщение. Все прыжки — по цитате, из поиска, на дату — ходят только
     * через эту функцию, чтобы не разъехаться. Фильтр тот же, что у ленты: в
     * закрытой переписке часть сообщений скрыта, и без него номера съезжают.
     */
    fun feedIndexOf(match: (ChatMessage) -> Boolean): Int {
        val chrono = messages.filter { !readOnly || it.isMine }
        val i = chrono.indexOfFirst(match)
        return if (i < 0) -1 else chrono.lastIndex - i
    }

    Scaffold(
        containerColor = PismoColors.BgMain,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            if (isGroup) showGroupManage = true else showUserActions = true
                        },
                    ) {
                        if (isGroup) GroupAvatar(
                            targetId, title, "#5865F2", 32.dp,
                            hasAvatar = true, version = groupLook,
                        )
                        else UserAvatar(targetId, title, 32.dp, presence = peerPresence)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                if (isGroup) "👥 $title" else title,
                                color = PismoColors.TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            // Вторая строка шапки занята по старшинству:
                            // связь → «печатает…» → присутствие. Обрыв важнее
                            // всего остального: пока его нет, видно, что
                            // приложение не молчит просто так. Заставки во
                            // весь экран на это больше нет.
                            val net by com.pismo.messenger.core.NetStatus.state.collectAsState()
                            val netLabel = com.pismo.messenger.core.NetStatus.label(net)
                            if (netLabel != null) {
                                Text(
                                    netLabel,
                                    color = PismoColors.TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            } else if (typingName.isNotEmpty()) {
                                Text(
                                    if (isGroup) "✍ $typingName печатает…" else "✍ печатает…",
                                    color = PismoColors.Green,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                peerPresence?.takeIf { !isGroup }?.let { p ->
                                    Text(
                                        p.headerText(),
                                        color = when {
                                            !p.isOnline -> PismoColors.TextMuted
                                            p.isIdle -> PismoColors.Yellow
                                            else -> PismoColors.Green
                                        },
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад", tint = PismoColors.TextSecondary)
                    }
                },
                actions = {
                    // Кружок идущих передач — здесь он ничего не
                    // перекрывает, в отличие от плавающего над экраном.
                    com.pismo.messenger.ui.components.TransfersBadge()

                    // Поиск, дата и закреплённые уехали в меню: пять кнопок
                    // в ряд не оставляли шапке места, и имя со статусом
                    // обрезались на середине слова.
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "Ещё", tint = PismoColors.TextSecondary)
                        }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Поиск по переписке") },
                                onClick = { menuOpen = false; showSearch = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Перейти к дате") },
                                onClick = { menuOpen = false; showCalendar = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Закреплённые") },
                                onClick = { menuOpen = false; showPins = true },
                            )
                            if (isGroup) {
                                DropdownMenuItem(
                                    text = { Text("Участники и оформление") },
                                    onClick = { menuOpen = false; showGroupManage = true },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { startCall(context, targetId, title, isGroup, false) }) {
                        Icon(Icons.Default.Call, "Позвонить", tint = PismoColors.Green)
                    }
                    IconButton(onClick = { startCall(context, targetId, title, isGroup, true) }) {
                        Icon(Icons.Default.Videocam, "Видеозвонок", tint = PismoColors.Blurple)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PismoColors.BgDarkest),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Без imePadding клавиатура наезжает на строку ввода, и не
                // видно, что набираешь. adjustResize в манифесте сам по себе
                // Compose не помогает: он двигает окно, а не разметку.
                .imePadding()
        ) {
            if (selectMode) {
                SelectionBar(
                    count = selectedIds.size,
                    // Чужие сообщения удаляет только админ — то же правило,
                    // что и в меню одиночного сообщения.
                    canDelete = com.pismo.messenger.core.UserSession.isAdmin ||
                        messages.filter { it.id in selectedIds }.all { it.isMine },
                    onCancel = { selectMode = false; selectedIds = emptySet() },
                    onForward = {
                        forwardBatch = messages
                            .filter { it.id in selectedIds }
                            .map { ForwardItem(it.id, it.text, it.senderName) }
                    },
                    onDelete = {
                        val ids = selectedIds.toList()
                        selectMode = false
                        selectedIds = emptySet()
                        scope.launch {
                            ids.forEach { id ->
                                val m = messages.firstOrNull { it.id == id }
                                runCatching {
                                    ChatRepository.deleteMessage(scopeKind, id, m?.fileName)
                                }
                            }
                            reload()
                        }
                    },
                )
            }

            if (readOnly) {
                Text(
                    if (iBlocked) "Вы заблокировали этого пользователя — входящие сообщения скрыты."
                    else "Этот пользователь заблокировал вас — входящие сообщения скрыты.",
                    color = PismoColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PismoColors.BgElevated)
                        .padding(12.dp),
                )
            }

            // fillMaxWidth обязателен: в Column ширину задаёт самый широкий
            // ребёнок, и на время загрузки им был сам индикатор — коробка
            // схлопывалась по нему, а align(Center) центрировал кружок
            // внутри него же, то есть выбрасывал к левому краю экрана.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                // Кружок подгрузки истории — ПОВЕРХ ленты, у верхнего края.
                // Почему не элементом списка — см. пояснение внутри
                // LazyColumn ниже.
                if (loadingOlder && !loading) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp)
                            .zIndex(1f),
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            color = PismoColors.Blurple,
                            strokeWidth = 2.dp,
                        )
                    }
                }
                if (loading) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center),
                        color = PismoColors.Blurple,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        // Снизу вверх: нулевой элемент у нижнего края. Из-за
                        // этого подгрузка старых сообщений вообще не трогает
                        // уже разложенное — подробности у listState выше.
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 10.dp, vertical = 8.dp
                        ),
                    ) {
                        // КРУЖКА ЗАГРУЗКИ В СПИСКЕ НЕТ НАМЕРЕННО — он
                        // рисуется поверх ленты, ниже по коду.
                        //
                        // Пока он был элементом списка, он стоял первым, а
                        // список при изменении данных держится за ПЕРВЫЙ
                        // ВИДИМЫЙ элемент. Подгрузка же начинается ровно
                        // тогда, когда человек у самого верха, то есть когда
                        // первый видимый — этот самый кружок. Приехавшая
                        // порция ложилась НИЖЕ него, кружок оставался на
                        // месте, и вместе с ним на месте оставался взгляд —
                        // а содержимое уезжало на страницу. Отсюда и
                        // «телепортирует к следующей черте прогрузки», в том
                        // числе не сразу, а на следующем движении вверх.
                        //
                        // Поверх ленты он ничего не держит и ничего не
                        // двигает: список состоит только из сообщений, и
                        // держится он всегда за сообщение.

                        // Пачка вложений, отправленная разом, показывается
                        // ОДНОЙ плиткой — см. core/Albums. Закреплённые и те,
                        // на которых висят реакции, в плитку не прячем: там
                        // их негде показать.
                        //
                        // asReversed — это ВИД на список, а не копия: разворот
                        // ничего не перекладывает в памяти.
                        val visible = com.pismo.messenger.core.Albums.group(
                            messages.filter { !readOnly || it.isMine },
                        ) { reactions[it.id]?.isNotEmpty() == true }.asReversed()
                        itemsIndexedGroups(visible) { index, group ->
                            val msg = group.first()
                            // Сосед по номеру СТАРШЕ: список перевёрнут. Черта
                            // с датой стоит над самым ранним сообщением дня, а
                            // это тот, у кого сосед сверху — уже из другого
                            // дня (внутри пузыря порядок обычный, сверху вниз,
                            // переворот касается только самих элементов).
                            val older = visible.getOrNull(index + 1)?.last()
                            val showDate = older == null ||
                                    formatDateSeparator(older.createdAtMs) !=
                                    formatDateSeparator(msg.createdAtMs)
                            if (showDate) DateSeparator(formatDateSeparator(msg.createdAtMs))

                            // Пачка из нескольких вложений — одна плитка.
                            if (group.size > 1) {
                                AlbumBubble(
                                    msgs = group,
                                    scopeKind = scopeKind,
                                    isGroup = isGroup,
                                    transferKey = Transfers.chatKey(isGroup, targetId),
                                    onOpenVideo = { file, name, id, sender, cap ->
                                        fullscreenVideo =
                                            FullscreenVideo(file, name, id, sender, cap)
                                    },
                                    onOpenMedia = { id -> mediaViewerAt = id },
                                    selectMode = selectMode,
                                    selected = group.all { it.id in selectedIds },
                                    onEnterSelect = {
                                        selectMode = true
                                        selectedIds = group.map { it.id }.toSet()
                                    },
                                    onToggleSelect = {
                                        val ids = group.map { it.id }.toSet()
                                        selectedIds =
                                            if (ids.all { it in selectedIds }) selectedIds - ids
                                            else selectedIds + ids
                                        if (selectedIds.isEmpty()) selectMode = false
                                    },
                                )
                                return@itemsIndexedGroups
                            }

                            MessageBubble(
                                transferKey = Transfers.chatKey(isGroup, targetId),
                                onQuoteClick = { id ->
                                    scope.launch {
                                        // Номер элемента ленты — через
                                        // feedIndexOf: лента перевёрнута, и
                                        // хронологический номер ей не подходит.
                                        fun indexOf() = feedIndexOf { it.id == id }

                                        // Сообщения может не быть на
                                        // загруженной странице — тогда прыжок
                                        // просто ничего не делал. Расширяем
                                        // страницу ровно настолько, чтобы оно
                                        // в неё вошло, тем же приёмом, что и
                                        // переход по дате.
                                        if (indexOf() < 0) {
                                            val need = ChatRepository.countSinceId(
                                                scopeKind, targetId, id
                                            )
                                            if (need > 0 && need + 5 > messages.size) {
                                                pageLimit = need + 5
                                                loading = true
                                                reload()
                                            }
                                        }
                                        val idx = indexOf()
                                        if (idx >= 0) {
                                            listState.animateScrollToItem(idx)
                                            flashId = id
                                            delay(1200)
                                            if (flashId == id) flashId = 0
                                        } else {
                                            jumpNote = "Сообщение не найдено — возможно, удалено."
                                        }
                                    }
                                },
                                highlighted = msg.id == flashId,
                                msg = msg,
                                isGroup = isGroup,
                                reactions = reactions[msg.id].orEmpty(),
                                onReply = { replyTo = msg },
                                onEdit = { editing = msg; input = msg.text },
                                onDelete = {
                                    scope.launch {
                                        runCatching {
                                            ChatRepository.deleteMessage(scopeKind, msg.id, msg.fileName)
                                        }
                                        reload()
                                    }
                                },
                                onReact = { emoji ->
                                    scope.launch {
                                        ReactionsRepository.toggle(msg.id, scopeKind, emoji)
                                        reactions = ReactionsRepository
                                            .forMessages(messages.map { it.id }, scopeKind)
                                    }
                                },
                                scopeKind = scopeKind,
                                onOpenMedia = { id -> mediaViewerAt = id },
                                onOpenVideo = { file, name, id ->
                                    fullscreenVideo = FullscreenVideo(
                                        file, name, id,
                                        // Ник показываем всегда: в полном
                                        // экране от переписки не остаётся
                                        // ничего, и без него ролик висит сам
                                        // по себе — даже в личной переписке.
                                        sender = msg.senderName,
                                        caption = msg.text,
                                    )
                                },
                                selectMode = selectMode,
                                selected = msg.id in selectedIds,
                                onEnterSelect = {
                                    selectMode = true
                                    selectedIds = setOf(msg.id)
                                },
                                onToggleSelect = {
                                    selectedIds =
                                        if (msg.id in selectedIds) selectedIds - msg.id
                                        else selectedIds + msg.id
                                    // Сняли последнее — режим больше не нужен,
                                    // как выход из выделения на ПК.
                                    if (selectedIds.isEmpty()) selectMode = false
                                },
                            )
                        }
                    }
                }
            }

            // Полоса идущей отправки файла. Живёт вне экрана, поэтому видна
            // и после возвращения в чат, и отменить её можно оттуда же.
            val uploads by Transfers.active.collectAsState()
            uploads.filter { it.where == Transfers.chatKey(isGroup, targetId) }
                .forEach { UploadBar(it) }

            if (sendError.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(PismoColors.BgElevated)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(28.dp).background(PismoColors.Red))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        sendError,
                        color = PismoColors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { sendError = "" }) {
                        Icon(Icons.Default.Close, "Убрать", tint = PismoColors.TextMuted)
                    }
                }
            }

            // Панель прикреплённого — РЯД МИНИАТЮР, а не список строк.
            //
            // Строки «Изображение прикреплено» занимали по экрану на каждое
            // вложение и не показывали ни что именно выбрано, ни в каком
            // порядке уйдёт. Теперь это миниатюры с номерами по порядку, и
            // крестик у каждой убирает именно её — как в Telegram и как в
            // панели вложений на ПК.
            if (pending.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(PismoColors.BgElevated)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pending.forEachIndexed { i, att ->
                        Box(Modifier.padding(end = 8.dp)) {
                            Box(
                                Modifier
                                    // Крупнее: на 56 точках не видно, что
                                    // именно приложено.
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x22000000)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (att.isImage) {
                                    AsyncImage(
                                        model = att.bytes,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    FileBadge(
                                        fileBadge(att.fileName), fileColor(att.fileName), 44.dp,
                                    )
                                }
                            }
                            Text(
                                "${i + 1}",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(2.dp)
                                    .background(Color(0xFF1E8FE1), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                            Icon(
                                Icons.Default.Close,
                                "Убрать вложение",
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(18.dp)
                                    .background(Color(0x99000000), RoundedCornerShape(9.dp))
                                    .clickable { pending = pending - att },
                            )
                        }
                    }
                    Text(
                        formatBytesShort(pending.sumOf { it.bytes.size.toLong() }),
                        color = PismoColors.TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }

            // Панель ответа/редактирования над строкой ввода.
            val banner = replyTo ?: editing
            if (banner != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(PismoColors.BgElevated)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(32.dp)
                            .background(if (editing != null) PismoColors.Yellow else PismoColors.Cyan)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (editing != null) "Редактирование"
                            else "Ответ для ${banner.senderName}",
                            color = if (editing != null) PismoColors.Yellow else PismoColors.Cyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            // Та же пометка, что и в самой цитате: отвечая на
                            // фото или кружок, полоска была пустой.
                            ChatRepository.quotePreview(
                                banner.text, banner.hasImage, banner.hasAudio,
                                banner.hasVideo, banner.fileName,
                            ).ellipsize(60),
                            color = PismoColors.TextMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = {
                        // Набранное НЕ стираем, если это была отмена ответа:
                        // текст писал человек, и к ответу он отношения не
                        // имеет. Поле очищается только при отмене
                        // РЕДАКТИРОВАНИЯ — там в нём лежит не его текст, а
                        // копия правимого сообщения.
                        if (editing != null) input = ""
                        replyTo = null
                        editing = null
                    }) {
                        Icon(Icons.Default.Close, "Отменить", tint = PismoColors.TextMuted)
                    }
                }
            }

            // Записанное и ещё не отправленное — полоса над строкой ввода.
            pendingVoice?.let { wav ->
                VoiceDraftBar(
                    wav = wav,
                    onRerecord = {
                        // Старое стираем сразу и тут же начинаем новую запись —
                        // как просили: «перезаписать» это одно действие, а не
                        // «удалить», потом «записать».
                        pendingVoice = null
                        recording = recorder.start()
                    },
                    onDrop = { pendingVoice = null },
                )
            }

            // Во время записи строку ввода подменяет панель с таймером и
            // явной кнопкой отмены. Скрытый свайп-жест здесь не подходит:
            // запись включается тапом, а не удержанием, и «смахнуть» просто
            // нечего — отменить надо чем-то видимым.
            if (recording) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(PismoColors.BgDarkest)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(PismoColors.Red)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        formatDuration(recordSeconds.toLong()),
                        color = PismoColors.TextPrimary, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(12.dp))
                    // «максимум 3» без единицы читалось как угодно — от секунд
                    // до часов. Пишем единицу прямо в строке и не даём ей
                    // ужиматься: weight(1f) с maxLines обрезал хвост.
                    Text(
                        "из ${VOICE_MAX_SECONDS / 60} мин",
                        color = PismoColors.TextMuted, fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = {
                        // cancel(), а не stop(): записанное надо выбросить,
                        // а не отправить.
                        recorder.cancel()
                        recording = false
                    }) { Text("Отмена", color = PismoColors.Red, fontSize = 13.sp) }
                    IconButton(onClick = { keepVoice(recorder.stop()) }) {
                        Icon(Icons.Default.Stop, "Остановить", tint = PismoColors.Blurple)
                    }
                }
            }

            if (!readOnly && !recording) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(PismoColors.BgDarkest)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Кнопок слева стало четыре, и в стандартные 48 dp они
                    // съедали поле ввода на узком экране. 40 dp — палец всё
                    // ещё попадает, а строке остаётся место для текста.
                    IconButton(
                        onClick = { showAttach = true },
                        enabled = !sending,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Default.AttachFile, "Прикрепить", tint = PismoColors.TextMuted)
                    }

                    // Камера — рядом с «прикрепить». Снять и отправить фото
                    // раньше значило выйти в камеру, вернуться и искать
                    // снимок среди файлов.
                    IconButton(
                        onClick = { takePhoto() },
                        enabled = !sending,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Default.PhotoCamera, "Снять фото", tint = PismoColors.TextMuted)
                    }

                    IconButton(
                        onClick = { showCircleRecorder = true },
                        enabled = !sending,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Default.Videocam, "Видео-кружочек", tint = PismoColors.TextMuted)
                    }

                    // Кнопка GIF — как на ПК (и как в Discord). Гифка
                    // прикрепляется вложением, а не улетает сразу: к ней
                    // можно дописать подпись.
                    IconButton(
                        onClick = { showGifPicker = true },
                        enabled = !sending,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Default.Gif, "Гифки", tint = PismoColors.TextMuted)
                    }

                    TextField(
                        value = input,
                        onValueChange = {
                            input = it
                            // Не чаще раза в две секунды и не при
                            // редактировании — как на ПК: иначе каждая буква
                            // становилась бы пакетом в сокет.
                            val now = System.currentTimeMillis()
                            if (it.isNotEmpty() && editing == null && now - typingSentAt > 2000) {
                                typingSentAt = now
                                if (isGroup) {
                                    SignalingClient.send("typing", 0, targetId, "group")
                                } else {
                                    SignalingClient.send(
                                        "typing", targetId, UserSession.effectiveId, "direct"
                                    )
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("Сообщение…", color = PismoColors.TextMuted, fontSize = 14.sp)
                        },
                        maxLines = 5,
                        // ImeAction.None даёт на клавиатуре обычный Enter,
                        // который переводит строку. С действием по умолчанию
                        // там оказывается «Готово», и перенос набрать нечем.
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.None,
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = PismoColors.TextPrimary,
                            unfocusedTextColor = PismoColors.TextPrimary,
                            cursorColor = PismoColors.Blurple,
                        ),
                    )

                    // Записанное голосовое тоже считается «есть что отправить»:
                    // без него кнопка оставалась микрофоном, полоска предлагала
                    // «Отправить», а отправлять было нечем. Вложение (pending) в
                    // этом условии учли сразу, голосовое — забыли.
                    if (input.isBlank() && editing == null && pending.isEmpty() && pendingVoice == null) {
                        // Удержание — запись голосового, как кнопка 🎤 на ПК.
                        IconButton(
                            onClick = {
                                if (recording) keepVoice(recorder.stop())
                                else recording = recorder.start()
                            },
                        ) {
                            Icon(
                                if (recording) Icons.Default.Stop else Icons.Default.Mic,
                                if (recording) "Остановить запись" else "Голосовое",
                                tint = if (recording) PismoColors.Red else PismoColors.TextMuted,
                            )
                        }
                    } else {
                        IconButton(onClick = { send() }, enabled = !sending) {
                            Icon(Icons.Default.Send, "Отправить", tint = PismoColors.Blurple)
                        }
                    }
                }
            }
        }
    }

    if (showGroupManage && isGroup) {
        com.pismo.messenger.ui.group.GroupMembersDialog(
            groupId = targetId,
            groupName = title,
            onDismiss = { showGroupManage = false },
            onLeft = {
                // Вышли из группы или удалили её — оставаться в переписке
                // незачем.
                showGroupManage = false
                onBack()
            },
            // Своё же событие по сокету не возвращается — значок в шапке
            // перечитываем сами.
            onChanged = { groupLook++ },
        )
    }

    if (showUserActions && !isGroup) {
        UserActionsDialog(
            conversation = Conversation(
                userId = targetId, name = title, lastMessage = "",
                lastTimeMs = null, unread = 0,
            ),
            onDismiss = { showUserActions = false },
            onChanged = { showUserActions = false },
            onOpenChat = { showUserActions = false },
            // Чат уже открыт — «Написать» здесь ни к чему.
            showOpenChat = false,
        )
    }

    if (showSearch) {
        ChatSearchDialog(
            scopeKind = scopeKind,
            targetId = targetId,
            onDismiss = { showSearch = false },
            onJump = { found ->
                showSearch = false
                // Прыгаем к сообщению, если оно есть на текущей странице;
                // иначе просто закрываем — подгрузка вглубь истории пока
                // не реализована.
                val idx = feedIndexOf { it.id == found.id }
                if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
            },
        )
    }

    if (showCalendar) {
        DateJumpDialog(
            onDismiss = { showCalendar = false },
            onPick = { dayStartMs ->
                showCalendar = false
                scope.launch {
                    val need = ChatRepository.countSince(scopeKind, targetId, dayStartMs)
                    if (need <= 0) {
                        jumpNote = "За выбранную дату и позже сообщений нет."
                        return@launch
                    }
                    // Тянем на пяток больше нужного — так же, как need + 5
                    // в JumpToDate: иначе целевое сообщение окажется первым
                    // в ленте, без единой строки контекста над ним.
                    if (need + 5 > messages.size) {
                        pageLimit = need + 5
                        loading = true
                        reload()
                    }
                    val idx = feedIndexOf { it.createdAtMs >= dayStartMs }
                    if (idx >= 0) {
                        listState.animateScrollToItem(idx)
                        jumpNote = ""
                    } else {
                        jumpNote = "Не удалось найти сообщения за эту дату."
                    }
                }
            },
        )
    }

    if (jumpNote.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { jumpNote = "" },
            containerColor = PismoColors.BgSidebar,
            title = { Text("PISMO", color = PismoColors.TextPrimary) },
            text = { Text(jumpNote, color = PismoColors.TextSecondary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { jumpNote = "" }) {
                    Text("Ок", color = PismoColors.Cyan)
                }
            },
        )
    }

    if (showPins) {
        PinnedMessagesDialog(
            scopeKind = scopeKind,
            targetId = targetId,
            onDismiss = { showPins = false },
        )
    }

    FullscreenVideoHost(fullscreenVideo) { fullscreenVideo = null }

    if (forwardBatch.isNotEmpty()) {
        ForwardDialog(
            srcScope = scopeKind,
            items = forwardBatch,
            onDismiss = { forwardBatch = emptyList() },
            onDone = {
                forwardBatch = emptyList()
                selectMode = false
                selectedIds = emptySet()
            },
        )
    }

    mediaViewerAt?.let { openAt ->
        val media = messages
            .filter {
                !it.isDeleted && (it.hasImage ||
                    (it.hasFile && com.pismo.messenger.core.MediaKinds.isVideo(it.fileName)))
            }
            .map {
                MediaItem(
                    msgId = it.id,
                    isVideo = it.hasFile &&
                        com.pismo.messenger.core.MediaKinds.isVideo(it.fileName),
                    fileName = it.fileName,
                    sender = it.senderName,
                    timeMs = it.createdAtMs,
                )
            }
        val at = media.indexOfFirst { it.msgId == openAt }
        if (at >= 0) {
            MediaViewerHost(
                items = media,
                startIndex = at,
                scopeKind = scopeKind,
                transferKey = Transfers.chatKey(isGroup, targetId),
                onClose = { mediaViewerAt = null },
                // Лента просмотра строится по ВСЕЙ переписке, а не по
                // загруженному экрану.
                target = targetId,
                onShowInChat = { id ->
                    scope.launch {
                        val idx = feedIndexOf { it.id == id }
                        if (idx >= 0) {
                            listState.animateScrollToItem(idx)
                            flashId = id
                            delay(1200)
                            if (flashId == id) flashId = 0
                        }
                    }
                },
                onReply = { id -> replyTo = messages.firstOrNull { it.id == id } },
                onDelete = { id ->
                    scope.launch {
                        val m = messages.firstOrNull { it.id == id } ?: return@launch
                        runCatching {
                            ChatRepository.deleteMessage(scopeKind, m.id, m.fileName)
                        }
                        reload()
                    }
                },
            )
        } else {
            mediaViewerAt = null
        }
    }

    if (showAttach) {
        AttachSheet(
            onDismiss = { showAttach = false },
            onCamera = { takePhoto() },
            onSend = { picked, cap ->
                val replyId = replyTo?.id ?: 0
                val text = cap.ifBlank { input.trim() }
                replyTo = null
                if (cap.isBlank()) input = ""
                scope.launch {
                    sendPickedAttachments(
                        context = context,
                        picked = picked,
                        caption = text,
                        replyToId = replyId,
                        where = Transfers.chatKey(isGroup, targetId),
                        enqueue = { bytes, name, isImage, t, rid ->
                            Transfers.sendChat(
                                scopeKind = scopeKind,
                                target = targetId,
                                isGroup = isGroup,
                                text = t,
                                image = bytes.takeIf { isImage },
                                file = bytes.takeIf { !isImage },
                                fileName = name,
                                replyToId = rid,
                            )
                        },
                        note = { jumpNote = it },
                    )
                }
            },
        )
    }

    if (showGifPicker) {
        GifPickerDialog(
            onPicked = { bytes ->
                pending = pending + PendingFile(bytes = bytes, fileName = "giphy.gif", isImage = true)
            },
            onDismiss = { showGifPicker = false },
        )
    }

    CircleRecorderHost(
        visible = showCircleRecorder,
        onDismiss = { showCircleRecorder = false },
        onRecorded = { data ->
            showCircleRecorder = false
            if (sending) return@CircleRecorderHost
            sending = true
            scope.launch {
                runCatching {
                    ChatRepository.sendMessage(
                        scope = scopeKind,
                        target = targetId,
                        text = "",
                        video = data,
                        replyToId = replyTo?.id ?: 0,
                    )
                    notifyPeers(isGroup, targetId)
                }
                replyTo = null
                sending = false
                reload(scrollToEnd = true)
            }
        },
    )
}

@Composable
private fun CircleRecorderHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onRecorded: (ByteArray) -> Unit,
) {
    if (visible) VideoCircleRecorderDialog(onDismiss = onDismiss, onRecorded = onRecorded)
}

/**
 * Сообщаем о новом сообщении — событие ws-сервера, как на ПК.
 *
 * АДРЕСАТ 0, ТО ЕСТЬ ВСЕМ, И В ЛИЧНОЙ ПЕРЕПИСКЕ ТОЖЕ.
 *
 * Раньше личное сообщение уходило событием ТОЛЬКО собеседнику. Из-за этого
 * второе устройство самого отправителя о нём не узнавало: сидишь в одном чате
 * с телефона и с компьютера, пишешь с телефона — на компьютере ничего не
 * появляется до следующего опроса. С ПК то же сообщение доходило, потому что
 * он всегда рассылал всем.
 *
 * Группы и каналы и так рассылали всем — потому и жалоба была только на личные.
 */
private fun notifyPeers(isGroup: Boolean, target: Int) {
    if (isGroup) SignalingClient.send("new_message", 0, target, "group")
    else SignalingClient.send("new_message", 0, target, "direct")
}

private fun startCall(
    context: android.content.Context,
    targetId: Int,
    title: String,
    isGroup: Boolean,
    withVideo: Boolean,
) {
    // Разговор уже идёт или поднимается — второе окно не открываем. Право
    // начать всё равно возьмёт только первое (см. ActiveCall.claimStart), но
    // без этой проверки второе успевало бы мигнуть на весь экран и закрыться.
    if (com.pismo.messenger.call.ActiveCall.isBusy) return

    val intent = Intent(context, com.pismo.messenger.ui.call.CallActivity::class.java).apply {
        putExtra(com.pismo.messenger.ui.call.CallActivity.EXTRA_PEER_ID, if (isGroup) -1 else targetId)
        putExtra(com.pismo.messenger.ui.call.CallActivity.EXTRA_GROUP_ID, if (isGroup) targetId else -1)
        putExtra(com.pismo.messenger.ui.call.CallActivity.EXTRA_PEER_NAME, title)
        putExtra(com.pismo.messenger.ui.call.CallActivity.EXTRA_WITH_VIDEO, withVideo)
        putExtra(com.pismo.messenger.ui.call.CallActivity.EXTRA_IS_CALLER, true)
    }
    context.startActivity(intent)
}

internal fun queryFileName(context: android.content.Context, uri: android.net.Uri): String {
    var name = "file"
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx) ?: name
        }
    }
    return name
}

/**
 * itemsIndexed для ГРУПП сообщений: одиночное сообщение — группа из одного,
 * альбом — из нескольких. Ключ берём у первого в группе: он не меняется ни
 * от подгрузки истории, ни от прихода новых сообщений.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedGroups(
    groups: List<List<ChatMessage>>,
    content: @androidx.compose.runtime.Composable (Int, List<ChatMessage>) -> Unit,
) {
    items(groups.size, key = { index -> groups[index].first().id }) { index ->
        content(index, groups[index])
    }
}

/** itemsIndexed для LazyColumn без импорта экспериментального API. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedCompat(
    items: List<ChatMessage>,
    itemContent: @Composable (Int, ChatMessage) -> Unit,
) {
    // Ключ по id сообщения обязателен. Без него Compose сопоставляет
    // элементы по порядковому номеру, и любое обновление списка (опрос идёт
    // каждые 2.5 с) считается заменой всех элементов сразу: состояние
    // пузырей сбрасывается, уже показанные картинки грузятся заново.
    items(items.size, key = { index -> items[index].id }) { index ->
        itemContent(index, items[index])
    }
}

/**
 * Размер файла по content-URI без его чтения.
 *
 * OpenableColumns.SIZE отдают не все провайдеры (облачные часто молчат),
 * поэтому при неудаче возвращаем 0 — «неизвестно», и решение принимается
 * уже по фактически прочитанным байтам.
 */
internal fun fileSizeOf(context: android.content.Context, uri: android.net.Uri): Long =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else 0L
        } ?: 0L
    }.getOrDefault(0L)

/**
 * Прикреплённый, но ещё не отправленный файл.
 *
 * На ПК вложение сначала «готовится к отправке», к нему дописывается текст,
 * и всё уходит ОДНИМ сообщением. Здесь файл улетал сразу при выборе, а
 * подпись потом отдельной строкой — получалось два сообщения вместо одного.
 */
internal data class PendingFile(
    val bytes: ByteArray,
    val fileName: String,
    val isImage: Boolean,
) {
    // ByteArray сравнивается по ссылке, поэтому equals/hashCode пишем руками:
    // без них Compose считал бы одинаковые вложения разными и лишний раз
    // пересобирал панель.
    override fun equals(other: Any?): Boolean =
        other is PendingFile && fileName == other.fileName &&
                isImage == other.isImage && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        31 * (31 * bytes.contentHashCode() + fileName.hashCode()) + isImage.hashCode()
}

/** «482 КБ» / «12,4 МБ» — для подписи под прикреплённым файлом. */
internal fun formatBytesShort(bytes: Long): String = when {
    bytes < 1024L -> "$bytes Б"
    bytes < 1024L * 1024L -> "${bytes / 1024L} КБ"
    else -> String.format(java.util.Locale.getDefault(), "%.1f МБ", bytes / 1024.0 / 1024.0)
}

/**
 * Полоса идущей отправки файла — имя, доля и крестик отмены.
 *
 * На ПК такой показ был с самого начала (круговой индикатор с кнопкой),
 * на телефоне отправка шла вслепую: большой файл уходил минутами, и понять,
 * идёт он или уже сорвался, было нельзя.
 */
@Composable
internal fun UploadBar(task: com.pismo.messenger.data.Transfers.Task?) {
    if (task == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .background(PismoColors.BgElevated)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(32.dp)
                .background(if (task.error != null) PismoColors.Red else PismoColors.Blurple)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    task.error != null -> if (task.download) "Не скачано" else "Не отправлено"
                    task.waiting -> "В очереди"
                    task.download -> "Скачивание · ${(task.progress * 100).toInt()}%"
                    else -> "Отправка · ${(task.progress * 100).toInt()}%"
                },
                color = if (task.error != null) PismoColors.Red else PismoColors.Blurple,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                // Сколько уже ушло — рядом с размером. Одной доли мало: на
                // слабой связи она подолгу стоит на месте, и понять, идёт
                // отправка или встала, было нечем.
                task.error ?: when {
                    task.totalBytes <= 0 -> task.fileName
                    task.progress > 0f && task.progress < 1f ->
                        task.fileName + " · " +
                                formatBytesShort((task.totalBytes * task.progress).toLong()) +
                                " из " + formatBytesShort(task.totalBytes)
                    else -> task.fileName + " · " + formatBytesShort(task.totalBytes)
                },
                color = PismoColors.TextMuted,
                fontSize = 12.sp,
                maxLines = 2,
            )
            Spacer(Modifier.height(4.dp))
            if (task.error == null) LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = PismoColors.Blurple,
                trackColor = PismoColors.BgMain,
            )
        }
        // Сорвалось — предлагаем повторить теми же байтами: искать файл
        // заново из-за моргнувшей связи незачем.
        if (task.error != null && com.pismo.messenger.data.Transfers.canRetry(task.id)) {
            TextButton(onClick = { com.pismo.messenger.data.Transfers.retry(task.id) }) {
                Text("Повторить", color = PismoColors.Cyan, fontSize = 13.sp)
            }
        }
        IconButton(onClick = { com.pismo.messenger.data.Transfers.cancel(task.id) }) {
            Icon(
                Icons.Default.Close,
                if (task.error != null) "Убрать" else "Отменить отправку",
                tint = PismoColors.TextMuted,
            )
        }
    }
}
