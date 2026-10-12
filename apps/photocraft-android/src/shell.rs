//! The Android shell around [`PhotocraftApp`]: the system Open/Save pickers, the on-screen keyboard,
//! A− / A+ buttons for Preferences › Interface › UI Scale (the desktop layout needs more points
//! than a phone has at its native scale; a first launch starts at three quarters of it).

use std::path::PathBuf;

use photocraft_engine::Session;
use photocraft_engine::prefs::UiScale;
use photocraft_ui_egui::PhotocraftApp;
use photocraft_ui_egui::i18n;
use photocraft_ui_egui::theme::ThemeKind;

use crate::system_dialogs::{Dialogs, Slot};

/// The fixed UI scales (Preferences › Interface › UI Scale), in percent, smallest first.
fn fixed_scales() -> impl Iterator<Item = (UiScale, f32)> {
    UiScale::NAMES.iter().filter_map(|n| Some((UiScale::parse(n)?, n.parse::<f32>().ok()?)))
}

/// The next fixed UI scale below (or above, `up`) `current` percent; `None` at the end.
pub fn step_scale(current: f32, up: bool) -> Option<UiScale> {
    let mut steps: Vec<(UiScale, f32)> = fixed_scales().collect();
    steps.sort_by(|a, b| a.1.total_cmp(&b.1));
    if up { steps.into_iter().find(|(_, pct)| *pct > current + 0.5) } else { steps.into_iter().rev().find(|(_, pct)| *pct < current - 0.5) }.map(|(s, _)| s)
}

/// The scale a first launch uses on a screen whose own scale is `native` pixels per point: three
/// quarters of it, so the dense desktop layout fits a phone or tablet in landscape.
pub fn first_launch_scale(native: f32) -> UiScale {
    let target = if native.is_finite() && native > 0.0 { native * 75.0 } else { 100.0 };
    let mut steps: Vec<(UiScale, f32)> = fixed_scales().collect();
    steps.sort_by(|a, b| a.1.total_cmp(&b.1));
    let smallest = steps.first().map_or(UiScale::Auto, |(s, _)| *s);
    steps.into_iter().rev().find(|(_, pct)| *pct <= target + 0.5).map_or(smallest, |(s, _)| s)
}

/// Shows or hides the soft keyboard (`None` off Android).
pub type KeyboardFn = Box<dyn FnMut(bool)>;

pub struct AndroidShell {
    app: PhotocraftApp,
    dialogs: Slot,
    keyboard: Option<KeyboardFn>,
    keyboard_shown: bool,
    /// Set the first-launch UI scale once the screen's own scale is known.
    pick_first_scale: bool,
    on_android: bool,
}

impl AndroidShell {
    pub fn new(cc: &eframe::CreationContext<'_>, data_dir: PathBuf, keyboard: Option<KeyboardFn>, on_android: bool) -> Self {
        if let Err(e) = std::fs::create_dir_all(&data_dir) {
            log::warn!("couldn't create {}: {e}", data_dir.display());
        }
        PhotocraftApp::setup_context(&cc.egui_ctx, ThemeKind::Pro);
        let dialogs = Dialogs::new(&data_dir);
        let first_launch = !crate::services::prefs_path(&data_dir).exists();
        let mut app = PhotocraftApp::new(Session::new(), crate::services::services(data_dir.clone(), dialogs.clone(), on_android));
        app.set_theme(&cc.egui_ctx, ThemeKind::Pro);
        if let Some(rs) = cc.wgpu_render_state.clone() {
            log::info!("photocraft-android: wgpu backend {:?}", rs.adapter.get_info().backend);
            app.set_wgpu(rs);
        }
        let pick_first_scale = first_launch && on_android && app.session.prefs().interface.ui_scale == UiScale::Auto;
        Self { app, dialogs, keyboard, keyboard_shown: false, pick_first_scale, on_android }
    }

    fn sync_keyboard(&mut self, ctx: &egui::Context) {
        let wants = ctx.egui_wants_keyboard_input();
        if wants != self.keyboard_shown {
            self.keyboard_shown = wants;
            if let Some(keyboard) = self.keyboard.as_mut() {
                keyboard(wants);
            }
        }
    }

    /// The interface scale now in effect, in percent.
    fn current_scale(&self, ctx: &egui::Context) -> f32 {
        let pref = self.app.session.prefs().interface.ui_scale;
        match pref.name().parse::<f32>() {
            Ok(pct) => pct,
            Err(_) => ctx.native_pixels_per_point().filter(|v| v.is_finite() && *v > 0.0).unwrap_or(1.0) * 100.0,
        }
    }

    /// A− / A+: step Preferences › Interface › UI Scale (saved like any preference).
    fn scale_control(&mut self, ctx: &egui::Context) {
        let current = self.current_scale(ctx);
        let smaller = step_scale(current, false);
        let larger = step_scale(current, true);
        let mut chosen = None;
        egui::Area::new(egui::Id::new("photocraft_android_scale"))
            .anchor(egui::Align2::RIGHT_BOTTOM, egui::vec2(-6.0, -30.0))
            .order(egui::Order::Foreground)
            .show(ctx, |ui| {
                egui::Frame::popup(ui.style()).show(ui, |ui| {
                    ui.horizontal(|ui| {
                        let size = egui::vec2(32.0, 28.0);
                        if ui.add_enabled(smaller.is_some(), egui::Button::new("A−").min_size(size)).on_hover_text(i18n::t("Smaller interface")).clicked() {
                            chosen = smaller;
                        }
                        if ui.add_enabled(larger.is_some(), egui::Button::new("A+").min_size(size)).on_hover_text(i18n::t("Larger interface")).clicked() {
                            chosen = larger;
                        }
                    });
                });
            });
        if let Some(scale) = chosen {
            self.app.session.edit_prefs(|p| p.interface.ui_scale = scale);
        }
    }
}

impl eframe::App for AndroidShell {
    fn logic(&mut self, ctx: &egui::Context, frame: &mut eframe::Frame) {
        if self.pick_first_scale
            && let Some(native) = ctx.native_pixels_per_point()
        {
            self.pick_first_scale = false;
            let scale = first_launch_scale(native);
            log::info!("first launch: screen scale {native}, UI scale {}%", scale.name());
            self.app.session.edit_prefs(|p| p.interface.ui_scale = scale);
        }
        self.app.logic(ctx, frame);
    }

    fn ui(&mut self, ui: &mut egui::Ui, frame: &mut eframe::Frame) {
        self.app.ui(ui, frame);
        let ctx = ui.ctx().clone();
        self.dialogs.borrow_mut().frame(&ctx, self.on_android);
        self.scale_control(&ctx);
        self.sync_keyboard(&ctx);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scale_steps_walk_the_fixed_choices() {
        assert_eq!(step_scale(100.0, true), Some(UiScale::P125));
        assert_eq!(step_scale(100.0, false), Some(UiScale::P95));
        // From an in-between (Auto) scale, the nearest fixed choice in that direction.
        assert_eq!(step_scale(262.5, false), Some(UiScale::P250));
        assert_eq!(step_scale(262.5, true), Some(UiScale::P300));
        assert_eq!(step_scale(300.0, true), None);
        assert_eq!(step_scale(75.0, false), None);
    }

    #[test]
    fn first_launch_fits_the_desktop_layout() {
        assert_eq!(first_launch_scale(2.75), UiScale::P200);
        assert_eq!(first_launch_scale(3.5), UiScale::P250);
        assert_eq!(first_launch_scale(1.0), UiScale::P75);
        assert_eq!(first_launch_scale(f32::NAN), UiScale::P100);
        assert_eq!(first_launch_scale(0.5), UiScale::P75);
    }
}
