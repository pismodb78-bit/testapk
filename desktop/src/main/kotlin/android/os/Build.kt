package android.os

/**
 * Настольная замена `android.os.Build` — см. пояснение у android.util.Base64.
 *
 * Общий код зовёт это ровно в одном месте: строка «на чём запущено» в отчёте
 * о падении. Подставляем то же самое про машину: производителя заменяет имя
 * системы, модель — архитектура, версию Android — версия ядра.
 */
object Build {

    @JvmField val MANUFACTURER: String = System.getProperty("os.name") ?: "Linux"
    @JvmField val MODEL: String = System.getProperty("os.arch") ?: "x86_64"
    @JvmField val DEVICE: String = "desktop"

    object VERSION {
        @JvmField val RELEASE: String = System.getProperty("os.version") ?: "?"
        /**
         * Общий код кое-где смотрит на уровень API. На настольной сборке
         * андроидных ограничений нет ни одного, поэтому отвечаем числом,
         * которое заведомо больше любой проверки «если новее чем…».
         */
        @JvmField val SDK_INT: Int = 10_000
    }

    object VERSION_CODES {
        const val N = 24
        const val O = 26
        const val P = 28
        const val Q = 29
        const val R = 30
        const val S = 31
        const val TIRAMISU = 33
        const val UPSIDE_DOWN_CAKE = 34
        const val VANILLA_ICE_CREAM = 35
    }
}
