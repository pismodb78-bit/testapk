plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pismo.messenger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pismo.messenger"
        minSdk = 24
        targetSdk = 35
        // versionCode ОБЯЗАН расти с каждой сборкой: Android ставит поверх
        // старой только версию с бо́льшим номером, и на равном откажет молча.
        // versionName — то, что видит человек.
        //
        // Оба значения переопределяются из командной строки: сборка релиза в
        // GitHub Actions считает их из тега (v1.2 → versionName 1.2,
        // versionCode 10200) — см. .github/workflows/release.yml. Здесь
        // остаются значения для сборки руками.
        versionCode = (providers.gradleProperty("pismoVersionCode").orNull)?.toIntOrNull() ?: 10400
        versionName = providers.gradleProperty("pismoVersionName").orNull ?: "1.4"
        vectorDrawables { useSupportLibrary = true }
    }

    /**
     * Подпись релиза.
     *
     * Обновление «из приложения» работает, только пока APK подписан ОДНИМ И
     * ТЕМ ЖЕ ключом: Android откажется ставить поверх сборку с другой
     * подписью. Поэтому ключ один, лежит в секретах репозитория и
     * подставляется через переменные окружения в GitHub Actions.
     *
     * Локальная сборка переменных не видит и подписывается отладочным
     * ключом, как и раньше, — но такой APK обновиться уже не сможет.
     */
    signingConfigs {
        create("release") {
            val storePath = System.getenv("PISMO_KEYSTORE")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("PISMO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PISMO_KEY_ALIAS")
                keyPassword = System.getenv("PISMO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName(
                if (System.getenv("PISMO_KEYSTORE").isNullOrBlank()) "debug" else "release"
            )
            // Минификация выключена: JDBC-драйвер и LiveKit активно используют
            // рефлексию, а неполные ProGuard-правила ломают их молча, уже в APK.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // Драйвер MariaDB и java.time требуют desugaring на minSdk 24.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/module-info.class",
                "META-INF/native-image/**",
                "driver-springboot-autoconfigure.json",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ── MySQL напрямую с устройства (как в ПК-версии — своего бэкенда нет) ──
    // 5.1.49 выбран намеренно: он уже проверен против боевого сервера
    // 85.174.248.59:3307 в репозитории PISMO_APK. Драйвер чистый Java 6,
    // без java.time/JMX, поэтому на Android заводится без плясок.
    // Альтернатива, если сервер переведут на caching_sha2_password (MySQL 8):
    //   implementation("org.mariadb.jdbc:mariadb-java-client:2.7.12")
    // и заменить префикс URL на jdbc:mariadb:// в Db.kt.
    implementation("mysql:mysql-connector-java:5.1.49")

    // ── WebSocket-сигналинг (тот же протокол, что у ws-сервера ПК-версии) ──
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ── Картинки и GIF в пузырях ──
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")

    // ── Камера для видео-кружочков ──
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    // ── Звонки ──
    // 2.28.0, а не 2.11.0, ровно из-за одной вещи: до 2.12 в Track.Source
    // не было SCREEN_SHARE_AUDIO, а с 2.24 в SDK лежит готовый
    // ScreenAudioCapturer, который берёт MediaProjection прямо у трека
    // демонстрации. Без него звук демки на Android пришлось бы просить
    // вторым системным диалогом — либо потерять совсем.
    implementation("io.livekit:livekit-android:2.28.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}
