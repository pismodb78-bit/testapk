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
