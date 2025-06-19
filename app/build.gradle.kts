import org.gradle.kotlin.dsl.libs
import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
}

// Load properties from local.properties
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        FileInputStream(localPropertiesFile).use { load(it) }
    }
}

// Extract the keys with default values as fallbacks
val livekitUrl = localProperties.getProperty("LIVEKIT_URL", "wss://solusvoiceassistant.livekit.cloud")
val tokenServerUrl = localProperties.getProperty("TOKEN_SERVER_URL", "http://192.168.36.95:5000/")
val porcupineAccessKey = localProperties.getProperty("PORCUPINE_ACCESS_KEY", "")

android {
    namespace = "com.example.solusfrontend"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.solusfrontend"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Expose secret keys and URLs as build config fields
        buildConfigField("String", "LIVEKIT_URL", "\"$livekitUrl\"")
        buildConfigField("String", "TOKEN_SERVER_URL", "\"$tokenServerUrl\"")
        buildConfigField("String", "PORCUPINE_ACCESS_KEY", "\"$porcupineAccessKey\"")
    }


    buildTypes {
        release {
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
    kotlinOptions {
        jvmTarget = "11"
    }
    
    // Enable Jetpack Compose
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
}

dependencies {
    // Core Android libraries
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.savedstate.ktx)
    
    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.timberkt)
    implementation(libs.androidx.animation)
    
    // LiveKit dependencies
    implementation(libs.livekit.compose.components)
    implementation(libs.livekit.android)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Porcupine Wake Word Detection    
    implementation (libs.porcupine.android)
    
    // Retrofit for API calls
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.logging.interceptor)
    
    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.cronet.embedded)
    implementation(libs.androidx.room.gradle.plugin)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
}