plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.hondadash"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.hondadash"
        minSdk = 26
        targetSdk = 33
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("keystore/hondadash.jks")
            storePassword = "hondadash"
            keyAlias = "hondadash"
            keyPassword = "hondadash"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
