plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.civicmirror"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.civicmirror"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.6.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
\n\ndependencies {\n    testImplementation("junit:junit:4.13.2")\n}\n