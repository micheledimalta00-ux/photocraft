// The APK wraps the Rust library `libphotocraft_android.so` in a NativeActivity: there is no
// Java or Kotlin code (android:hasCode="false"). Build the library first into src/main/jniLibs:
//   cargo ndk -t arm64-v8a -P 26 -o android/app/src/main/jniLibs build --release -p photocraft-android --lib
// then `gradle -p android assembleRelease`. See android/README.md.

plugins {
    id("com.android.application")
}

// Release signing from the environment (CI secrets); without it the release APK is signed with
// the debug key so it still installs.
val keystorePath: String? = System.getenv("PHOTOCRAFT_KEYSTORE")

android {
    namespace = "com.storytold.photocraft"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.storytold.photocraft"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("PHOTOCRAFT_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("PHOTOCRAFT_VERSION_NAME") ?: "0.8.2"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("PHOTOCRAFT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PHOTOCRAFT_KEY_ALIAS")
                keyPassword = System.getenv("PHOTOCRAFT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            // Keep the .so uncompressed and page-aligned so it loads straight from the APK.
            useLegacyPackaging = false
        }
    }
}
