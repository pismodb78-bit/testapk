import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

/**
 * Настольная сборка PISMO — Linux (а заодно и всё, где есть JVM).
 *
 * Главное решение здесь — в `srcDir` ниже. Модуль НЕ копирует к себе код
 * телефона, а компилирует те же самые файлы из `app/src/main/java`. Копия
 * разошлась бы с оригиналом на первой же правке, и держать клиенты в
 * согласии стало бы невозможно; общие исходники этого не позволяют по
 * построению — правка в репозитории видна всем сборкам сразу.
 *
 * Работает это потому, что слой данных писался без оглядки на Android: все
 * одиннадцать репозиториев, модели, миграции и пул соединений не содержат
 * ни одного андроидного импорта. Остаток — Base64, Log и SharedPreferences —
 * закрыт заглушками в `src/main/kotlin/android`, повторяющими оригинальные
 * сигнатуры.
 */
java {
    // Без toolchain: берём ту JDK, на которой запущен Gradle, и целимся в 17.
    // Toolchain заставил бы машину сборки качать ровно ту версию, которой у
    // неё может не быть, — а нам подходит любая, начиная с семнадцатой.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }

    sourceSets["main"].kotlin.apply {
        srcDir("../app/src/main/java")

        // Экраны, звонки, камера и службы — насквозь андроидные. Настольные
        // версии живут здесь же, в `desktop`, и пишутся отдельно.
        //
        // Исключения относительны КАЖДОМУ корню исходников, включая наш
        // собственный, — поэтому настольные файлы лежат под `desktop/` и
        // называются иначе (LinkOpenerDesktop.kt), чтобы не попасть под
        // те же образцы.
        exclude("com/pismo/messenger/ui/**")
        exclude("com/pismo/messenger/call/**")
        exclude("com/pismo/messenger/media/**")
        exclude("com/pismo/messenger/service/**")
        exclude("com/pismo/messenger/PismoApp.kt")
        // Три файла общего слоя тоже завязаны на систему: открытие ссылки
        // через Intent, счётчик активных экранов Activity, установка APK.
        exclude("com/pismo/messenger/core/LinkOpener.kt")
        exclude("com/pismo/messenger/core/PresenceReporter.kt")
        exclude("com/pismo/messenger/core/Updater.kt")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Без него Compose на настольной JVM не знает, какой поток главный.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // Та же версия драйвера, что и на телефоне, и та же база bdauth.
    implementation("mysql:mysql-connector-java:5.1.49")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // org.json на Android входит в саму систему, на настольной JVM его нет.
    // Берём эталонную реализацию с теми же именами и сигнатурами — общий код
    // (кеш переписки, разбор ответов Giphy и сигналинга) правок не требует.
    implementation("org.json:json:20240303")
}

compose.desktop {
    application {
        mainClass = "com.pismo.messenger.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "pismo"
            packageVersion = project.findProperty("pismoVersionName")?.toString() ?: "1.0.0"
            description = "PISMO — мессенджер"
            vendor = "PISMO"

            linux {
                menuGroup = "Network"
                appCategory = "Network"
                shortcut = true          // ярлык в меню приложений
            }
        }
    }
}
