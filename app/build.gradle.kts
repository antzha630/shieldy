import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val mapsApiKey = (localProperties.getProperty("MAPS_API_KEY") ?: "").trim()
val zeticPersonalKey = (localProperties.getProperty("ZETIC_PERSONAL_KEY") ?: "").trim()
val cloudRelayUrl = (localProperties.getProperty("CLOUD_RELAY_URL") ?: "").trim()
val cloudRelayApiKey = (localProperties.getProperty("CLOUD_RELAY_API_KEY") ?: "").trim()
val zeticModelName = (localProperties.getProperty("ZETIC_MODEL_NAME") ?: "antzha630/EchoShield").trim()

android {
    namespace = "com.echoshield.echonode"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.echoshield.echonode"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-mvp"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        manifestPlaceholders["CLOUD_RELAY_URL"] = cloudRelayUrl
        manifestPlaceholders["CLOUD_RELAY_API_KEY"] = cloudRelayApiKey

        buildConfigField("String", "ZETIC_KEY", "\"$zeticPersonalKey\"")
        buildConfigField("String", "ZETIC_MODEL_NAME", "\"$zeticModelName\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    androidResources {
        noCompress += "tflite"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose with Material 3
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")

    // ViewModel for Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Google Play Services - Nearby Connections (CRITICAL for P2P Mesh)
    implementation("com.google.android.gms:play-services-nearby:19.0.0")
    
    // Google Play Services - Location (for GPS)
    implementation("com.google.android.gms:play-services-location:21.1.0")
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.maps.android:maps-compose:4.3.3")

    // Cloud relay scaffolding
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // On-device audio ML inference (Zetic Melange with NPU acceleration)
    implementation("com.zeticai.mlange:mlange:1.6.1")
    // Legacy TensorFlow Lite YAMNet runtime for weighted dual-model voting.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.01.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
