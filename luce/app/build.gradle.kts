plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.michele.luce"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.michele.luce"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
        vectorDrawables { useSupportLibrary = true }
        ndk {
            // MediaPipe: telefoni ARM a 64 e 32 bit ed emulatori x86_64
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("luce") {
            storeFile = rootProject.file("keystore/luce.jks")
            storeType = "pkcs12"
            storePassword = "luce-open-source"
            keyAlias = "luce"
            keyPassword = "luce-open-source"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("luce")
        }
        debug {
            signingConfig = signingConfigs.getByName("luce")
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
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }
    androidResources {
        // i modelli devono restare non compressi per essere mappati in memoria
        noCompress += listOf("tflite", "task")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.32")

    testImplementation("junit:junit:4.13.2")
}
