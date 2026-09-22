// Корневой build-файл.
//
// Все плагины объявлены ЗДЕСЬ, с apply false, и применяются в модуле.
//
// Раньше они жили в settings.gradle.kts, в pluginManagement, и корневой
// файл был пустым. Причина была такая: в проекте лежал ещё настольный
// модуль на Compose Desktop, и объявление в корневом plugins заставляло
// Gradle разрешать андроидный плагин даже при сборке одной только
// настольной версии — на машине, где Google Maven может быть недоступен.
// Того модуля больше нет, и вместе с ним пропала причина.
//
// А появилась обратная. Плагин Firebase применяется УСЛОВНО (см.
// app/build.gradle.kts): без google-services.json он роняет сборку, а файл
// приватный. Условное применение — это apply(), а apply() берёт плагин из
// той же области видимости, где он объявлен. Пока Android-плагин
// объявлялся в модуле, а Firebase — в корне, они оказывались в РАЗНЫХ
// областях: родительская не видит классов дочерней, и сборка падала на
//
//   Could not generate a decorated class for type GoogleServicesPlugin
//     > com/android/build/api/variant/Variant
//
// то есть плагин Firebase не нашёл классов AGP. Объявление всех четырёх в
// одном месте это и решает.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}
