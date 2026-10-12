//! An in-app file browser over shared storage (`/storage/emulated/0`).
//!
//! On Android it is only used to choose a folder (Open and Save go through the system pickers,
//! [`crate::system_dialogs`]): engine commands that work on a folder need a real path, which the
//! system folder picker doesn't give. Walking shared storage needs "All files access", which
//! [`crate::jni_bridge`] can open the settings page for. The desktop preview uses it for
//! everything.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use photocraft_ui_egui::i18n;
use photocraft_ui_egui::{FileDialogAnswer, FileDialogReply, FileDialogRequest};

/// Everything File › Open reads (the same list as the web build).
pub const OPEN_EXTS: &[&str] = &[
    "pcraft", "pdn", "ora", "psd", "psb", "psdt", "png", "jpg", "jpeg", "tif", "tiff", "webp", "gif", "bmp", "tga", "ico", "qoi", "exr", "hdr", "pbm", "pgm",
    "ppm", "pam", "pfm", "fits", "fit", "fts", "heic", "heif", "hif", "dng", "cr2", "cr3", "nef", "nrw", "arw", "pef", "orf", "rw2", "raf", "abr", "grd",
    "svg", "svgz", "aco", "ase", "af", "afdesign", "afphoto", "afpub",
];

/// Shared storage as apps see it.
pub const STORAGE_ROOT: &str = "/storage/emulated/0";

#[derive(Debug, Clone, PartialEq, Eq)]
enum Mode {
    Open { multiple: bool, extensions: Vec<String> },
    Save,
    Folder,
}

/// One entry of the current folder.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub name: String,
    pub path: PathBuf,
    pub is_dir: bool,
}

/// What the user did this frame.
enum Action {
    None,
    Navigate(PathBuf),
    Toggle(PathBuf),
    Answer(Option<FileDialogAnswer>),
}

pub struct Picker {
    mode: Mode,
    reply: Option<FileDialogReply>,
    dir: PathBuf,
    entries: Result<Vec<Entry>, String>,
    selected: BTreeSet<PathBuf>,
    name: String,
    /// Shortcuts to the usual shared folders, plus the app's own (always readable) folder.
    places: Vec<(String, PathBuf)>,
}

impl Picker {
    /// A picker for `request`, answering through `reply` when the user is done.
    pub fn new(request: FileDialogRequest, reply: FileDialogReply, app_dir: &Path) -> Self {
        let root = Path::new(STORAGE_ROOT);
        let places = vec![
            (i18n::t("Download").to_string(), root.join("Download")),
            (i18n::t("Pictures").to_string(), root.join("Pictures")),
            (i18n::t("Camera").to_string(), root.join("DCIM")),
            (i18n::t("Documents").to_string(), root.join("Documents")),
            (i18n::t("App folder").to_string(), app_dir.to_path_buf()),
        ];
        let default_dir = root.join("Download");
        let (mode, dir, name) = match request {
            FileDialogRequest::Open { multiple, initial_dir, extensions } => {
                let extensions = extensions.unwrap_or_else(|| OPEN_EXTS.iter().map(|e| (*e).to_string()).collect());
                (Mode::Open { multiple, extensions }, initial_dir.map(PathBuf::from), String::new())
            }
            FileDialogRequest::Folder { initial_dir } => (Mode::Folder, initial_dir.map(PathBuf::from), String::new()),
            FileDialogRequest::Save { suggested } => {
                let path = PathBuf::from(&suggested);
                let name = path.file_name().map_or_else(|| suggested.clone(), |n| n.to_string_lossy().to_string());
                (Mode::Save, path.parent().filter(|p| p.is_absolute()).map(Path::to_path_buf), name)
            }
        };
        let dir = dir.filter(|d| d.is_dir()).unwrap_or(default_dir);
        let mut picker = Self { mode, reply: Some(reply), dir: PathBuf::new(), entries: Ok(Vec::new()), selected: BTreeSet::new(), name, places };
        picker.navigate(dir);
        picker
    }

    fn navigate(&mut self, dir: PathBuf) {
        let filter = match &self.mode {
            Mode::Open { extensions, .. } => Some(extensions.as_slice()),
            Mode::Save | Mode::Folder => None,
        };
        self.entries = list(&dir, filter);
        self.selected.clear();
        self.dir = dir;
    }

    /// Answer the dialog and close it.
    fn answer(&mut self, answer: Option<FileDialogAnswer>) {
        if let Some(reply) = self.reply.take() {
            reply.send(answer);
        }
    }

    /// Draw the browser. Returns `true` once it has been answered (and should be dropped).
    pub fn show(&mut self, ctx: &egui::Context, can_grant_access: bool, grant_access: &mut dyn FnMut()) -> bool {
        let screen = ctx.content_rect();
        let size = egui::vec2((screen.width() - 24.0).clamp(240.0, 720.0), (screen.height() - 24.0).clamp(200.0, 640.0));
        let title = match self.mode {
            Mode::Open { .. } => i18n::t("Open"),
            Mode::Save => i18n::t("Save As"),
            Mode::Folder => i18n::t("Choose Folder"),
        };
        let mut action = Action::None;
        egui::Window::new(title)
            .id(egui::Id::new("photocraft_android_picker"))
            .collapsible(false)
            .resizable(false)
            .fixed_size(size)
            .anchor(egui::Align2::CENTER_CENTER, egui::Vec2::ZERO)
            .order(egui::Order::Foreground)
            .show(ctx, |ui| action = self.contents(ui, can_grant_access, grant_access));
        match action {
            Action::None => false,
            Action::Navigate(dir) => {
                self.navigate(dir);
                false
            }
            Action::Toggle(path) => {
                if !self.selected.remove(&path) {
                    self.selected.insert(path);
                }
                false
            }
            Action::Answer(answer) => {
                self.answer(answer);
                true
            }
        }
    }

    fn contents(&mut self, ui: &mut egui::Ui, can_grant_access: bool, grant_access: &mut dyn FnMut()) -> Action {
        let mut action = Action::None;
        let touch = egui::vec2(0.0, 36.0);
        ui.horizontal_wrapped(|ui| {
            for (label, path) in &self.places {
                if ui.add(egui::Button::new(label.as_str()).min_size(touch)).clicked() {
                    action = Action::Navigate(path.clone());
                }
            }
        });
        ui.horizontal(|ui| {
            let parent = self.dir.parent().map(Path::to_path_buf);
            if ui.add_enabled(parent.is_some(), egui::Button::new("⬆").min_size(egui::vec2(44.0, 36.0))).clicked()
                && let Some(parent) = parent
            {
                action = Action::Navigate(parent);
            }
            ui.add(egui::Label::new(self.dir.to_string_lossy()).truncate());
        });
        ui.separator();

        let footer = 96.0;
        let list_height = (ui.available_height() - footer).max(80.0);
        egui::ScrollArea::vertical().max_height(list_height).auto_shrink([false, false]).show(ui, |ui| match &self.entries {
            Err(e) => {
                ui.label(e.as_str());
                if can_grant_access {
                    ui.add_space(8.0);
                    ui.label(i18n::t("PhotoCraft needs “All files access” to browse your folders."));
                    if ui.add(egui::Button::new(i18n::t("Grant access…")).min_size(touch)).clicked() {
                        grant_access();
                    }
                    ui.label(i18n::t("After allowing it, come back and tap a folder above."));
                }
            }
            Ok(entries) if entries.is_empty() => {
                ui.weak(i18n::t("No files here."));
            }
            Ok(entries) => {
                for entry in entries {
                    let checked = self.selected.contains(&entry.path);
                    let icon = if entry.is_dir {
                        "📁"
                    } else if checked {
                        "☑"
                    } else {
                        "🖼"
                    };
                    let button = egui::Button::new(format!("{icon}  {}", entry.name)).frame(false).min_size(egui::vec2(ui.available_width(), 36.0));
                    if !ui.add(button).clicked() {
                        continue;
                    }
                    action = if entry.is_dir {
                        Action::Navigate(entry.path.clone())
                    } else {
                        match &self.mode {
                            Mode::Open { multiple: true, .. } => Action::Toggle(entry.path.clone()),
                            Mode::Open { multiple: false, .. } => Action::Answer(Some(FileDialogAnswer::Paths(vec![entry.path.to_string_lossy().to_string()]))),
                            // Tapping an existing file in Save picks its name (to overwrite it).
                            Mode::Save => {
                                self.name.clone_from(&entry.name);
                                Action::None
                            }
                            Mode::Folder => Action::None,
                        }
                    };
                }
            }
        });

        ui.separator();
        if self.mode == Mode::Save {
            ui.horizontal(|ui| {
                ui.label(i18n::t("Name:"));
                ui.add(egui::TextEdit::singleline(&mut self.name).desired_width(f32::INFINITY));
            });
        }
        ui.horizontal(|ui| {
            if ui.add(egui::Button::new(i18n::t("Cancel")).min_size(touch)).clicked() {
                action = Action::Answer(None);
            }
            let confirm = match &self.mode {
                Mode::Open { multiple: true, .. } => {
                    let label = i18n::fmt(i18n::t("Open {count}"), &[("count", &self.selected.len().to_string())]);
                    ui.add_enabled(!self.selected.is_empty(), egui::Button::new(label).min_size(touch))
                        .clicked()
                        .then(|| FileDialogAnswer::Paths(self.selected.iter().map(|p| p.to_string_lossy().to_string()).collect()))
                }
                Mode::Open { multiple: false, .. } => None,
                Mode::Save => {
                    let name = self.name.trim();
                    let valid = valid_file_name(name);
                    ui.add_enabled(valid, egui::Button::new(i18n::t("Save")).min_size(touch))
                        .clicked()
                        .then(|| FileDialogAnswer::SaveTo(self.dir.join(name).to_string_lossy().to_string()))
                }
                Mode::Folder => ui
                    .add_enabled(self.entries.is_ok(), egui::Button::new(i18n::t("Use This Folder")).min_size(touch))
                    .clicked()
                    .then(|| FileDialogAnswer::FolderTo(self.dir.to_string_lossy().to_string())),
            };
            if let Some(answer) = confirm {
                action = Action::Answer(Some(answer));
            }
        });
        action
    }
}

impl Drop for Picker {
    /// A picker replaced or dropped unanswered counts as Cancel.
    fn drop(&mut self) {
        self.answer(None);
    }
}

/// A plain file name: not empty, no separators, not `.`/`..`.
pub fn valid_file_name(name: &str) -> bool {
    !name.is_empty() && name != "." && name != ".." && !name.contains(['/', '\0'])
}

/// `name` as a single, harmless file name: separators and control characters become `_`, and
/// leading dots are dropped (no hidden files, no `..`).
pub fn sanitize_name(name: &str) -> String {
    let s: String = name.chars().map(|c| if matches!(c, '/' | '\\' | ':' | '\0') || c.is_control() { '_' } else { c }).collect();
    let s = s.trim().trim_start_matches('.').trim();
    if s.is_empty() { "image".to_string() } else { s.to_string() }
}

/// The visible entries of `dir`: folders first, then files whose extension is in `filter` (all
/// files when `None`), each group sorted case-insensitively. Hidden entries are skipped.
pub fn list(dir: &Path, filter: Option<&[String]>) -> Result<Vec<Entry>, String> {
    let read = std::fs::read_dir(dir).map_err(|e| match e.kind() {
        std::io::ErrorKind::PermissionDenied => i18n::t("This folder can’t be read: storage access was not granted.").to_string(),
        std::io::ErrorKind::NotFound => i18n::t("This folder doesn’t exist.").to_string(),
        _ => e.to_string(),
    })?;
    let mut entries: Vec<Entry> = read
        .filter_map(Result::ok)
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().to_string();
            if name.starts_with('.') {
                return None;
            }
            let path = e.path();
            let is_dir = path.is_dir();
            if !is_dir && let Some(exts) = filter {
                let ext = path.extension().map(|x| x.to_string_lossy().to_ascii_lowercase()).unwrap_or_default();
                if !exts.iter().any(|x| x.eq_ignore_ascii_case(&ext)) {
                    return None;
                }
            }
            Some(Entry { name, path, is_dir })
        })
        .collect();
    entries.sort_by(|a, b| b.is_dir.cmp(&a.is_dir).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())));
    Ok(entries)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lists_folders_first_and_filters_by_extension() {
        let dir = std::env::temp_dir().join(format!("photocraft-android-picker-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("zeta")).unwrap();
        std::fs::write(dir.join("b.PNG"), b"").unwrap();
        std::fs::write(dir.join("a.txt"), b"").unwrap();
        std::fs::write(dir.join(".hidden.png"), b"").unwrap();
        let exts = vec!["png".to_string()];
        let names: Vec<String> = list(&dir, Some(&exts)).unwrap().into_iter().map(|e| e.name).collect();
        assert_eq!(names, ["zeta", "b.PNG"]);
        let all: Vec<String> = list(&dir, None).unwrap().into_iter().map(|e| e.name).collect();
        assert_eq!(all, ["zeta", "a.txt", "b.PNG"]);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn missing_folder_is_an_error_not_a_panic() {
        assert!(list(Path::new("/definitely/not/here"), None).is_err());
    }

    #[test]
    fn file_names_are_checked() {
        assert!(valid_file_name("photo.png"));
        assert!(!valid_file_name(""));
        assert!(!valid_file_name(".."));
        assert!(!valid_file_name("a/b.png"));
    }
}
