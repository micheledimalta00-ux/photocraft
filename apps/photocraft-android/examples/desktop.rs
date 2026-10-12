//! The Android shell in a desktop window: `cargo run -p photocraft-android --example desktop`.

#[cfg(not(target_os = "android"))]
fn main() -> eframe::Result {
    photocraft_android::run_desktop()
}

#[cfg(target_os = "android")]
fn main() {}
