package com.pismo.messenger.ui.chat

import android.graphics.BitmapFactory

/**
 * Пропорции картинок в переписке.
 *
 * ЗАЧЕМ. Вложения едут отдельно от самих сообщений, и до их приезда пузырь
 * не занимает под них ни пикселя. Фотография появляется разом и сдвигает
 * вниз всё, что под ней, — это и есть «чат дёргает при прогрузке».
 *
 * Чтобы держать место заранее, нужна высота, а она зависит от пропорций
 * картинки. Берём их у самой картинки при первом показе — без
 * раскодирования пикселей, только заголовок файла, — и помним до закрытия
 * приложения. Первый показ ещё может дрогнуть, дальше заглушка встаёт ровно
 * той же высоты, и сдвига нет вовсе.
 *
 * На диск не пишем намеренно: это подсказка для глаза, а не данные. Список
 * ограничен — иначе он рос бы вместе с перепиской.
 */
internal object ImageShapes {

    /** Обычные пропорции фотографии — пока настоящие неизвестны. */
    private const val DEFAULT = 4f / 3f

    /** Совсем узкие и совсем длинные не держим: заглушка в пол-экрана хуже сдвига. */
    private const val MIN = 0.5f
    private const val MAX = 2.5f

    private const val MAX_REMEMBERED = 400

    private val shapes = object : LinkedHashMap<Int, Float>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Float>?): Boolean =
            size > MAX_REMEMBERED
    }

    fun ratio(msgId: Int): Float = synchronized(shapes) { shapes[msgId] ?: DEFAULT }

    fun remember(msgId: Int, bytes: ByteArray) {
        val r = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
            else bounds.outWidth.toFloat() / bounds.outHeight
        }.getOrNull() ?: return
        synchronized(shapes) { shapes[msgId] = r.coerceIn(MIN, MAX) }
    }
}
