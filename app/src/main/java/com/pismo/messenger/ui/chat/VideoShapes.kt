package com.pismo.messenger.ui.chat

/**
 * Пропорции роликов — чтобы пузырь видео был таким же, как сам ролик.
 *
 * Раньше любое видео показывалось в рамке 4:3. Снятое телефоном вертикальное
 * при этом занимало узкую полоску посреди чёрного поля, а широкое — наоборот,
 * обрезалось рамкой по высоте. В Telegram пузырь принимает форму ролика, и
 * здесь теперь так же.
 *
 * Узнать форму заранее неоткуда: размеры кадра знает только сам файл, а он
 * лежит на сервере. Поэтому первый показ идёт в обычных 4:3, а как только
 * появился первый кадр — форма запоминается, и дальше пузырь встаёт сразу
 * правильным. Память живёт до перезапуска: на диске её держать незачем, цена
 * ошибки — одна перерисовка.
 */
object VideoShapes {

    private const val DEFAULT = 4f / 3f
    /** Уже вертикального «девять на шестнадцать» не бывает. */
    private const val MIN = 0.5f
    /** И шире «двух к одному» тоже. */
    private const val MAX = 2.0f
    private const val MAX_REMEMBERED = 400

    private val ratios = object : LinkedHashMap<Int, Float>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Float>?): Boolean =
            size > MAX_REMEMBERED
    }

    fun ratio(msgId: Int): Float = synchronized(ratios) { ratios[msgId] ?: DEFAULT }

    fun remember(msgId: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val r = (width.toFloat() / height.toFloat()).coerceIn(MIN, MAX)
        synchronized(ratios) { ratios[msgId] = r }
    }
}
