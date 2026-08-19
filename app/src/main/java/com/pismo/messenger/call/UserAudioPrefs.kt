package com.pismo.messenger.call

import com.pismo.messenger.core.Prefs

/**
 * Индивидуальная громкость и «заглушить» по каждому собеседнику — порт
 * UserAudioPrefs.cs.
 *
 * Настройка привязана к id участника и переживает перезапуск: если у
 * человека вечно тихий микрофон и его один раз вывели на 200 %, делать это
 * заново в каждом звонке не нужно. Ровно так же на ПК.
 *
 * Хранится одной строкой в настройках: `id=громкость:мьют`, по записи на
 * строку. Заводить ради двух чисел на человека JSON-файл, как на ПК,
 * незачем — SharedPreferences здесь и есть тот файл.
 */
object UserAudioPrefs {

    private val cache = HashMap<String, Pair<Float, Boolean>>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        Prefs.userAudio.lineSequence().forEach { line ->
            val eq = line.indexOf('=')
            val colon = line.lastIndexOf(':')
            if (eq <= 0 || colon <= eq) return@forEach
            val id = line.substring(0, eq)
            val vol = line.substring(eq + 1, colon).toFloatOrNull() ?: return@forEach
            val muted = line.substring(colon + 1) == "1"
            cache[id] = vol.coerceIn(0f, 3f) to muted
        }
    }

    private fun save() {
        Prefs.userAudio = cache.entries.joinToString("\n") { (id, e) ->
            "$id=${e.first}:${if (e.second) 1 else 0}"
        }
    }

    @Synchronized
    fun volumeOf(identity: String): Float {
        ensureLoaded()
        return cache[identity]?.first ?: 1f
    }

    @Synchronized
    fun isMuted(identity: String): Boolean {
        ensureLoaded()
        return cache[identity]?.second ?: false
    }

    @Synchronized
    fun setVolume(identity: String, volume: Float) {
        ensureLoaded()
        val prev = cache[identity]
        cache[identity] = volume.coerceIn(0f, 3f) to (prev?.second ?: false)
        save()
    }

    @Synchronized
    fun setMuted(identity: String, muted: Boolean) {
        ensureLoaded()
        val prev = cache[identity]
        cache[identity] = (prev?.first ?: 1f) to muted
        save()
    }

    /**
     * Всё сохранённое разом — этим звонок заполняется на входе.
     *
     * Именно списком, а не «спросим, когда участник появится»: настройка
     * должна подхватиться и для тех, кто был в комнате раньше нас, и для
     * тех, у кого нет ни камеры, ни демонстрации.
     */
    @Synchronized
    fun snapshot(): Map<String, Pair<Float, Boolean>> {
        ensureLoaded()
        return HashMap(cache)
    }
}
