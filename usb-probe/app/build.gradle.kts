plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.nitsuya.aadisplay.usbprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.nitsuya.aadisplay.usbprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
