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
}
