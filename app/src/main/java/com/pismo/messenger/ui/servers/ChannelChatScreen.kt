package com.pismo.messenger.ui.servers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.pismo.messenger.ui.chat.jumpToCenter
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pismo.messenger.core.Mentions
import com.pismo.messenger.core.PresenceReporter
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.core.ellipsize
import com.pismo.messenger.core.formatDateSeparator
import com.pismo.messenger.core.formatDuration
import com.pismo.messenger.data.MessageMemory
import com.pismo.messenger.data.Transfers
import com.pismo.messenger.data.model.ChatMessage
import com.pismo.messenger.data.model.ReactionSummary
import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.media.WavPlayer
import com.pismo.messenger.media.WavRecorder
import com.pismo.messenger.data.model.ServerPermissions
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.ui.chat.TransferTile
import com.pismo.messenger.data.repo.ReactionsRepository
import com.pismo.messenger.data.repo.ServerRepository
import com.pismo.messenger.media.Sounds
import com.pismo.messenger.net.SignalingClient
import com.pismo.messenger.ui.chat.DateJumpDialog
import com.pismo.messenger.ui.chat.MAX_ATTACH_BYTES
import com.pismo.messenger.ui.chat.MessageBubble
import com.pismo.messenger.ui.chat.PendingFile
import com.pismo.messenger.ui.chat.PinnedMessagesDialog
import com.pismo.messenger.ui.chat.VOICE_MAX_SECONDS
import com.pismo.messenger.ui.chat.fileSizeOf
import com.pismo.messenger.ui.chat.queryFileName
import com.pismo.messenger.ui.components.DateSeparator
import com.pismo.messenger.ui.components.VoiceDraftBar
import com.pismo.messenger.ui.theme.PismoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Переписка в текстовом канале сервера — порт чата ServersForm.cs.
 *
 * Отличия от личных чатов, унаследованные от ПК: сообщения удаляются
 * жёстко (is_deleted в схеме server_messages нет), а чужое сообщение
 * может удалить только модератор.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelChatScreen(
    serverId: Int,
    channelId: Int,
    channelName: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // То же, что в личных чатах: запомненная лента показывается сразу,
    // свежая подтягивается фоном. Объявление обязано стоять ПЕРЕД messages —
    // из него берётся начальное значение.
    val remembered = remember(channelId) { MessageMemory.peek(Scope.SERVER, channelId) }
    var messages by remember(channelId) {
        mutableStateOf(remembered?.first ?: emptyList())
    }
    var reactions by remember(channelId) {
        mutableStateOf(remembered?.second ?: emptyMap<Int, List<ReactionSummary>>())
    }
    var perms by remember { mutableStateOf(ServerPermissions()) }
    // TextFieldValue, а не String: подсказка @упоминаний обязана знать, где
    // стоит курсор, иначе непонятно, какое из «@» в строке дополняется.
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    var loading by remember(channelId) { mutableStateOf(remembered == null) }
    /**
     * Что мы знаем о канале НА СЕРВЕРЕ: максимальный id и общее число
     * сообщений. Ровно как в личных чатах — и ровно с той же историей: сюда
     * клали размер загруженной СТРАНИЦЫ, а сравнивали с числом всех
     * сообщений канала, поэтому на канале длиннее сорока сообщений опрос
     * считал, что «что-то изменилось», каждые 2,5 секунды и перечитывал
     * ленту вхолостую. −1 — «ещё не измеряли».
     */
    var lastMaxId by remember(channelId) { mutableIntStateOf(-1) }
    var lastTotal by remember(channelId) { mutableIntStateOf(-1) }
    var tick by remember(channelId) { mutableIntStateOf(0) }
    /** Докуда уже отмечено прочитанным — чтобы не писать одно и то же. */
    var lastReadMarked by remember(channelId) { mutableIntStateOf(-1) }
    // Логин и роль — для подсветки сообщений, где упомянули меня.
    var mentionMe by remember(channelId) { mutableStateOf("" to "") }
    // Множественное выделение — то же, что в личных чатах.
    var selectMode by remember(channelId) { mutableStateOf(false) }
    var selectedIds by remember(channelId) { mutableStateOf(setOf<Int>()) }
    var forwardBatch by remember(channelId) {
        mutableStateOf<List<com.pismo.messenger.ui.chat.ForwardItem>>(emptyList())
    }
    var fullscreenVideo by remember(channelId) {
        mutableStateOf<com.pismo.messenger.ui.chat.FullscreenVideo?>(null)
    }

    // ЛЕНТА ПЕРЕВЁРНУТА, как в личных чатах: нулевой элемент — самое свежее
    // сообщение, и стоит он у нижнего края. Поэтому канал открывается на
    // последнем сообщении сам, начальную позицию задавать не нужно, а
    // приехавшая в ленту история ничего не сдвигает — двигать над якорем
    // нечего. Подробное объяснение — в ChatScreen, у его listState.
    val listState = rememberLazyListState()
    // KeepListAboveKeyboard не нужен: перевёрнутая лента прижата к низу окна
    // и поднимается вместе с ним, когда выезжает клавиатура.

    var showSearch by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    var showPins by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var jumpNote by remember { mutableStateOf("") }
    // Размер загружаемой страницы. Обычно хватает сорока последних, но
    // прыжок на старую дату требует дотянуть ленту до неё.
    var pageLimit by remember(channelId) { mutableStateOf(40) }

    /** Самое свежее чужое сообщение, о котором уже отзвучали. См. ChatScreen. */
    var lastHeardId by remember(channelId) { mutableIntStateOf(-1) }

    /** Отправка уже идёт — повторные нажатия игнорируем. */
    var sending by remember { mutableStateOf(false) }

    // Вложения в каналах: ПК их сюда только что принёс, а репозиторий у нас
    // умел их с самого начала — не хватало ровно кнопок.
    // Вложений может быть несколько: выбираются пачкой и уходят отдельными
    // сообщениями — в строке базы ровно одно место под файл.
    var pending by remember(channelId) { mutableStateOf<List<PendingFile>>(emptyList()) }
    /** Сообщение, к которому только что прыгнули по цитате: мигает и гаснет. */
    var flashId by remember(channelId) { mutableIntStateOf(0) }
    var showGifPicker by remember { mutableStateOf(false) }
    // Свой лист вложений — тот же, что в личной переписке.
    var showAttach by remember { mutableStateOf(false) }
    // Открытый просмотр медиа канала — с переходами по соседям.
    var mediaViewerAt by remember(channelId) { mutableStateOf<Int?>(null) }

    var attachNote by remember(channelId) { mutableStateOf("") }

    // Голосовые в каналах. Раньше их здесь не было вовсе — записать голосовое
    // можно было только в личном чате. Устроено так же, как там: тап
    // начинает запись, второй заканчивает, записанное ждёт в полосе над
    // строкой ввода и уходит обычной кнопкой отправки.
    val recorder = remember { WavRecorder(scope) }
    var recording by remember(channelId) { mutableStateOf(false) }
    var recordSeconds by remember(channelId) { mutableIntStateOf(0) }
    var pendingVoice by remember(channelId) { mutableStateOf<ByteArray?>(null) }
    /** Открытое на предпросмотр прикреплённое вложение (номер в списке). */
    var previewAttach by remember(channelId) { mutableStateOf<Int?>(null) }

    fun keepVoice(wav: ByteArray?) {
        recording = false
        WavPlayer.stop()
        // Меньше 4 КБ — случайный двойной тап, а не голосовое.
        if (wav == null || wav.size <= 4000) return
        pendingVoice = wav
    }

    suspend fun reload(scrollToEnd: Boolean = false) {
        // БЫЛ ЛИ ЧЕЛОВЕК У КОНЦА ЛЕНТЫ — СМОТРИМ ДО ОБНОВЛЕНИЯ СПИСКА.
        //
        // В перевёрнутой ленте пришедшее сообщение встаёт нулевым
        // элементом, а то, за которое держался список, уезжает на
        // первый — и условие «нулевой у нижнего края» после обновления
        // не выполняется уже никогда. Поэтому чужое сообщение ленту
        // вниз не доматывало.
        val wasAtBottom = listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset <= 8
        runCatching {
            val loaded = ServerRepository.channelMessages(channelId, limit = pageLimit)

            // Долистанное не теряем — то же, что в личной переписке. Опрос
            // прочитал лимит ДО запроса, и пока запрос ехал, человек мог
            // долистать ещё страницу истории. Простое присваивание
            // выбрасывало её вместе с сообщением, за которое держалась
            // лента, и канал уезжал на страницу.
            val oldestFresh = loaded.firstOrNull()?.id
            val kept = if (oldestFresh == null) emptyList()
                       else messages.takeWhile { it.id < oldestFresh }
            val merged = if (kept.isEmpty()) loaded else kept + loaded
            // Присваиваем только при реальном изменении — иначе одинаковый
            // список каждые 2,5 секунды перекомпоновывал бы все пузыри.
            if (merged.size != messages.size ||
                merged.zip(messages).any { (a, b) -> a != b }
            ) {
                messages = merged
            }
            if (merged.size > pageLimit) pageLimit = merged.size
            // Максимум ленты = максимум канала: страница грузится с конца.
            lastMaxId = loaded.maxOfOrNull { it.id } ?: -1

            // «Плип» на чужое сообщение в открытом канале — как на ПК.
            val newestIncoming = loaded.filterNot { it.isMine }.maxOfOrNull { it.id } ?: -1
            if (lastHeardId >= 0 && newestIncoming > lastHeardId) Sounds.message()
            if (newestIncoming > lastHeardId) lastHeardId = newestIncoming

            // Реакции и память — по всему показанному: иначе у долистанных
            // сообщений реакции пропадали бы на каждом опросе.
            reactions = ReactionsRepository.forMessages(merged.map { it.id }, Scope.SERVER)
            MessageMemory.put(Scope.SERVER, channelId, merged, reactions)
            ServerRepository.prefetchChannelMedia(loaded)
            perms = ServerRepository.permissions(serverId)
            // То же, что в личных чатах: свёрнутое приложение не должно
            // «прочитывать» канал за пользователя и глушить уведомления.
            // Отметка прочитанного — это запись в server_reads. Без
            // условия она уходила на КАЖДУЮ перезагрузку ленты, то есть
            // писала в базу одно и то же значение раз за разом.
            if (PresenceReporter.isForeground && lastMaxId > lastReadMarked) {
                ServerRepository.markChannelRead(channelId)
                lastReadMarked = lastMaxId
            }
        }
        loading = false
        // Прокручиваем вниз, только если пользователь и так был у конца
        // ленты: иначе новое сообщение выдёргивало бы его из середины
        // истории, которую он читает.
        // «У конца» в перевёрнутой ленте — это нулевой элемент у самого
        // нижнего края: см. пояснение в личной переписке.
        // Пока ленту листает пользователь — не трогаем: scrollToItem
        // мгновенно обрывает инерцию, а опрос приходит раз в две с половиной
        // секунды и легко попадает в разгон пальца.
        // И пока смотрят видео: доматывание вниз выдёргивает кадр из-под
        // пальца ровно тогда, когда ролик перематывают.
        if (scrollToEnd && messages.isNotEmpty() && wasAtBottom &&
            !listState.isScrollInProgress &&
            !com.pismo.messenger.ui.chat.VideoPlayback.active
        ) {
            // Конец перевёрнутой ленты — её нулевой элемент.
            listState.scrollToItem(0)
        }
    }

    // Таймер записи и тот же потолок в три минуты, что в личных чатах:
    // голосовое целиком лежит в памяти и уходит одним BLOB'ом.
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

    // Связь вернулась — перечитываем ленту канала немедленно.
    val netReconnects by com.pismo.messenger.data.db.Db.reconnects.collectAsState()
    var seenNet by remember(channelId) { mutableStateOf(netReconnects) }
    LaunchedEffect(channelId, netReconnects) {
        if (netReconnects != seenNet) {
            seenNet = netReconnects
            reload()
        }
    }

    LaunchedEffect(channelId) {
        mentionMe = runCatching { ServerRepository.mentionIdentity(channelId) }
            .getOrDefault("" to "")
    }

    LaunchedEffect(channelId) {
        // Кружок ставим, только если показать нечего: с запомненной лентой
        // он перекрыл бы уже нарисованные сообщения ради того же результата.
        if (remembered == null) loading = true
        reload(scrollToEnd = true)
    }

    LaunchedEffect(channelId) {
        while (isActive) {
            delay(2500)
            runCatching {
                // Каждый тик — только MAX(id): одно движение по индексу,
                // сколько бы сообщений в канале ни накопилось. Счёт строк
                // проходил по всему каналу и делал это каждые 2,5 секунды у
                // каждого читателя — отсюда и бралась постоянная нагрузка.
                val maxId = ServerRepository.channelMaxId(channelId)

                // Счёт оставлен только ради удалений: при них максимум не
                // меняется. Раз в восемь тиков — двадцать секунд, — а не
                // каждый раз.
                tick++
                val total = if (tick % 8 != 0) lastTotal
                else ServerRepository.channelMessageCount(channelId)

                val changed = (lastMaxId >= 0 && maxId != lastMaxId) ||
                    (lastTotal >= 0 && total != lastTotal)
                lastMaxId = maxId
                lastTotal = total
                if (changed) reload(scrollToEnd = true)
            }
        }
    }

    DisposableEffect(channelId) {
        val listener: (String, Int, Int, String) -> Unit = { type, _, session, payload ->
            if (type == "new_message") scope.launch { reload(scrollToEnd = true) }
            // Сервер удалили или меня с него выгнали — читать здесь больше
            // нечего, уходим обратно.
            if (type == "server_kick" && session == serverId) onBack()
            if (type == "server_members" && payload == "deleted" && session == serverId) onBack()
        }
        SignalingClient.addListener(listener)
        onDispose { SignalingClient.removeListener(listener) }
    }

    /**
     * Отправка. Защёлка sending обязательна: поле очищается только ПОСЛЕ
     * ответа базы, и на плохой сети текст всё это время остаётся на экране —
     * без неё каждое нажатие по «отправить» уходило отдельным сообщением.
     * В личных чатах такая защёлка была, здесь её забыли.
     */
    fun send() {
        if (sending) return
        val text = input.text.trim()
        val edit = editing
        if (edit != null) {
            if (text.isEmpty()) return
            sending = true
            scope.launch {
                runCatching { ServerRepository.editChannelMessage(edit.id, text) }
                editing = null; input = TextFieldValue("")
                sending = false
                reload()
            }
            return
        }
        // Записанное голосовое уходит здесь же, вместе с подписью, если она есть.
        val voice = pendingVoice
        if (voice != null) {
            sending = true
            WavPlayer.stop()
            scope.launch {
                runCatching {
                    ServerRepository.sendChannelMessage(
                        channelId = channelId,
                        text = text,
                        replyToId = replyTo?.id ?: 0,
                        audio = voice,
                    )
                    SignalingClient.send("new_message", 0, channelId, "server")
                }
                input = TextFieldValue(""); replyTo = null; pendingVoice = null
                sending = false
                reload(scrollToEnd = true)
            }
            return
        }

        val attach = pending
        // С вложением подпись необязательна — как в личных чатах.
        if (text.isEmpty() && attach.isEmpty()) return

        // Вложения — вне области экрана: уход из канала обрывал дозапись и
        // оставлял в канале пустое сообщение с одним именем файла. Каждое
        // уходит своим сообщением, подпись достаётся первому.
        if (attach.isNotEmpty()) {
            attach.forEachIndexed { i, att ->
                Transfers.sendChannel(
                    channelId = channelId,
                    // Знак пачки — всем, кроме первого (см. core/Albums).
                    text = if (i == 0) text
                           else if (attach.size > 1) com.pismo.messenger.core.Albums.MARK
                           else "",
                    replyToId = if (i == 0) replyTo?.id ?: 0 else 0,
                    // У ролика картинка — это его обложка (см. VideoCover).
                    image = if (att.isImage) att.bytes else att.cover,
                    file = att.bytes.takeIf { !att.isImage },
                    // Имя файла только для не-картинок: с ним ПК рисует под
                    // фото вторую строку-карточку того же вложения.
                    fileName = att.fileName.takeIf { !att.isImage },
                )
            }
            input = TextFieldValue(""); replyTo = null; pending = emptyList()
            return
        }

        sending = true
        scope.launch {
            runCatching {
                ServerRepository.sendChannelMessage(
                    channelId = channelId,
                    text = text,
                    replyToId = replyTo?.id ?: 0,
                )
                SignalingClient.send("new_message", 0, channelId, "server")
            }
            input = TextFieldValue(""); replyTo = null
            sending = false
            reload(scrollToEnd = true)
        }
    }

    // Отправка файла закончилась или отменена — перечитываем канал.
    // Только ОТПРАВКИ: докачавшийся с сервера файл не повод трогать ленту
    // (см. то же в личной переписке).
    val chanUploadCount = Transfers.active.collectAsState().value
        .count { it.where == Transfers.channelKey(channelId) && !it.download }
    LaunchedEffect(chanUploadCount) {
        if (chanUploadCount == 0) reload(scrollToEnd = true)
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            // Слишком большие пропускаем поимённо, а не бросаем всю пачку.
            val tooBig = mutableListOf<String>()
            val added = mutableListOf<PendingFile>()
            // Всё приложенное лежит в памяти до отправки, поэтому предел общий,
            // а не на каждый файл: иначе пачкой можно набрать столько, что
            // приложению не хватит памяти и оно просто закроется.
            var used = pending.sumOf { it.bytes.size.toLong() }
            for (uri in uris) {
                runCatching {
                    val name = queryFileName(context, uri)
                    // Размер узнаём ДО чтения: файл на двести мегабайт иначе
                    // успел бы положить приложение ещё до проверки.
                    val declared = fileSizeOf(context, uri)
                    if (declared > 0 && used + declared > MAX_ATTACH_BYTES) {
                        tooBig += name; return@runCatching
                    }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching
                    if (used + bytes.size > MAX_ATTACH_BYTES) { tooBig += name; return@runCatching }
                    used += bytes.size
                    val isImg = com.pismo.messenger.core.isImageName(name) ||
                            com.pismo.messenger.core.isGifName(name)
                    // Тот же перевод в универсальный формат, что и в переписке:
                    // WebP с телефона на ПК не открывался.
                    val (outBytes, outName) =
                        if (isImg) com.pismo.messenger.core.toPortableImage(bytes, name)
                        else bytes to name
                    val img = com.pismo.messenger.core.isImageName(name) ||
                            com.pismo.messenger.core.isGifName(name)
                    added += PendingFile(
                        bytes = outBytes,
                        fileName = outName,
                        isImage = img,
                        cover = if (!img && com.pismo.messenger.core.MediaKinds.isVideo(outName))
                            com.pismo.messenger.ui.chat.videoCoverJpeg(outBytes) else null,
                    )
                }
            }
            if (added.isNotEmpty()) pending = pending + added
            attachNote = if (tooBig.isEmpty()) ""
                else "Не приложено — всего можно ${MAX_ATTACH_BYTES / 1024 / 1024} МБ за раз: " +
                        tooBig.joinToString(", ")
        }
    }

    // Снимок с камеры прикрепляется так же, как выбранный файл: к нему
    // можно дописать подпись, и уходит всё одним сообщением.
    val takePhoto = com.pismo.messenger.ui.chat.rememberCameraShot { bytes, name ->
        pending = pending + PendingFile(bytes = bytes, fileName = name, isImage = true)
    }

    /**
     * Лента ровно в том виде, в каком её рисует LazyColumn: пачка вложений —
     * ОДИН элемент, порядок перевёрнут (нулевой элемент — самое свежее).
     */
    fun feedGroups(): List<List<ChatMessage>> =
        com.pismo.messenger.core.Albums.group(messages) {
            reactions[it.id]?.isNotEmpty() == true
        }.asReversed()

    /**
     * Номер ЭЛЕМЕНТА ЛЕНТЫ для сообщения, подходящего под условие. Считать по
     * хронологическому списку нельзя: альбом занимает один элемент на десяток
     * сообщений, и прыжок промахивался тем сильнее, чем больше пачек выше.
     */
    fun feedIndexOf(match: (ChatMessage) -> Boolean): Int =
        feedGroups().indexOfFirst { group -> group.any(match) }


    Scaffold(
        containerColor = PismoColors.BgMain,
        topBar = {
            TopAppBar(
                title = {
                    Text("# $channelName", color = PismoColors.TextPrimary, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold)
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

                    // Ровно то же меню, что и в личных чатах: поиск, прыжок
                    // на дату и закреплённые. Раньше здесь висела одна лупа,
                    // и два других действия в канале были недоступны вовсе.
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "Ещё", tint = PismoColors.TextSecondary)
                        }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Поиск по каналу") },
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
                        }
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
                // Клавиатура наезжала на строку ввода — ровно как в ЛС.
                .imePadding()
        ) {
            if (selectMode) {
                com.pismo.messenger.ui.chat.SelectionBar(
                    count = selectedIds.size,
                    // В канале чужое удаляет модератор — то же правило, что
                    // и в меню одиночного сообщения.
                    canDelete = perms.isAdminLike ||
                        messages.filter { it.id in selectedIds }
                            .all { it.senderId == UserSession.effectiveId },
                    onCancel = { selectMode = false; selectedIds = emptySet() },
                    onForward = {
                        val parts = com.pismo.messenger.core.Albums.partIds(messages)
                        forwardBatch = messages
                            .filter { it.id in selectedIds }
                            .map {
                                com.pismo.messenger.ui.chat.ForwardItem(
                                    it.id, it.text, it.senderName, it.id in parts
                                )
                            }
                    },
                    onDelete = {
                        val ids = selectedIds.toList()
                        selectMode = false
                        selectedIds = emptySet()
                        scope.launch {
                            ids.forEach { id ->
                                val mine = messages.firstOrNull { it.id == id }
                                    ?.senderId == UserSession.effectiveId
                                runCatching {
                                    ServerRepository.deleteChannelMessage(
                                        id,
                                        asModerator = !mine && perms.isAdminLike,
                                    )
                                }
                            }
                            reload()
                        }
                    },
                )
            }

            // fillMaxWidth: без него ширину задаёт самый широкий ребёнок, и
            // на время загрузки им был сам индикатор — кружок уезжал влево.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                // Кружок — только когда показывать нечего: перезагрузка
                // ленты больше не стирает переписку серым полем.
                if (loading && messages.isEmpty()) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center), color = PismoColors.Blurple,
                    )
                } else if (messages.isEmpty()) {
                    Text(
                        "Здесь пока пусто. Напишите первое сообщение.",
                        color = PismoColors.TextMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        // Снизу вверх — как в личных чатах: так приходящие
                        // сообщения и подгруженная история не сдвигают
                        // прочитанное из-под глаз.
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 10.dp, vertical = 8.dp
                        ),
                    ) {
                        // Ключ по id: без него обновление списка сбрасывает
                        // состояние всех пузырей, и картинки грузятся заново.
                        //
                        // asReversed — вид на список, а не копия: разворот
                        // ничего не перекладывает в памяти.
                        // Пачка вложений, отправленная разом, показывается
                        // одной плиткой — как в личной переписке.
                        val feed = feedGroups()
                        items(feed.size, key = { feed[it].first().id }) { index ->
                            val group = feed[index]
                            val msg = group.first()
                            // Сосед по номеру СТАРШЕ: список перевёрнут. Черта
                            // с датой стоит над самым ранним сообщением дня —
                            // у того, чей сосед сверху уже из другого дня.
                            val older = feed.getOrNull(index + 1)?.last()
                            val showDate = older == null ||
                                    formatDateSeparator(older.createdAtMs) !=
                                    formatDateSeparator(msg.createdAtMs)
                            if (showDate) DateSeparator(formatDateSeparator(msg.createdAtMs))

                            if (group.size > 1) {
                                com.pismo.messenger.ui.chat.AlbumBubble(
                                    msgs = group,
                                    scopeKind = Scope.SERVER,
                                    isGroup = true,
                                    transferKey = Transfers.channelKey(channelId),
                                    onOpenMedia = { id -> mediaViewerAt = id },
                                    onOpenVideo = { file, name, id, sender, cap ->
                                        fullscreenVideo =
                                            com.pismo.messenger.ui.chat.FullscreenVideo(
                                                file, name, id, sender, cap
                                            )
                                    },
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
                                    // Альбом — одно сообщение: отвечаем на него
                                    // и пересылаем его целиком.
                                    onReply = { replyTo = group.first() },
                                    onForward = {
                                        forwardBatch = group.mapIndexed { i, m ->
                                            com.pismo.messenger.ui.chat.ForwardItem(
                                                m.id, m.text, m.senderName, albumPart = i > 0
                                            )
                                        }
                                    },
                                    onDelete = run {
                                        val mine = group.all { it.senderId == UserSession.effectiveId }
                                        if (mine || perms.isAdminLike) {
                                            {
                                                scope.launch {
                                                    group.forEach { m ->
                                                        runCatching {
                                                            ServerRepository.deleteChannelMessage(
                                                                m.id, asModerator = !mine && perms.isAdminLike,
                                                            )
                                                        }
                                                    }
                                                    reload()
                                                }
                                                Unit
                                            }
                                        } else null
                                    },
                                )
                                return@items
                            }

                            MessageBubble(
                                transferKey = Transfers.channelKey(channelId),
                                onOpenMedia = { id -> mediaViewerAt = id },
                                onQuoteClick = { id ->
                                    scope.launch {
                                        // Сообщения может не быть на
                                        // загруженной странице — тогда прыжок
                                        // просто ничего не делал. Расширяем
                                        // страницу ровно настолько, чтобы оно
                                        // в неё вошло, тем же приёмом, что и
                                        // переход по дате.
                                        if (feedIndexOf { it.id == id } < 0) {
                                            val need = ChatRepository.countSinceId(
                                                Scope.SERVER, channelId, id
                                            )
                                            if (need > 0 && need + 5 > messages.size) {
                                                pageLimit = need + 5
                                                loading = true
                                                reload()
                                            }
                                        }
                                        val idx = feedIndexOf { it.id == id }
                                        if (idx >= 0) {
                                            flashId = id
                                            listState.jumpToCenter(idx)
                                            delay(1200)
                                            if (flashId == id) flashId = 0
                                        } else {
                                            jumpNote = "Сообщение не найдено — возможно, удалено."
                                        }
                                    }
                                },
                                highlighted = msg.id == flashId,
                                msg = msg,
                                isGroup = true,          // в канале всегда показываем автора
                                onOpenVideo = { file, name, id ->
                                    fullscreenVideo =
                                        com.pismo.messenger.ui.chat.FullscreenVideo(
                                            file, name, id,
                                            // В канале автор подписан всегда —
                                            // там пишут многие.
                                            sender = msg.senderName,
                                            caption = msg.text,
                                        )
                                },
                                mentioned = msg.senderId != UserSession.effectiveId &&
                                    Mentions.mentionsMe(msg.text, mentionMe.first, mentionMe.second),
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
                                    if (selectedIds.isEmpty()) selectMode = false
                                },
                                reactions = reactions[msg.id].orEmpty(),
                                scopeKind = Scope.SERVER,
                                onReply = { replyTo = msg },
                                onEdit = { editing = msg; input = TextFieldValue(msg.text) },
                                onDelete = {
                                    scope.launch {
                                        val mine = msg.senderId == UserSession.effectiveId
                                        runCatching {
                                            ServerRepository.deleteChannelMessage(
                                                msg.id,
                                                asModerator = !mine && perms.isAdminLike,
                                            )
                                        }
                                        reload()
                                    }
                                },
                                onReact = { emoji ->
                                    scope.launch {
                                        ReactionsRepository.toggle(msg.id, Scope.SERVER, emoji)
                                        reactions = ReactionsRepository.forMessages(
                                            messages.map { it.id }, Scope.SERVER
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

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
                            color = PismoColors.TextMuted, fontSize = 12.sp, maxLines = 1,
                        )
                    }
                    IconButton(onClick = { replyTo = null; editing = null; input = TextFieldValue("") }) {
                        Icon(Icons.Default.Close, "Отменить", tint = PismoColors.TextMuted)
                    }
                }
            }

            // Подсказка @упоминаний — прямо над строкой ввода. Позицию «@»
            // и набранный после него кусок считаем из курсора, как на ПК:
            // подсказка живёт до пробела и гаснет сама.
            val mention = mentionPrefix(input.text, input.selection.start)
            if (mention != null) {
                MentionSuggestions(
                    serverId = serverId,
                    partial = mention.second,
                    onPick = { option ->
                        val (text, cursor) = applyMention(
                            input.text, mention.first, input.selection.start, option.token
                        )
                        input = TextFieldValue(
                            text = text,
                            selection = androidx.compose.ui.text.TextRange(cursor),
                        )
                    },
                )
            }

            // Прикреплённое показываем строкой над полем: файл уходит вместе
            // с подписью одним сообщением, а не двумя, — как в личных чатах.
            // Полоса идущих передач — та же, что в личных чатах.
            val chanTransfers by Transfers.active.collectAsState()
            chanTransfers.filter { it.where == Transfers.channelKey(channelId) }
                .let { tasks ->
                    if (tasks.isEmpty()) return@let
                    // Плитками, как в личной переписке.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(PismoColors.BgElevated)
                            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tasks.forEach { TransferTile(it) }
                    }
                }

            // Ряд миниатюр с номерами — как в личной переписке и на ПК:
            // видно, что именно выбрано и в каком порядке уйдёт, а крестик
            // убирает именно эту вещь.
            if (pending.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(PismoColors.BgDarkest)
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pending.forEachIndexed { i, p ->
                        Box(Modifier.padding(end = 8.dp)) {
                            Box(
                                Modifier
                                    // Крупнее: на 56 точках не видно, что
                                    // именно приложено.
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x22000000))
                                    // Нажатие — предпросмотр: снимок в
                                    // редакторе, ролик в плеере.
                                    .clickable { previewAttach = i },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (p.isImage) {
                                    coil.compose.AsyncImage(
                                        model = p.bytes,
                                        contentDescription = null,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else if (p.cover != null) {
                                    // У ролика на плитке его кадр, а не значок.
                                    coil.compose.AsyncImage(
                                        model = p.cover,
                                        contentDescription = null,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        null,
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp),
                                    )
                                } else {
                                    com.pismo.messenger.ui.components.FileBadge(
                                        com.pismo.messenger.core.fileBadge(p.fileName),
                                        com.pismo.messenger.core.fileColor(p.fileName),
                                        44.dp,
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
                                    .clickable { pending = pending - p },
                            )
                        }
                    }
                }
            }
            if (attachNote.isNotEmpty()) {
                Text(
                    attachNote,
                    color = PismoColors.Red,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PismoColors.BgDarkest)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            // Записанное и ещё не отправленное голосовое.
            pendingVoice?.let { wav ->
                VoiceDraftBar(
                    wav = wav,
                    onRerecord = {
                        pendingVoice = null
                        recording = recorder.start()
                    },
                    onDrop = { pendingVoice = null },
                )
            }

            // Во время записи строку ввода подменяет панель с таймером —
            // один в один как в личных чатах.
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
                    Text(
                        "из ${VOICE_MAX_SECONDS / 60} мин",
                        color = PismoColors.TextMuted, fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = {
                        recorder.cancel()
                        recording = false
                    }) { Text("Отмена", color = PismoColors.Red, fontSize = 13.sp) }
                    IconButton(onClick = { keepVoice(recorder.stop()) }) {
                        Icon(Icons.Default.Stop, "Остановить", tint = PismoColors.Blurple)
                    }
                }
            }

            if (!recording) Row(
                Modifier
                    .fillMaxWidth()
                    .background(PismoColors.BgDarkest)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Кнопок четыре, и в стандартные 48 dp они съедали поле
                // ввода на узком экране. 40 dp — палец попадает, а строке
                // остаётся место для текста.
                IconButton(
                    onClick = { showAttach = true },
                    enabled = !sending,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Default.AttachFile, "Прикрепить", tint = PismoColors.TextMuted)
                }
                // Камера — то же, что в личной переписке: снять и отправить
                // одним нажатием, не выходя в галерею за снимком.
                IconButton(
                    onClick = { takePhoto() },
                    enabled = !sending,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Default.PhotoCamera, "Снять фото", tint = PismoColors.TextMuted)
                }
                IconButton(
                    onClick = {
                        if (recording) keepVoice(recorder.stop())
                        else recording = recorder.start()
                    },
                    enabled = !sending,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Default.Mic, "Голосовое", tint = PismoColors.TextMuted)
                }
                IconButton(
                    onClick = { showGifPicker = true },
                    enabled = !sending,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Default.Gif, "Гифки", tint = PismoColors.TextMuted)
                }

                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("Сообщение в #$channelName", color = PismoColors.TextMuted, fontSize = 14.sp)
                    },
                    maxLines = 5,
                    // ImeAction.None даёт обычный Enter, переводящий строку:
                    // с действием по умолчанию там «Готово», и переноса не набрать.
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
                IconButton(onClick = { send() }, enabled = !sending) {
                    Icon(
                        Icons.Default.Send, "Отправить",
                        tint = if (sending) PismoColors.TextMuted else PismoColors.Blurple,
                    )
                }
            }
        }
    }

    // Предпросмотр прикреплённого: снимок — сразу в редакторе, ролик — в
    // полноэкранном плеере.
    previewAttach?.let { idx ->
        val att = pending.getOrNull(idx)
        if (att == null) previewAttach = null
        else if (att.isImage) {
            com.pismo.messenger.ui.chat.PhotoEditor(
                bytes = att.bytes,
                onCancel = { previewAttach = null },
                onDone = { out ->
                    pending = pending.toMutableList().also {
                        it[idx] = PendingFile(out, att.fileName, true)
                    }
                    previewAttach = null
                },
            )
        } else {
            LaunchedEffect(idx) {
                val f = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val tmp = java.io.File(context.cacheDir, "attach-preview-" + idx)
                        tmp.writeBytes(att.bytes)
                        tmp
                    }.getOrNull()
                }
                previewAttach = null
                if (f != null) fullscreenVideo =
                    com.pismo.messenger.ui.chat.FullscreenVideo(f, att.fileName, 0)
                else jumpNote = "Не удалось открыть вложение."
            }
        }
    }

    com.pismo.messenger.ui.chat.FullscreenVideoHost(fullscreenVideo) { fullscreenVideo = null }

    if (forwardBatch.isNotEmpty()) {
        com.pismo.messenger.ui.chat.ForwardDialog(
            srcScope = Scope.SERVER,
            items = forwardBatch,
            onDismiss = { forwardBatch = emptyList() },
            onDone = {
                forwardBatch = emptyList()
                selectMode = false
                selectedIds = emptySet()
            },
        )
    }

    if (showSearch) {
        ChannelSearchDialog(
            channelId = channelId,
            onDismiss = { showSearch = false },
            onJump = { found ->
                showSearch = false
                // Прыгаем к сообщению, если оно есть на загруженной странице.
                // Номер переводим в номер элемента перевёрнутой ленты.
                val idx = feedIndexOf { it.id == found.id }
                if (idx >= 0) scope.launch {
                    flashId = found.id
                    listState.jumpToCenter(idx)
                    delay(1200)
                    if (flashId == found.id) flashId = 0
                }
            },
        )
    }

    mediaViewerAt?.let { openAt ->
        // Подпись у пачки одна на всех — та, что ушла с первым вложением.
        val captions = remember(messages) { com.pismo.messenger.core.Albums.captions(messages) }
        val media = messages
            .filter {
                !it.isDeleted && (it.hasImage ||
                    (it.hasFile && com.pismo.messenger.core.MediaKinds.isVideo(it.fileName)))
            }
            .map {
                com.pismo.messenger.ui.chat.MediaItem(
                    msgId = it.id,
                    isVideo = it.hasFile &&
                        com.pismo.messenger.core.MediaKinds.isVideo(it.fileName),
                    fileName = it.fileName,
                    sender = it.senderName,
                    timeMs = it.createdAtMs,
                    caption = captions[it.id].orEmpty(),
                )
            }
        val at = media.indexOfFirst { it.msgId == openAt }
        if (at >= 0) {
            com.pismo.messenger.ui.chat.MediaViewerHost(
                items = media,
                startIndex = at,
                scopeKind = Scope.SERVER,
                transferKey = Transfers.channelKey(channelId),
                onClose = { mediaViewerAt = null },
                target = channelId,
                onEdited = { out ->
                    pending = pending + PendingFile(
                        bytes = out, fileName = "drawing.jpg", isImage = true,
                    )
                },
                onShowInChat = { id ->
                    scope.launch {
                        // Просмотр листает весь канал, а лента держит только
                        // загруженную страницу — дотягиваем её до сообщения.
                        if (feedIndexOf { it.id == id } < 0) {
                            val need = ChatRepository.countSinceId(
                                Scope.SERVER, channelId, id
                            )
                            if (need > 0 && need + 5 > messages.size) {
                                pageLimit = need + 5
                                loading = true
                                reload()
                            }
                        }
                        val idx = feedIndexOf { it.id == id }
                        if (idx >= 0) {
                            flashId = id
                            listState.jumpToCenter(idx)
                            delay(1200)
                            if (flashId == id) flashId = 0
                        } else {
                            jumpNote = "Сообщение не найдено — возможно, удалено."
                        }
                    }
                },
                onReply = { id -> replyTo = messages.firstOrNull { it.id == id } },
            )
        } else {
            mediaViewerAt = null
        }
    }

    if (showAttach) {
        com.pismo.messenger.ui.chat.AttachSheet(
            onDismiss = { showAttach = false },
            onCamera = { takePhoto() },
            // Выбранное НЕ уходит сразу: прикрепляется к полю ввода, как в
            // личной переписке. Подпись из листа переезжает в поле.
            onSend = { picked, cap ->
                if (cap.isNotBlank()) {
                    val cur = input.text
                    val merged = if (cur.isBlank()) cap else cur.trimEnd() + " " + cap
                    input = TextFieldValue(merged, androidx.compose.ui.text.TextRange(merged.length))
                }
                scope.launch {
                    com.pismo.messenger.ui.chat.attachPicked(
                        context = context,
                        picked = picked,
                        used = pending.sumOf { it.bytes.size.toLong() },
                        onAdd = { added -> pending = pending + added },
                        note = { jumpNote = it },
                    )
                }
            },
        )
    }

    if (showGifPicker) {
        com.pismo.messenger.ui.chat.GifPickerDialog(
            onPicked = { bytes ->
                pending = pending + PendingFile(bytes = bytes, fileName = "giphy.gif", isImage = true)
            },
            onDismiss = { showGifPicker = false },
        )
    }

    if (showCalendar) {
        DateJumpDialog(
            onDismiss = { showCalendar = false },
            onPick = { dayStartMs ->
                showCalendar = false
                scope.launch {
                    val need = ChatRepository.countSince(Scope.SERVER, channelId, dayStartMs)
                    if (need <= 0) {
                        jumpNote = "За выбранную дату и позже сообщений нет."
                        return@launch
                    }
                    // Тянем на пяток больше нужного: иначе целевое сообщение
                    // окажется первым в ленте, без строчки контекста над ним.
                    if (need + 5 > messages.size) {
                        pageLimit = need + 5
                        loading = true
                        reload()
                    }
                    val idx = feedIndexOf { it.createdAtMs >= dayStartMs }
                    if (idx >= 0) {
                        listState.jumpToCenter(idx)
                        jumpNote = ""
                    } else {
                        jumpNote = "Не удалось найти сообщения за эту дату."
                    }
                }
            },
        )
    }

    if (showPins) {
        PinnedMessagesDialog(
            scopeKind = Scope.SERVER,
            targetId = channelId,
            onDismiss = { showPins = false },
        )
    }

    if (jumpNote.isNotBlank()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { jumpNote = "" },
            containerColor = PismoColors.BgSidebar,
            text = { Text(jumpNote, color = PismoColors.TextPrimary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { jumpNote = "" }) {
                    Text("Понятно", color = PismoColors.Cyan)
                }
            },
        )
    }
}
