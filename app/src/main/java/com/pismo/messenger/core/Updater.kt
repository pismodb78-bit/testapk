package com.pismo.messenger.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.pismo.messenger.PismoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Обновление приложения через GitHub Releases — порт Updater.cs с ПК.
 *
 * Как это устроено там: при запуске приложение спрашивает у GitHub последний
 * релиз, сравнивает версию с текущей и, если есть новее, предлагает обновиться;
 * качает архив и распаковывает его поверх себя. Здесь то же самое, но вместо
 * архива — APK, а вместо распаковки — системный установщик: заменить свои
 * файлы на ходу приложение на Android не может в принципе.
 *
 * ЧТО ОБЯЗАТЕЛЬНО ЗНАТЬ ПРО ПОДПИСЬ. Android поставит обновление поверх, только
 * если новый APK подписан ТЕМ ЖЕ ключом, что и установленный. Поэтому сборка
 * релиза живёт в GitHub Actions с одним постоянным ключом из секретов
 * репозитория (.github/workflows/release.yml). APK, собранный в Android Studio
 * отладочным ключом, этой цепочкой обновляться не будет — его придётся один
 * раз снести и поставить релизный.
 *
 * И ПРО versionCode. Система откажет молча, если у нового APK номер версии не
 * БОЛЬШЕ установленного. Номер считается из тега релиза (v1.2 → 10200), так
 * что достаточно не выпускать тег меньше предыдущего.
 */
object Updater {

    private const val OWNER = "pismodb78-bit"
    private const val REPO = "testapk"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** Данные последнего релиза — ровно то, что нужно показать и скачать. */
    data class Release(
        val tag: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val sizeBytes: Long,
    )

    sealed interface State {
        /** Ничего не делаем. */
        data object Idle : State

        /** Спрашиваем GitHub. */
        data object Checking : State

        /** Спросили — установлена свежая версия. */
        data object UpToDate : State

        /** Есть версия новее. */
        data class Available(val release: Release) : State

        /** Качаем; [percent] = −1, когда размер неизвестен. */
        data class Downloading(val percent: Int, val release: Release) : State

        /** APK на диске, можно запускать установщик. */
        data class Ready(val file: File, val release: Release) : State

        /** Не вышло. Текст показываем как есть — по нему видно причину. */
        data class Failed(val message: String) : State
    }

    /**
     * Своя область для скачивания. Не rememberCoroutineScope с экрана: уход
     * с экрана посреди закачки отменил бы её, а человек в этот момент как раз
     * листает чаты, ожидая, пока докачается.
     */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Проверку при запуске делаем один раз за жизнь процесса: перезаход на
     * вкладку не повод снова дёргать GitHub, у него лимит на анонимные
     * запросы.
     */
    @Volatile
    private var startupChecked = false

    /** Человеческая версия установленного приложения — «1.1». */
    val currentVersion: String get() = com.pismo.messenger.BuildConfig.VERSION_NAME

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            // Ассет отдаётся редиректом на objects.githubusercontent.com.
            .followRedirects(true)
            .build()
    }

    /**
     * Спрашивает GitHub про последний релиз.
     *
     * [manual] = true — проверку запросил человек кнопкой: тогда ему надо
     * ответить и в случае «всё свежее», и в случае ошибки. Автоматическая
     * проверка при запуске молчит, пока новой версии нет: приложение,
     * встречающее сообщением «обновлений нет», раздражает.
     */
    suspend fun check(manual: Boolean) {
        if (!manual && startupChecked) return
        startupChecked = true

        _state.value = State.Checking
        val release = try {
            withContext(Dispatchers.IO) { fetchLatest() }
        } catch (e: Exception) {
            _state.value = if (manual) State.Failed(e.message ?: "нет связи с GitHub")
            else State.Idle
            return
        }

        if (release == null || !isNewer(release.tag)) {
            _state.value = if (manual) State.UpToDate else State.Idle
            return
        }
        _state.value = State.Available(release)
    }

    /** Возврат в исходное состояние — «Позже» в диалоге. */
    fun dismiss() {
        _state.value = State.Idle
    }

    private fun fetchLatest(): Release? {
        val req = Request.Builder()
            .url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PISMO-Android-Updater")
            .build()

        http.newCall(req).execute().use { resp ->
            // 404 у публичного репозитория означает «релизов ещё нет» —
            // это не ошибка, о которой стоит говорить.
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IllegalStateException("GitHub ответил ${resp.code}")

            val root = JSONObject(resp.body?.string().orEmpty())
            val tag = root.optString("tag_name").trim()
            if (tag.isEmpty()) return null

            val assets = root.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val url = a.optString("browser_download_url")
                if (url.isEmpty()) continue
                return Release(
                    tag = tag,
                    notes = root.optString("body").trim(),
                    apkUrl = url,
                    apkName = name,
                    sizeBytes = a.optLong("size", 0L),
                )
            }
            return null
        }
    }

    /**
     * Качает APK во временную папку, отдавая проценты в [state].
     *
     * Файл кладём в кеш: если установку прервут, мусор уберёт система.
     * Прошлые скачивания стираем — держать на телефоне десяток APK незачем.
     */
    /** Запускает закачку в своей области — экран может уйти, она продолжится. */
    fun startDownload(release: Release) {
        if (_state.value is State.Downloading) return
        io.launch { download(release) }
    }

    private suspend fun download(release: Release) {
        val context = PismoApp.appContext
        _state.value = State.Downloading(-1, release)
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "updates").apply {
                    mkdirs()
                    listFiles()?.forEach { it.delete() }
                }
                val target = File(dir, release.apkName)

                val req = Request.Builder()
                    .url(release.apkUrl)
                    .header("User-Agent", "PISMO-Android-Updater")
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("GitHub ответил ${resp.code}")
                    val body = resp.body ?: error("пустой ответ")
                    val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes

                    body.byteStream().use { input ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastShown = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                    // Обновляем состояние только на смене
                                    // процента: иначе поток кадров упирается
                                    // в перерисовку, а не в сеть.
                                    if (pct != lastShown) {
                                        lastShown = pct
                                        _state.value = State.Downloading(pct, release)
                                    }
                                }
                            }
                        }
                    }
                }
                if (target.length() < 1024) error("файл пустой или битый")
                target
            }
            _state.value = State.Ready(file, release)
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "не удалось скачать")
        }
    }

    /**
     * Разрешено ли нам ставить APK.
     *
     * С Android 8 «неизвестные источники» выдаются не системе целиком, а
     * КАЖДОМУ приложению отдельно. Без этого установщик просто не откроется,
     * и выглядело бы это как «кнопка не работает».
     */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                context.packageManager.canRequestPackageInstalls()

    /** Открывает системный экран, где это разрешение и выдают. */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Отдаёт скачанный APK системному установщику. */
    fun install(context: Context, file: File) {
        if (!canInstall(context)) {
            openInstallPermission(context)
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_ACTIVITY_NEW_TASK
                    )
            )
        }.onFailure {
            _state.value = State.Failed(it.message ?: "установщик не открылся")
        }
    }

    /**
     * Новее ли [tag] установленной версии.
     *
     * Сравниваем числами по частям, а не строками: «1.10» строкой меньше
     * «1.9», хотя версия старше. Лишние символы (v, -beta) отбрасываем.
     */
    internal fun isNewer(tag: String): Boolean =
        compareVersions(parts(tag), parts(com.pismo.messenger.BuildConfig.VERSION_NAME)) > 0

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .takeWhile { it.isDigit() || it == '.' }
            .split('.')
            .mapNotNull { it.toIntOrNull() }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
