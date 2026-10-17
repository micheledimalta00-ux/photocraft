#!/usr/bin/env python3
"""Piccolo pilota dell'interfaccia via adb + uiautomator, per i test sull'emulatore."""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

OUT = "selftest-out"

def adb(*a, capture=False):
    r = subprocess.run(["adb", *a], capture_output=True, text=not capture, timeout=120)
    return r.stdout

def dump():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        x = adb("shell", "cat", "/sdcard/ui.xml")
        if x and "<hierarchy" in x:
            try:
                return ET.fromstring(x[x.index("<hierarchy"):])
            except ET.ParseError:
                pass
        time.sleep(1)
    return None

def nodes(label):
    root = dump()
    out = []
    if root is None:
        return out
    for n in root.iter("node"):
        t = n.get("text") or ""
        d = n.get("content-desc") or ""
        if t == label or d == label:
            m = re.findall(r"\d+", n.get("bounds", ""))
            if len(m) == 4:
                x1, y1, x2, y2 = map(int, m)
                if x2 > x1 and y2 > y1:
                    out.append(((x1 + x2) // 2, (y1 + y2) // 2))
    return out

def screen():
    s = adb("shell", "wm", "size")
    m = re.findall(r"(\d+)x(\d+)", s or "")
    w, h = map(int, m[-1]) if m else (1080, 2400)
    rot = (adb("shell", "settings", "get", "system", "user_rotation") or "0").strip()
    return (h, w) if rot in ("1", "3") else (w, h)

def tap(label, where="any"):
    for attempt in range(6):
        ns = nodes(label)
        if ns:
            if where == "bottom":
                ns.sort(key=lambda p: -p[1])
            elif where == "top":
                ns.sort(key=lambda p: p[1])
            x, y = ns[0]
            adb("shell", "input", "tap", str(x), str(y))
            print(f"tap {label} @ {x},{y}")
            time.sleep(1.5)
            return True
        # scorre la barra degli strumenti in basso (verticale) o la barra laterale
        w, h = screen()
        if w < h:
            y = int(h * 0.955)
            adb("shell", "input", "swipe", str(int(w * 0.85)), str(y), str(int(w * 0.15)), str(y), "300")
            if attempt == 3:
                adb("shell", "input", "swipe", str(int(w * 0.15)), str(y), str(int(w * 0.85)), str(y), "300")
                adb("shell", "input", "swipe", str(int(w * 0.15)), str(y), str(int(w * 0.85)), str(y), "300")
        else:
            x = int(w * 0.97)
            adb("shell", "input", "swipe", str(x), str(int(h * 0.85)), str(x), str(int(h * 0.15)), "300")
        time.sleep(1)
    print(f"NON TROVATO: {label}")
    return False

def shot(name):
    png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
    with open(f"{OUT}/{name}.png", "wb") as f:
        f.write(png)
    print(f"screenshot {name} ({len(png)} byte)")

if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "tap":
        ok = tap(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "any")
        sys.exit(0)
    elif cmd == "shot":
        shot(sys.argv[2])
