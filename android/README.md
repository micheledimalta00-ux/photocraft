# PhotoCraft for Android

An experimental Android build: the same editor as the desktop app (`apps/photocraft-android`
wraps `PhotocraftApp`) running in a `NativeActivity`, rendered with wgpu on Vulkan or OpenGL ES.
The interface is still the desktop one, so it is most usable on a tablet or a phone in landscape.

## Get the APK from GitHub Actions

1. Open the repository's **Actions** tab, pick **android**, and press **Run workflow**.
2. When the run finishes (the first one takes 20–40 minutes; later ones are cached), download the
   **PhotoCraft-android** artifact at the bottom of the run page. It is a zip with the `.apk` inside.
3. Copy the `.apk` to the phone and open it; allow installing apps from that source when asked.

Without signing secrets each run signs with a fresh debug key, so installing a newer build over an
older one fails: uninstall first (preferences are lost). To keep updates installable, create a key
once and store it as repository secrets (Settings › Secrets and variables › Actions):

```sh
keytool -genkeypair -v -keystore photocraft.jks -alias photocraft -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 photocraft.jks   # → secret PHOTOCRAFT_KEYSTORE_BASE64
```

plus `PHOTOCRAFT_KEYSTORE_PASSWORD`, `PHOTOCRAFT_KEY_ALIAS` (`photocraft`) and
`PHOTOCRAFT_KEY_PASSWORD`.

## Build locally

Needs the Android SDK (platform 35) and NDK, Java 17, Gradle 8.9+, and `cargo install cargo-ndk`.

```sh
rustup target add aarch64-linux-android
cargo ndk -t arm64-v8a -P 26 -o android/app/src/main/jniLibs build --release -p photocraft-android --lib
gradle -p android assembleRelease
adb install android/app/build/outputs/apk/release/app-release.apk
adb logcat -s PhotoCraft:V RustStdoutStderr:V   # logs
```

To try the touch shell and file browser without a device:
`cargo run -p photocraft-android --example desktop`.

## Using it

- **Files**: File › Open and Save show an in-app browser over shared storage (Download,
  Pictures, Camera, Documents). On first use a banner asks for **All files access**; tap
  *Allow…*, enable it for PhotoCraft, and come back.
- **Interface size**: the **A− / A+** buttons at the bottom right scale the whole interface.
- **Keyboard**: the on-screen keyboard opens when a text field has focus.
- **Long press** opens egui context menus where the app uses them.

## Known limits

- arm64 phones and tablets only (Android 8.0+).
- No pen pressure yet, no HEIC (libheif needs the C++ toolchain), no plug-ins, no autosave.
- The desktop menus and panels are dense on a phone screen.
