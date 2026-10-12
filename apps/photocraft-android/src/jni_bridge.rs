//! The Java side of the app: the system pickers in `PhotoCraftActivity` (gallery, camera,
//! documents, "Save as"), plus storage access for the in-app folder browser ("All files access",
//! Android 11+).
//!
//! This is the crate's only `unsafe` besides the exported entry point: turning the raw JavaVM and
//! Activity pointers that `ndk-context` holds into `jni` handles. Every call returns an error
//! instead of panicking, and a pending Java exception is cleared so it can't abort the app.
#![allow(unsafe_code)]

use jni::objects::{JObject, JObjectArray, JString, JValue};
use jni::{Env, JavaVM, jni_sig, jni_str};

/// The Android package name (must match `android/app/build.gradle.kts`).
pub const PACKAGE: &str = "com.storytold.photocraft";

/// Hands out the running Activity's JNI reference (see [`set_activity`]).
type ActivityFn = Box<dyn Fn() -> *mut std::ffi::c_void + Send + Sync>;

static ACTIVITY: std::sync::Mutex<Option<ActivityFn>> = std::sync::Mutex::new(None);

/// Tell the bridge how to reach the Activity. `ndk-context` only holds the *Application*, which
/// has none of `PhotoCraftActivity`'s methods (nor `startActivity` for results), so the entry
/// point passes a function asking `AndroidApp` for the current Activity reference.
pub fn set_activity(activity: ActivityFn) {
    *ACTIVITY.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(activity);
}

fn activity_ptr() -> Option<*mut std::ffi::c_void> {
    let guard = ACTIVITY.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    guard.as_ref().map(|f| f()).filter(|p| !p.is_null())
}

fn with_activity<T>(f: impl FnOnce(&mut Env, &JObject) -> jni::errors::Result<T>) -> Result<T, String> {
    let ctx = ndk_context::android_context();
    let Some(activity_raw) = activity_ptr() else {
        return Err("no Android activity".into());
    };
    if ctx.vm().is_null() {
        return Err("no Android context".into());
    }
    // SAFETY: ndk-context is initialised by android-activity before `android_main` runs with the
    // process's JavaVM, which lives as long as the process; the pointer was checked non-null.
    let vm = unsafe { JavaVM::from_raw(ctx.vm().cast()) };
    vm.attach_current_thread(|env| {
        // SAFETY: `activity_raw` is the global reference to the running Activity that
        // `AndroidApp::activity_as_ptr` returned just now; android-activity keeps it alive while
        // the `AndroidApp` lives (for the whole of `android_main`). It is only borrowed through
        // this local wrapper, never deleted.
        let activity = unsafe { JObject::from_raw(env, activity_raw.cast()) };
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

/// What a system picker answered (see `PhotoCraftActivity.takeResult`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PickResult {
    /// Picked content, copied into the app cache: (display name, path).
    Files(Vec<(String, String)>),
    /// The document "Save as" created: its content URI and display name.
    SaveTarget {
        uri: String,
        name: String,
    },
    Cancelled,
    Error(String),
}

impl PickResult {
    /// Parse the string array the activity hands back.
    pub fn parse(parts: &[String]) -> Self {
        match parts.split_first() {
            Some((kind, rest)) if kind == "files" => {
                let files: Vec<(String, String)> = rest.chunks_exact(2).filter_map(|p| Some((p.first()?.clone(), p.get(1)?.clone()))).collect();
                if files.is_empty() { Self::Cancelled } else { Self::Files(files) }
            }
            Some((kind, rest)) if kind == "save" => match rest {
                [uri, name, ..] => Self::SaveTarget { uri: uri.clone(), name: name.clone() },
                _ => Self::Error("the save dialog gave no destination".into()),
            },
            Some((kind, _)) if kind == "cancel" => Self::Cancelled,
            Some((kind, rest)) if kind == "error" => Self::Error(rest.first().cloned().unwrap_or_else(|| "unknown error".into())),
            _ => Self::Error("unexpected answer from the picker".into()),
        }
    }
}

/// The gallery (the system Photo Picker on Android 13+).
pub fn pick_images(multiple: bool) -> Result<(), String> {
    with_activity(|env, activity| {
        env.call_method(activity, jni_str!("pickImages"), jni_sig!("(Z)V"), &[JValue::Bool(multiple)])?;
        Ok(())
    })
}

/// Any file, from any storage provider.
pub fn pick_files(multiple: bool) -> Result<(), String> {
    with_activity(|env, activity| {
        env.call_method(activity, jni_str!("pickFiles"), jni_sig!("(Z)V"), &[JValue::Bool(multiple)])?;
        Ok(())
    })
}

/// A new photo from the camera app.
pub fn take_photo() -> Result<(), String> {
    with_activity(|env, activity| {
        env.call_method(activity, jni_str!("takePhoto"), jni_sig!("()V"), &[])?;
        Ok(())
    })
}

/// The system "Save as" dialog.
pub fn create_document(name: &str, mime: &str) -> Result<(), String> {
    with_activity(|env, activity| {
        let name = env.new_string(name)?;
        let mime = env.new_string(mime)?;
        env.call_method(
            activity,
            jni_str!("createDocument"),
            jni_sig!("(Ljava/lang/String;Ljava/lang/String;)V"),
            &[JValue::Object(&name), JValue::Object(&mime)],
        )?;
        Ok(())
    })
}

/// Copy the file at `path` over the document `uri` (from "Save as").
pub fn copy_to_uri(path: &str, uri: &str) -> Result<(), String> {
    let error = with_activity(|env, activity| {
        let path = env.new_string(path)?;
        let uri = env.new_string(uri)?;
        let out = env
            .call_method(
                activity,
                jni_str!("copyToUri"),
                jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
                &[JValue::Object(&path), JValue::Object(&uri)],
            )?
            .l()?;
        if out.is_null() {
            return Ok(None);
        }
        let text = env.cast_local::<JString>(out)?;
        Ok(Some(text.try_to_string(env)?))
    })?;
    match error {
        None => Ok(()),
        Some(e) => Err(e),
    }
}

/// The answer to the last picker, once it has one.
pub fn take_result() -> Result<Option<PickResult>, String> {
    let parts = with_activity(|env, activity| {
        let obj = env.call_method(activity, jni_str!("takeResult"), jni_sig!("()[Ljava/lang/String;"), &[])?.l()?;
        if obj.is_null() {
            return Ok(None);
        }
        let array = env.cast_local::<JObjectArray<JString>>(obj)?;
        let n = array.len(env)?;
        let mut parts = Vec::with_capacity(n.min(1024));
        for i in 0..n {
            let item = array.get_element(env, i)?;
            parts.push(if item.is_null() { String::new() } else { item.try_to_string(env)? });
        }
        Ok(Some(parts))
    })?;
    Ok(parts.map(|p| PickResult::parse(&p)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(v: &[&str]) -> Vec<String> {
        v.iter().map(|x| (*x).to_string()).collect()
    }

    #[test]
    fn picker_answers_parse_without_panicking() {
        assert_eq!(
            PickResult::parse(&s(&["files", "a.jpg", "/c/a.jpg", "b.png", "/c/b.png"])),
            PickResult::Files(vec![("a.jpg".into(), "/c/a.jpg".into()), ("b.png".into(), "/c/b.png".into())])
        );
        assert_eq!(PickResult::parse(&s(&["files"])), PickResult::Cancelled);
        assert_eq!(PickResult::parse(&s(&["files", "odd"])), PickResult::Cancelled);
        assert_eq!(PickResult::parse(&s(&["save", "content://x", "out.png"])), PickResult::SaveTarget { uri: "content://x".into(), name: "out.png".into() });
        assert!(matches!(PickResult::parse(&s(&["save"])), PickResult::Error(_)));
        assert_eq!(PickResult::parse(&s(&["cancel"])), PickResult::Cancelled);
        assert_eq!(PickResult::parse(&s(&["error", "boom"])), PickResult::Error("boom".into()));
        assert!(matches!(PickResult::parse(&[]), PickResult::Error(_)));
    }
}
