//! File › Open and Save through the system pickers, the way other Android apps do it:
//! Open asks "Gallery, Camera or Files?" and hands over the chosen pictures; Save As shows the
//! system "Save as" dialog. The Java side (`PhotoCraftActivity`) copies picked content into the
//! app cache, so PhotoCraft opens it by path as on the desktop; a save is written to a private
//! file first and then copied to the chosen document.
//!
//! Choosing a folder still uses the in-app browser ([`crate::picker`]): engine commands need a
//! real path, which the system folder picker doesn't give.

use std::cell::RefCell;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::rc::Rc;

use photocraft_ui_egui::i18n;
use photocraft_ui_egui::{FileDialogAnswer, FileDialogReply};

use crate::jni_bridge::PickResult;
use crate::picker::Picker;

/// Image extensions the gallery can supply; any other filter goes straight to the file picker.
const IMAGE_EXTS: &[&str] = &["png", "jpg", "jpeg", "webp", "gif", "bmp", "tif", "tiff", "heic", "heif", "dng"];

/// What the user is choosing from, or waiting on.
pub enum Pending {
    /// The in-app folder browser.
    Browser(Picker),
    /// The "Gallery / Camera / Files" sheet for File › Open.
    Chooser { multiple: bool, reply: FileDialogReply },
    /// A system picker is open; its answer arrives through [`crate::jni_bridge::take_result`].
    Waiting { reply: FileDialogReply, save_ext: Option<String> },
}

/// The dialogs shared between `Services::file_dialog`, the write service and the shell.
pub struct Dialogs {
    pub pending: Option<Pending>,
    /// Private file → the document "Save as" created for it: every write to the file is copied
    /// there, so File › Save keeps updating the same document.
    pub save_targets: HashMap<String, String>,
    /// Where saves are staged before they are copied to their document.
    pub save_dir: PathBuf,
    /// Saves staged so far (for unique folder names).
    saves: u64,
}

pub type Slot = Rc<RefCell<Dialogs>>;

impl Dialogs {
    pub fn new(data_dir: &Path) -> Slot {
        let save_dir = data_dir.join("saving");
        // Staged saves from earlier sessions belong to documents nobody has open any more.
        let _ = std::fs::remove_dir_all(&save_dir);
        Rc::new(RefCell::new(Self { pending: None, save_targets: HashMap::new(), save_dir, saves: 0 }))
    }

    /// Where a save named `name` is staged; `ext` is added when the user dropped the extension,
    /// since the format is chosen by it.
    fn stage_path(&mut self, name: &str, ext: Option<&str>) -> PathBuf {
        self.saves += 1;
        let mut file = crate::picker::sanitize_name(name);
        if Path::new(&file).extension().is_none()
            && let Some(ext) = ext.filter(|e| !e.is_empty())
        {
            file = format!("{file}.{ext}");
        }
        self.save_dir.join(self.saves.to_string()).join(file)
    }

    /// Handle a picker's answer to the pending request.
    fn finish(&mut self, result: PickResult) {
        let Some(Pending::Waiting { reply, save_ext }) = self.pending.take() else { return };
        let answer = match (result, save_ext) {
            (PickResult::Files(files), None) => Some(FileDialogAnswer::Paths(files.into_iter().map(|(_, path)| path).collect())),
            (PickResult::SaveTarget { uri, name }, Some(ext)) => {
                let path = self.stage_path(&name, Some(&ext));
                let key = path.to_string_lossy().to_string();
                self.save_targets.insert(key.clone(), uri);
                Some(FileDialogAnswer::SaveTo(key))
            }
            (PickResult::Error(e), _) => Some(FileDialogAnswer::Unavailable(e)),
            // Cancelled, or an answer that doesn't match the request.
            _ => None,
        };
        reply.send(answer);
    }

    /// Draw the open sheet or folder browser and collect system picker answers. Called every
    /// frame by the shell.
    pub fn frame(&mut self, ctx: &egui::Context, on_android: bool) {
        match self.pending.take() {
            None => {}
            Some(Pending::Browser(mut picker)) => {
                let done = picker.show(ctx, on_android, &mut || {
                    if let Err(e) = crate::jni_bridge::open_all_files_settings() {
                        log::error!("couldn't open the storage access settings: {e}");
                    }
                });
                if !done {
                    self.pending = Some(Pending::Browser(picker));
                }
            }
            Some(Pending::Chooser { multiple, reply }) => match open_sheet(ctx) {
                None => self.pending = Some(Pending::Chooser { multiple, reply }),
                Some(Source::Cancel) => reply.send(None),
                Some(source) => {
                    let started = match source {
                        Source::Gallery => crate::jni_bridge::pick_images(multiple),
                        Source::Camera => crate::jni_bridge::take_photo(),
                        Source::Files | Source::Cancel => crate::jni_bridge::pick_files(multiple),
                    };
                    match started {
                        Ok(()) => self.pending = Some(Pending::Waiting { reply, save_ext: None }),
                        Err(e) => reply.send(Some(FileDialogAnswer::Unavailable(e))),
                    }
                }
            },
            Some(waiting @ Pending::Waiting { .. }) => {
                self.pending = Some(waiting);
                match crate::jni_bridge::take_result() {
                    Ok(Some(result)) => self.finish(result),
                    Ok(None) => ctx.request_repaint_after(std::time::Duration::from_millis(200)),
                    Err(e) => self.finish(PickResult::Error(e)),
                }
            }
        }
    }
}

/// Where File › Open should look, for an Open request filtered to `extensions`.
pub fn wants_gallery(extensions: Option<&[String]>) -> bool {
    extensions.is_none_or(|exts| exts.iter().any(|e| IMAGE_EXTS.iter().any(|i| i.eq_ignore_ascii_case(e))))
}

/// The MIME type "Save as" is told about, by extension.
pub fn mime_for(ext: &str) -> &'static str {
    match ext.to_ascii_lowercase().as_str() {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "webp" => "image/webp",
        "gif" => "image/gif",
        "bmp" => "image/bmp",
        "tif" | "tiff" => "image/tiff",
        "psd" | "psb" => "image/vnd.adobe.photoshop",
        "svg" => "image/svg+xml",
        "pdf" => "application/pdf",
        _ => "application/octet-stream",
    }
}

/// Whether `path` is a private copy of something picked from another app (the gallery, a
/// document provider), which a plain File › Save must not silently write to.
pub fn is_picked_copy(path: &str) -> bool {
    path.contains("/cache/picked/")
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Source {
    Gallery,
    Camera,
    Files,
    Cancel,
}

/// The "Open from…" sheet. Returns the user's choice once they make one.
fn open_sheet(ctx: &egui::Context) -> Option<Source> {
    let mut chosen = None;
    let screen = ctx.content_rect();
    let width = (screen.width() - 32.0).clamp(220.0, 420.0);
    egui::Window::new(i18n::t("Open"))
        .id(egui::Id::new("photocraft_android_open_from"))
        .collapsible(false)
        .resizable(false)
        .fixed_size(egui::vec2(width, 0.0))
        .anchor(egui::Align2::CENTER_CENTER, egui::Vec2::ZERO)
        .order(egui::Order::Foreground)
        .show(ctx, |ui| {
            let size = egui::vec2(ui.available_width(), 44.0);
            for (source, label) in [
                (Source::Gallery, format!("🖼   {}", i18n::t("Gallery"))),
                (Source::Camera, format!("📷   {}", i18n::t("Take a Photo"))),
                (Source::Files, format!("📁   {}", i18n::t("Files"))),
            ] {
                if ui.add(egui::Button::new(label).min_size(size)).clicked() {
                    chosen = Some(source);
                }
            }
            ui.add_space(4.0);
            if ui.add(egui::Button::new(i18n::t("Cancel")).min_size(egui::vec2(size.x, 36.0))).clicked() {
                chosen = Some(Source::Cancel);
            }
        });
    if ctx.input(|i| i.key_pressed(egui::Key::Escape)) {
        chosen = Some(Source::Cancel);
    }
    chosen
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gallery_is_offered_for_pictures_only() {
        assert!(wants_gallery(None));
        assert!(wants_gallery(Some(&["PNG".to_string(), "abr".to_string()])));
        assert!(!wants_gallery(Some(&["abr".to_string(), "grd".to_string()])));
    }

    #[test]
    fn saves_are_staged_with_an_extension_and_unique_folders() {
        let dir = std::env::temp_dir().join(format!("photocraft-android-dialogs-{}", std::process::id()));
        let slot = Dialogs::new(&dir);
        let mut d = slot.borrow_mut();
        let a = d.stage_path("Holiday", Some("png"));
        let b = d.stage_path("Holiday.jpg", Some("png"));
        let c = d.stage_path("../x/evil", None);
        assert!(a.ends_with("Holiday.png"));
        assert!(b.ends_with("Holiday.jpg"));
        assert_ne!(a.parent(), b.parent());
        assert!(c.starts_with(&d.save_dir) && !c.to_string_lossy().contains(".."));
    }

    #[test]
    fn picked_copies_are_recognised() {
        assert!(is_picked_copy("/data/user/0/com.storytold.photocraft/cache/picked/123/0/IMG.jpg"));
        assert!(!is_picked_copy("/storage/emulated/0/Download/IMG.jpg"));
    }

    #[test]
    fn mime_types_by_extension() {
        assert_eq!(mime_for("JPG"), "image/jpeg");
        assert_eq!(mime_for("pcraft"), "application/octet-stream");
    }
}
