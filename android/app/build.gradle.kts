plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.pult.pc"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.pult.pc"
        minSdk = 28          // Bluetooth-клавиатура/мышь появилась в Android 9 (API 28)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
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
