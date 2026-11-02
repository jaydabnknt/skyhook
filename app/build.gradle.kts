plugins {
    id("com.android.application") // AGP 9 compiles Kotlin itself
}

// GeckoView version: CI passes -PgeckoviewVersion=<latest release>; "+" otherwise.
val geckoviewVersion: String = providers.gradleProperty("geckoviewVersion").getOrElse("+")

fun secret(name: String): String =
    (providers.gradleProperty(name).orNull ?: System.getenv(name) ?: "").trim()

android {
    namespace = "com.jaydabnknt.skyhook"
    // GeckoView 159+ requires API 37.2
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.jaydabnknt.skyhook"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"

        // Phone is arm64; keeping only this ABI keeps the APK ~3x smaller.
        ndk { abiFilters += listOf("arm64-v8a") }

        buildConfigField("String", "DROPBOX_APP_KEY", "\"${secret("DROPBOX_APP_KEY")}\"")
        buildConfigField("String", "YANDEX_CLIENT_ID", "\"${secret("YANDEX_CLIENT_ID")}\"")
    }

    signingConfigs {
        create("release") {
            val ks = file(System.getenv("KEYSTORE_FILE") ?: "release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "skyhook"
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += listOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:$geckoviewVersion")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.13.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    implementation("com.google.android.gms:play-services-auth:21.4.0")
}
