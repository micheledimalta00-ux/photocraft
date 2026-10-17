#!/bin/bash
# Screenshot dell'interfaccia per la guida di Luce.
cd "$(dirname "$0")"
OUT=guide-out
mkdir -p $OUT
PKG=com.michele.luce
UI="python3 ui.py"
sed -i 's/^OUT = .*/OUT = "guide-out"/' ui.py
adb install -r -g ../Luce.apk 2>&1 | tail -1
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
for f in kodim*.png; do adb push $f /sdcard/Pictures/$f >/dev/null; done
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 6
for f in kodim*.png; do adb shell content call --uri content://media --method scan_file --arg /sdcard/Pictures/$f >/dev/null 2>&1 || true; done
sleep 3
adb shell content query --uri content://media/external/images/media --projection _id:_display_name > $OUT/media.txt
uri_of() { id=$(grep "_display_name=$1" $OUT/media.txt | sed -E 's/.*_id=([0-9]+).*/\1/' | head -1); echo "content://media/external/images/media/$id"; }
open_img() { adb shell am force-stop $PKG; adb shell am start -a android.intent.action.VIEW -d "$(uri_of $1)" -t image/png -n $PKG/.MainActivity >/dev/null; sleep 9; }
panel_up() { adb shell input swipe 540 2050 540 1450 400; sleep 1.5; }
back_masks() { $UI tap "Tutte le maschere"; sleep 1; }

# libreria
adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 7
$UI shot g01_libreria

# ritratto
open_img kodim04.png
$UI shot g02_luce
panel_up; panel_up; $UI shot g03_curva
$UI tap "Parametrica"; sleep 2; panel_up; $UI shot g04_curva_parametrica
$UI tap "Colore" bottom; sleep 2; $UI shot g05_colore_base
$UI tap "Colore puntuale"; sleep 1; $UI tap "Campiona un colore"; sleep 1
adb shell input tap 540 520; sleep 4; $UI shot g06_colore_puntuale
$UI tap "Miscelazione"; sleep 2; $UI shot g07_miscelazione
$UI tap "Color grading"; sleep 2; $UI shot g08_color_grading
$UI tap "Calibrazione"; sleep 2; $UI shot g09_calibrazione
$UI tap "Effetti" bottom; sleep 2; $UI shot g10_effetti
$UI tap "Dettaglio" bottom; sleep 2; $UI shot g11_dettaglio
$UI tap "Ottica" bottom; sleep 2; $UI shot g12_ottica
$UI tap "Geometria" bottom; sleep 2; $UI shot g13_geometria
$UI tap "Ritaglia" bottom; sleep 3; $UI shot g14_ritaglia
$UI tap "Profili" bottom; sleep 8; $UI shot g15_profili
$UI tap "Predefiniti" bottom; sleep 10; $UI shot g16_predefiniti
$UI tap "Maschere" bottom; sleep 2; $UI shot g17_maschere_crea
$UI tap "Persone"; sleep 2; $UI shot g18_persone_dialogo
$UI tap "Persona intera"; $UI tap "Pelle del viso"; $UI tap "Crea"; sleep 25; $UI shot g19_maschera_viso
panel_up; panel_up; panel_up; $UI shot g20_regolazioni_locali
panel_up; panel_up; $UI shot g21_regolazioni_locali_2
back_masks
$UI tap "Pennello"; sleep 2; $UI shot g22_pennello
adb shell input swipe 380 900 700 950 900; sleep 4; $UI shot g23_pennello_dipinto
back_masks
$UI tap "Radiale"; sleep 3; $UI shot g24_radiale
back_masks
$UI tap "Lineare"; sleep 3; $UI shot g25_lineare
back_masks
$UI tap "Colore" top; sleep 2; $UI shot g26_intervallo_colore_attesa
adb shell input tap 540 520; sleep 5; $UI shot g27_intervallo_colore
$UI tap "Fine"; sleep 1
$UI tap "Su nero"; sleep 4; $UI shot g28_sovrapposizione_nero
$UI tap "Bianco e nero"; sleep 4; $UI shot g29_sovrapposizione_bn
$UI tap "Colore" top; sleep 1
back_masks
$UI tap "Luminanza"; sleep 2; $UI tap "Fine"; sleep 3; panel_up; $UI shot g30_intervallo_luminanza
back_masks
$UI tap "Oggetti"; sleep 2; $UI shot g31_oggetti_attesa
adb shell input tap 540 520; sleep 20; $UI shot g32_oggetto
$UI tap "Aggiungi"; sleep 2; $UI shot g33_aggiungi_menu
adb shell input keyevent KEYCODE_BACK; sleep 2
$UI tap "Esporta"; sleep 3; $UI shot g34_esporta
adb shell input keyevent KEYCODE_BACK; sleep 2
$UI tap "Altro"; sleep 2; $UI shot g35_menu
adb shell input keyevent KEYCODE_BACK; sleep 2
$UI tap "Luce" bottom; sleep 2
( adb shell input swipe 540 700 540 700 4000 & ) ; sleep 2.5; $UI shot g36_prima; sleep 3

# orizzontale
adb shell settings put system user_rotation 1; adb shell cmd window user-rotation lock 1; sleep 8
$UI shot g37_orizzontale
$UI tap "Colore"; sleep 2; $UI shot g38_orizzontale_colore
adb shell settings put system user_rotation 0; adb shell cmd window user-rotation lock 0; sleep 6

# paesaggio
open_img kodim13.png
$UI tap "Maschere" bottom; sleep 2
$UI tap "Cielo"; sleep 25; $UI shot g39_cielo
back_masks
$UI tap "Paesaggio"; sleep 2; $UI tap "Cielo"; $UI tap "Acqua"; $UI tap "Vegetazione"; $UI shot g40_paesaggio_dialogo
$UI tap "Crea"; sleep 25; $UI shot g41_paesaggio
$UI tap "Geometria" bottom; sleep 2; $UI tap "Auto"; sleep 6; $UI shot g42_upright

# colore
open_img kodim23.png
$UI tap "Colore" bottom; sleep 2; $UI tap "Miscelazione"; sleep 3; $UI shot g43_pappagalli_miscelazione

adb logcat -d | grep -E "FATAL EXCEPTION|Fatal signal" > $OUT/crash.txt || true
ls -la $OUT
