package com.pismo.messenger

/**
 * На Android этот объект генерирует плагин сборки. Здесь его нет, а общий
 * код (отчёт о падении, проверка обновлений) на него ссылается, — поэтому
 * объявляем сами.
 *
 * Версия приходит из манифеста собранного пакета: jpackage кладёт её туда
 * из `packageVersion`, так что числа в отчёте и в установленном пакете
 * совпадают без второго места, где их надо помнить.
 */
object BuildConfig {

    @JvmField
    val VERSION_NAME: String =
        BuildConfig::class.java.`package`?.implementationVersion
            ?: System.getProperty("pismo.version")
            ?: "dev"

    const val DEBUG: String = ""
    const val APPLICATION_ID: String = "com.pismo.messenger"
}
