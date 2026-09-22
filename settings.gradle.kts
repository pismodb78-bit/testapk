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
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
        // Плагин Firebase. Применяется ТОЛЬКО когда рядом лежит
        // google-services.json — см. app/build.gradle.kts. Без файла он
        // роняет сборку с «File google-services.json is missing», а файл
        // этот приватный и в репозитории его нет и не будет.
        id("com.google.gms.google-services") version "4.4.2"
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

include(":app")
