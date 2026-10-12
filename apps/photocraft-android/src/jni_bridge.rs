//! The few Java calls a native activity needs: whether the app may read shared storage, and
//! opening the settings page that grants it ("All files access", Android 11+).
//!
//! This is the crate's only `unsafe` besides the exported entry point: turning the raw JavaVM and
//! Activity pointers that `ndk-context` holds into `jni` handles. Every call returns an error
//! instead of panicking, and a pending Java exception is cleared so it can't abort the app.
#![allow(unsafe_code)]

use jni::objects::{JObject, JValue};
use jni::{Env, JavaVM, jni_sig, jni_str};

/// The Android package name (must match `android/app/build.gradle.kts`).
pub const PACKAGE: &str = "com.storytold.photocraft";

fn with_activity<T>(f: impl FnOnce(&mut Env, &JObject) -> jni::errors::Result<T>) -> Result<T, String> {
    let ctx = ndk_context::android_context();
    if ctx.vm().is_null() || ctx.context().is_null() {
        return Err("no Android context".into());
    }
    // SAFETY: ndk-context is initialised by android-activity before `android_main` runs with the
    // process's JavaVM, which lives as long as the process; the pointer was checked non-null.
    let vm = unsafe { JavaVM::from_raw(ctx.vm().cast()) };
    vm.attach_current_thread(|env| {
        // SAFETY: `context()` is a global reference to the running Activity held by
        // android-activity for the life of the process, so it is a valid jobject here; it is
        // only borrowed (never deleted) through this local wrapper.
        let activity = unsafe { JObject::from_raw(env, ctx.context().cast()) };
        let result = f(env, &activity);
        if env.exception_check() {
            env.exception_clear();
        }
        // `JObject` never deletes its reference on drop, so android-activity's global reference
        // stays intact.
        result
    })
    .map_err(|e: jni::errors::Error| e.to_string())
}

/// The device's Android API level (`Build.VERSION.SDK_INT`).
pub fn sdk_int() -> Result<i32, String> {
    with_activity(|env, _| env.get_static_field(jni_str!("android/os/Build$VERSION"), jni_str!("SDK_INT"), jni_sig!("I"))?.i())
}

const WRITE_STORAGE: &str = "android.permission.WRITE_EXTERNAL_STORAGE";

/// Whether PhotoCraft may read and write shared storage by path: "All files access" on Android
/// 11+, the storage runtime permission before that.
pub fn has_all_files_access() -> Result<bool, String> {
    if sdk_int()? < 30 {
        return with_activity(|env, activity| {
            let name = env.new_string(WRITE_STORAGE)?;
            let state = env.call_method(activity, jni_str!("checkSelfPermission"), jni_sig!("(Ljava/lang/String;)I"), &[JValue::Object(&name)])?.i()?;
            // PackageManager.PERMISSION_GRANTED
            Ok(state == 0)
        });
    }
    with_activity(|env, _| env.call_static_method(jni_str!("android/os/Environment"), jni_str!("isExternalStorageManager"), jni_sig!("()Z"), &[])?.z())
}

/// Ask for storage access: the "All files access" settings page on Android 11+, the system
/// permission prompt before that.
pub fn open_all_files_settings() -> Result<(), String> {
    if sdk_int()? < 30 {
        return with_activity(|env, activity| {
            let name = env.new_string(WRITE_STORAGE)?;
            let names = env.new_object_array(1, jni_str!("java/lang/String"), &name)?;
            env.call_method(activity, jni_str!("requestPermissions"), jni_sig!("([Ljava/lang/String;I)V"), &[JValue::Object(&names), JValue::Int(1)])?;
            Ok(())
        });
    }
    open_settings_page().or_else(|e| {
        // Some vendor builds lack the per-app page; the list of all apps still works.
        log::warn!("per-app storage settings unavailable ({e}); opening the app list");
        with_activity(|env, activity| {
            let action = env.new_string("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION")?;
            let intent = env.new_object(jni_str!("android/content/Intent"), jni_sig!("(Ljava/lang/String;)V"), &[JValue::Object(&action)])?;
            env.call_method(activity, jni_str!("startActivity"), jni_sig!("(Landroid/content/Intent;)V"), &[JValue::Object(&intent)])?;
            Ok(())
        })
    })
}

fn open_settings_page() -> Result<(), String> {
    with_activity(|env, activity| {
        let action = env.new_string("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION")?;
        let uri_text = env.new_string(format!("package:{PACKAGE}"))?;
        let uri = env
            .call_static_method(
                jni_str!("android/net/Uri"),
                jni_str!("parse"),
                jni_sig!("(Ljava/lang/String;)Landroid/net/Uri;"),
                &[JValue::Object(&uri_text)],
            )?
            .l()?;
        let intent = env.new_object(
            jni_str!("android/content/Intent"),
            jni_sig!("(Ljava/lang/String;Landroid/net/Uri;)V"),
            &[JValue::Object(&action), JValue::Object(&uri)],
        )?;
        env.call_method(activity, jni_str!("startActivity"), jni_sig!("(Landroid/content/Intent;)V"), &[JValue::Object(&intent)])?;
        Ok(())
    })
}
