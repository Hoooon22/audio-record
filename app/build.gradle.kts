plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hoooon22.audiorecord"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hoooon22.audiorecord"
        // AudioPlaybackCapture (내부 미디어 소리 녹음)는 Android 10(API 29)부터 지원됩니다.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
