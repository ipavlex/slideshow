import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Секреты (параметры релизной подписи) хранятся в отдельном файле и читаются на сборке.
// Ключей Яндекс.Диска в сборке нет: вход — по WebDAV (логин + пароль приложения).
val secretsFile = rootProject.file("app/secrets.properties")
val secrets = Properties().apply {
    if (secretsFile.exists()) secretsFile.inputStream().use { load(it) }
}

// Релизная подпись: параметры в app/secrets.properties (не в git).
// Если конфига нет — release подписывается debug-ключом (sideload-сборка).
val releaseStoreFile = secrets.getProperty("RELEASE_STORE_FILE", "")
val releaseStorePassword = secrets.getProperty("RELEASE_STORE_PASSWORD", "")
val releaseKeyAlias = secrets.getProperty("RELEASE_KEY_ALIAS", "")
val releaseKeyPassword = secrets.getProperty("RELEASE_KEY_PASSWORD", "")
val hasReleaseSigning = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { it.isNotBlank() }

// Имя приложения (slug) и версия — используются в имени APK.
val appName = "tvslideshow"
val appVersionName = "0.10.0"
val appVersionCode = 40

android {
    namespace = "com.pzarubin.tvslideshow"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pzarubin.tvslideshow"
        minSdk = 28
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

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
        // BuildConfig не используется — ключей в сборке нет.
        buildConfig = false
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Собственная релизная подпись, при её отсутствии — debug-ключ
            // (sideload-сборка для установки по adb).
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    // Локальные нативные обёртки (например, libheif для HEIC).
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
}
