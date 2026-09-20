pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    /*
     * Версии плагинов объявлены здесь, а не в корневом build-файле.
     *
     * Разница не косметическая. В корневом `plugins { … apply false }`
     * Gradle разрешает КАЖДЫЙ плагин при настройке сборки — в том числе
     * андроидный, даже когда собирают одну только настольную версию. А он
     * лежит на Google Maven, которого на машине сборки под Linux может не
     * быть вовсе. Объявление в pluginManagement — это лишь версия по
     * умолчанию: плагин достаётся тогда, когда его правда применяют.
     */
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
        // Compose Multiplatform — тот же Compose, что на телефоне, только
        // для настольной JVM. 1.7.3 собрана под Kotlin 2.0.21.
        id("org.jetbrains.compose") version "1.7.3"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Обязателен для звонков: livekit-android зависит от
        // com.github.davidliu:audioswitch, который публикуется только на
        // JitPack и версионируется хешем коммита. Без этой строки сборка
        // падает на «Could not find com.github.davidliu:audioswitch».
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PISMO"

// -PdesktopOnly=true — собирать только настольную версию.
//
// Нужно там, где нет Android SDK или доступа к Google Maven: сборка Linux к
// ним отношения не имеет, но Gradle настраивает ВСЕ модули подряд и без
// этого спотыкался бы об андроидный плагин.
if (providers.gradleProperty("desktopOnly").orNull != "true") include(":app")
// Настольная сборка (Linux и всё, где есть JVM). Компилирует общий слой
// данных из :app — см. пояснение в desktop/build.gradle.kts.
include(":desktop")
