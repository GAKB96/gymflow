plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nz.gaurav.gymflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "nz.gaurav.gymflow"
        minSdk = 26
        targetSdk = 35
        // Every GitHub build gets a higher number so it installs as an update.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.$run"
    }

    // One fixed signing key, so every new build can update the installed app.
    signingConfigs {
        create("gymflow") {
            storeFile = file("gymflow.keystore")
            storePassword = "gymflow1"
            keyAlias = "gymflow"
            keyPassword = "gymflow1"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("gymflow")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("gymflow")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
