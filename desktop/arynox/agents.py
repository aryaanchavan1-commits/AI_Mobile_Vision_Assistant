import json
import os
import shutil
import subprocess

from .config import IS_WINDOWS


def _run(cmd, timeout=60):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def open_app(name):
    if IS_WINDOWS:
        exe = shutil.which(name) or shutil.which(name + ".exe")
        if exe:
            try:
                os.startfile(exe)
                return name
            except Exception:
                return None
        return None
    out = _run(["pm", "list", "packages"])
    matches = [
        line.replace("package:", "")
        for line in out.stdout.splitlines()
        if name.lower() in line.lower()
    ]
    if not matches:
        return None
    pkg = min(matches, key=len)
    _run(["monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"])
    return pkg


def check_battery():
    if IS_WINDOWS:
        try:
            out = _run(
                [
                    "powershell", "-NoProfile", "-Command",
                    "(Get-CimInstance Win32_Battery).EstimatedChargeRemaining",
                ],
                timeout=20,
            )
            pct = out.stdout.strip()
            if pct:
                return f"Battery is at {pct} percent."
            return "No battery detected on this PC."
        except Exception:
            return "I could not read the battery."
    try:
        out = _run(["termux-battery-status"])
        data = json.loads(out.stdout)
        level = data.get("percentage", "unknown")
        state = data.get("status", "unknown")
        return f"Battery is at {level} percent and {state}."
    except Exception:
        return "I could not read the battery."


def sensors():
    if IS_WINDOWS:
        return "Sensors are only available on Android."
    try:
        out = _run(["termux-sensor", "-n", "1", "-a"], timeout=30)
        data = json.loads(out.stdout)
        names = list(data.keys())[:3]
        if names:
            return "Active sensors: " + ", ".join(names) + "."
        return "No sensors found."
    except Exception:
        return "Sensors are unavailable."


def _walk_find(root, query, limit, maxdepth):
    matches = []
    q = query.lower()
    for dirpath, dirs, files in os.walk(root):
        depth = dirpath[len(root):].count(os.sep)
        if depth >= maxdepth:
            dirs[:] = []
        for name in files:
            if q in name.lower():
                matches.append(os.path.join(dirpath, name))
                if len(matches) >= limit:
                    return matches
    return matches


def find_files(query, limit=5):
    if IS_WINDOWS:
        try:
            return _walk_find(os.path.expanduser("~"), query, limit, maxdepth=4)
        except Exception:
            return []
    out = _run(["find", "/sdcard", "-maxdepth", "4", "-iname", f"*{query}*", "-type", "f"], timeout=60)
    return [p for p in out.stdout.splitlines() if p.strip()][:limit]


def read_text(path, max_chars=2000):
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            return f.read(max_chars)
    except Exception:
        return ""
