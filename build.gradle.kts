// Корневой build-файл.
//
// Версии плагинов объявлены в settings.gradle.kts, в блоке pluginManagement.
// Там это лишь версия по умолчанию: плагин достаётся тогда, когда его правда
// применяют. В корневом `plugins { … apply false }` Gradle разрешал бы каждый
// при настройке сборки, независимо от того, нужен он кому-нибудь или нет.

buildscript {
    // Плагин Firebase — здесь, а не в pluginManagement.
    //
    // Он применяется условно (`apply(plugin = …)` в app/build.gradle.kts),
    // потому что без google-services.json роняет сборку. А условное
    // применение через apply() берёт плагин С CLASSPATH сборочного скрипта:
    // версия, объявленная в pluginManagement, для него не действует — она
    // работает только для блока `plugins {}`. Без этих строк сборка с
    // настройками Firebase падала бы на «Plugin with id … not found».
    //
    // Само объявление тоже условное: у кого файла нет, тот и плагин не
    // качает.
    if (file("app/google-services.json").exists()) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.google.gms:google-services:4.4.2")
        }
    }
}
