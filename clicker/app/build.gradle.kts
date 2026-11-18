plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.bloosim.clicker"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.bloosim.clicker"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // One fixed key, so every new build installs over the old one and keeps your schemes.
    signingConfigs {
        create("fixed") {
            storeFile = file("clicker-signing.keystore")
            storePassword = "clicker-app"
            keyAlias = "clicker"
            keyPassword = "clicker-app"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("debug") {
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
        abortOnError = false
        checkReleaseBuilds = false
    }
}
