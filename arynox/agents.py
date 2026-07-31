import json
import subprocess


def _run(cmd, timeout=60):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def open_app(name):
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
    try:
        out = _run(["termux-battery-status"])
        data = json.loads(out.stdout)
        level = data.get("percentage", "unknown")
        state = data.get("status", "unknown")
        return f"Battery is at {level} percent and {state}."
    except Exception:
        return "I could not read the battery."


def sensors():
    try:
        out = _run(["termux-sensor", "-n", "1", "-a"], timeout=30)
        data = json.loads(out.stdout)
        names = list(data.keys())[:3]
        if names:
            return "Active sensors: " + ", ".join(names) + "."
        return "No sensors found."
    except Exception:
        return "Sensors are unavailable."


def find_files(query, limit=5):
    out = _run(["find", "/sdcard", "-maxdepth", "4", "-iname", f"*{query}*", "-type", "f"], timeout=60)
    return [p for p in out.stdout.splitlines() if p.strip()][:limit]


def read_text(path, max_chars=2000):
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            return f.read(max_chars)
    except Exception:
        return ""
