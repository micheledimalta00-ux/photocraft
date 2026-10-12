//! PhotoCraft on Android.
//!
//! Runs the same [`photocraft_ui_egui::PhotocraftApp`] as the desktop app inside a
//! `NativeActivity` (eframe's winit backend, wgpu on Vulkan or GLES). Build the APK with the
//! Gradle project in `android/`; see `android/README.md`.
//!
//! Differences from the desktop app:
//! - no TCP control server, plug-ins host or crash-recovery autosave;
//! - File › Open offers the gallery, the camera or any file, and Save As uses the system "Save as"
//!   dialog ([`system_dialogs`], through `PhotoCraftActivity` in `android/`);
//! - preferences live in the app's private folder;
//! - "A− / A+" buttons step Preferences › Interface › UI Scale, since the desktop layout needs
//!   more points than a phone has at its native scale (a first launch picks a smaller scale).

#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

pub mod jni_bridge;
pub mod picker;
pub mod services;
pub mod shell;
pub mod system_dialogs;

/// The entry point `NativeActivity` calls (through android-activity) once the native library is
/// loaded.
#[cfg(target_os = "android")]
#[allow(unsafe_code)]
// SAFETY: exporting the symbol by name is how android-activity finds the entry point; it is
// defined exactly once in this library and has the signature android-activity expects.
#[unsafe(no_mangle)]
fn android_main(android: winit::platform::android::activity::AndroidApp) {
    android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Info).with_tag("PhotoCraft"));
    let data_dir = android.internal_data_path().unwrap_or_else(|| std::path::PathBuf::from("/data/data").join(jni_bridge::PACKAGE).join("files"));
    let activity_app = android.clone();
    jni_bridge::set_activity(Box::new(move || activity_app.activity_as_ptr()));
    let keyboard_app = android.clone();
    let keyboard: shell::KeyboardFn = Box::new(move |show| {
        if show {
            keyboard_app.show_soft_input(true);
        } else {
            keyboard_app.hide_soft_input(false);
        }
    });
    let mut options = eframe::NativeOptions { android_app: Some(android), renderer: eframe::Renderer::Wgpu, ..Default::default() };
    photocraft_ui_egui::gpu_canvas::use_adapter_limits(&mut options.wgpu_options.wgpu_setup);
    let result = eframe::run_native("PhotoCraft", options, Box::new(move |cc| Ok(Box::new(shell::AndroidShell::new(cc, data_dir, Some(keyboard), true)))));
    if let Err(e) = result {
        log::error!("PhotoCraft failed to start: {e}");
    }
}

/// Run the Android shell in a desktop window, for trying the touch layout and the file browser
/// without a device (`cargo run -p photocraft-android --example desktop`).
#[cfg(not(target_os = "android"))]
pub fn run_desktop() -> eframe::Result {
    let data_dir = std::env::temp_dir().join("photocraft-android-preview");
    let options = eframe::NativeOptions {
        renderer: eframe::Renderer::Wgpu,
        viewport: egui::ViewportBuilder::default().with_inner_size([915.0, 412.0]).with_title("PhotoCraft (Android preview)"),
        ..Default::default()
    };
    eframe::run_native("PhotoCraft", options, Box::new(move |cc| Ok(Box::new(shell::AndroidShell::new(cc, data_dir, None, false)))))
}
