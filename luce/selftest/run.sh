#!/bin/bash
# Test sull'emulatore: autodiagnosi IA + navigazione dell'interfaccia con screenshot.
cd "$(dirname "$0")"
OUT=selftest-out
mkdir -p $OUT
PKG=com.michele.luce
DIR=/sdcard/Android/data/$PKG/files
UI="python3 ui.py"
adb install -r -g ../Luce.apk 2>&1 | tail -2
adb shell pm grant $PKG android.permission.READ_MEDIA_IMAGES 2>/dev/null
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
for f in astronaut rocket coffee; do adb push $f.jpg /sdcard/Pictures/$f.jpg >/dev/null; done
# indicizza le foto nella galleria di sistema
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 6
for f in astronaut rocket coffee; do
  adb shell content call --uri content://media --method scan_file --arg /sdcard/Pictures/$f.jpg >/dev/null 2>&1 || true
done
sleep 3
adb shell content query --uri content://media/external/images/media --projection _id:_display_name > $OUT/media.txt
cat $OUT/media.txt
uri_of() { id=$(grep "_display_name=$1.jpg" $OUT/media.txt | sed -E 's/.*_id=([0-9]+).*/\1/' | head -1); echo "content://media/external/images/media/$id"; }
adb logcat -c

# 1) autodiagnosi
for f in astronaut rocket coffee; do
  adb shell am force-stop $PKG
  adb shell am start -n $PKG/.MainActivity --ez luce_selftest true --es luce_selftest_uri "$(uri_of $f)" --es luce_selftest_name $f
  for i in $(seq 1 300); do
    if adb shell cat $DIR/selftest/report_$f.txt 2>/dev/null | grep -q "^FINE"; then break; fi
    sleep 2
  done
  adb shell cat $DIR/selftest/report_$f.txt > $OUT/report_$f.txt 2>/dev/null
done
adb pull $DIR/selftest $OUT/files >/dev/null 2>&1

# 2) interfaccia: editor in verticale
adb shell am force-stop $PKG
adb shell am start -a android.intent.action.VIEW -d "$(uri_of astronaut)" -t image/jpeg -n $PKG/.MainActivity
sleep 10
$UI shot 01_luce
$UI tap "Colore" bottom; sleep 3; $UI shot 02_colore
$UI tap "Colore puntuale"; sleep 2; $UI shot 03_colore_puntuale
$UI tap "Calibrazione"; sleep 2; $UI shot 04_calibrazione
$UI tap "Geometria" bottom; sleep 3; $UI shot 05_geometria
$UI tap "Maschere" bottom; sleep 3; $UI shot 06_maschere
$UI tap "Soggetto"; sleep 25; $UI shot 07_soggetto
$UI tap "Aggiungi"; sleep 2; $UI shot 08_aggiungi
$UI tap "Persone"; sleep 2; $UI shot 09_persone_dialogo
$UI tap "Capelli"; $UI tap "Iride e pupilla"; $UI tap "Crea"; sleep 25; $UI shot 10_persone
$UI tap "Effetti" bottom; sleep 2; $UI shot 11_effetti
$UI tap "Ottica" bottom; sleep 2; $UI shot 12_ottica
$UI tap "Luce" bottom; sleep 2; $UI tap "Ritaglio"; sleep 4; $UI shot 13_ritaglio_luci
# orizzontale
adb shell settings put system user_rotation 1
adb shell cmd window user-rotation lock 1
sleep 8
$UI shot 14_orizzontale
$UI tap "Maschere"; sleep 3; $UI shot 15_orizzontale_maschere
adb shell settings put system user_rotation 0
adb shell cmd window user-rotation lock 0
sleep 5
# cielo sulla foto del razzo
adb shell am force-stop $PKG
adb shell am start -a android.intent.action.VIEW -d "$(uri_of rocket)" -t image/jpeg -n $PKG/.MainActivity
sleep 10
$UI tap "Maschere" bottom; sleep 3
$UI tap "Cielo"; sleep 25; $UI shot 16_cielo
$UI tap "Tutte le maschere"; sleep 2
$UI tap "Paesaggio"; sleep 2; $UI shot 17_paesaggio_dialogo
$UI tap "Crea"; sleep 25; $UI shot 18_paesaggio
adb shell input keyevent KEYCODE_BACK; sleep 4; $UI shot 19_libreria

adb logcat -d > $OUT/logcat.txt
grep -E "FATAL EXCEPTION|AndroidRuntime: |LuceSelfTest|ANR in" $OUT/logcat.txt > $OUT/crash_e_diagnosi.txt || true
ls -la $OUT $OUT/files 2>/dev/null | head -80
echo "fine test"
