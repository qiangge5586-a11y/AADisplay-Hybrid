plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.iosbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iosbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.2.8"
    }

    // Keep Java and Kotlin bytecode targets aligned for Gradle 8.7.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
