import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Секреты (client_id и т.п.) хранятся в отдельном файле и читаются на сборке.
val secretsFile = rootProject.file("app/secrets.properties")
val secrets = Properties().apply {
    if (secretsFile.exists()) secretsFile.inputStream().use { load(it) }
}
val yandexClientId = secrets.getProperty("YANDEX_CLIENT_ID", "")
val yandexClientSecret = secrets.getProperty("YANDEX_CLIENT_SECRET", "")

// Имя приложения (slug) и версия — используются в имени APK.
val appName = "tvslideshow"
val appVersionName = "0.9.9"
val appVersionCode = 23

android {
    namespace = "com.pzarubin.tvslideshow"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pzarubin.tvslideshow"
        minSdk = 28
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "YANDEX_CLIENT_ID", "\"$yandexClientId\"")
        buildConfigField("String", "YANDEX_CLIENT_SECRET", "\"$yandexClientSecret\"")

        ndk {
            // Нативный HEIC-декодер (libheif) собирается для этих ABI.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sideload: подписываем release тем же debug-ключом для удобной установки.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Имя APK: <name>-<versionName>.apk (например tvslideshow-0.5.0.apk).
    applicationVariants.configureEach {
        for (output in outputs) {
            (output as BaseVariantOutputImpl).outputFileName = "$appName-$appVersionName.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    // Локальные нативные обёртки (например, libheif для HEIC).
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
}
