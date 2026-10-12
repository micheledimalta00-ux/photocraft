//! Platform services for Android: files by path (shared storage), preferences in the app's
//! private folder, and the in-app file browser in place of system dialogs.

use std::path::{Path, PathBuf};

use photocraft_codecs::{ChannelLayout, EncodeOptions, Image};
use photocraft_doc::Document;
use photocraft_ui_egui::Services;

use crate::picker::{Picker, Slot};

/// Where the preferences file lives inside the app's private folder.
pub fn prefs_path(data_dir: &Path) -> PathBuf {
    // In a subfolder so it stays out of the way when browsing the app folder for documents.
    data_dir.join(".config").join("preferences.json")
}

/// Write `bytes` to `path`, creating its folder, through a temporary file so a failed write never
/// leaves a half-written document behind.
pub fn write_file(path: &Path, bytes: &[u8]) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    }
    let name = path.file_name().ok_or_else(|| format!("{}: not a file name", path.display()))?;
    let tmp = path.with_file_name(format!(".{}.partial", name.to_string_lossy()));
    std::fs::write(&tmp, bytes).map_err(|e| format!("{}: {e}", path.display()))?;
    std::fs::rename(&tmp, path).map_err(|e| {
        let _ = std::fs::remove_file(&tmp);
        format!("{}: {e}", path.display())
    })
}

pub fn services(data_dir: PathBuf, picker: Slot) -> Services {
    let prefs = prefs_path(&data_dir);
    let prefs_out = prefs.clone();
    let app_dir = data_dir.clone();
    Services {
        import: Some(Box::new(|name: &str, bytes: &[u8], max_svg_group_depth: usize| {
            photocraft_io::import_with_svg_group_depth(name, bytes, max_svg_group_depth).map(|r| (r.document, r.warnings)).map_err(|e| e.to_string())
        })),
        export: Some(Box::new(|doc: &Document, path: &str, settings: &photocraft_ui_egui::ExportSettings| {
            let mut opts = photocraft_io::ExportOptions::default();
            if let Some(q) = settings.jpeg_quality {
                opts.encode.jpeg_quality = q;
            }
            opts.encode.webp_lossless = settings.webp_lossless;
            if let Some(q) = settings.webp_quality {
                opts.encode.webp_quality = q;
            }
            opts.tiff_layers = settings.tiff_layers;
            opts.xmp = if settings.xmp_all { photocraft_io::XmpEmbed::All } else { photocraft_io::XmpEmbed::None };
            opts.tga_bits = settings.tga_bits;
            opts.image_previews = settings.image_previews;
            photocraft_io::export(doc, path, &opts).map(|r| (r.bytes, r.warnings)).map_err(|e| e.to_string())
        })),
        file_dialog: Some(Box::new(move |request, _frame, reply| {
            // A newer dialog replaces one still open; dropping the old one answers it with Cancel.
            *picker.borrow_mut() = Some(Picker::new(request, reply, &app_dir));
        })),
        write: Some(Box::new(|path: &str, bytes: &[u8]| write_file(Path::new(path), bytes))),
        encode_png: Some(Box::new(|w, h, rgba| {
            let img = Image::from_u8(w, h, ChannelLayout::Rgba, rgba.to_vec()).map_err(|e| e.to_string())?;
            photocraft_codecs::encode(&img, photocraft_codecs::Format::Png, &EncodeOptions::default()).map_err(|e| e.to_string())
        })),
        load_prefs: Some(Box::new(move || std::fs::read_to_string(&prefs).ok())),
        save_prefs: Some(Box::new(move |text: &str| write_file(&prefs_out, text.as_bytes()))),
        ..Default::default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_through_a_temporary_file_and_creates_folders() {
        let dir = std::env::temp_dir().join(format!("photocraft-android-write-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let path = dir.join("nested").join("out.png");
        write_file(&path, b"abc").unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), b"abc");
        assert!(!dir.join("nested").join(".out.png.partial").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn unwritable_path_is_an_error() {
        assert!(write_file(Path::new("/proc/photocraft/nope.png"), b"x").is_err());
    }
}
