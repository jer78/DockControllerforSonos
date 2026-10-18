plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "io.github.jer78.dockcontroller"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jer78.dockcontroller"
        minSdk = 26
        targetSdk = 35
        versionCode = 34
        versionName = "1.0.33"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Release signing comes from the user-level ~/.gradle/gradle.properties
    // (DOCK_RELEASE_*), never from the repo. Without those properties the
    // release build is left unsigned.
    signingConfigs {
        create("release") {
            val storeFilePath = providers.gradleProperty("DOCK_RELEASE_STORE_FILE").orNull
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("DOCK_RELEASE_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("DOCK_RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("DOCK_RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            if (providers.gradleProperty("DOCK_RELEASE_STORE_FILE").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        buildPython("py", "-3.12")
        pip {
            install("soco")
            install("requests")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // DataStore, Coil for album art, OkHttp for HA API, Palette for the
    // album-art spectrum color scheme, and Extended Icons
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.material.icons.extended)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}