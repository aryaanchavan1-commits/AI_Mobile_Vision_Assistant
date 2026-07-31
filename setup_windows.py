import ctypes
import json
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import zipfile
from pathlib import Path

import requests

from arynox.config import IS_WINDOWS
from arynox.models import LINUX_ASSET_PATTERN, LLAMA_ASSET_PATTERN, TIERS, WHISPER_MODELS

APP_DIR = Path.home() / ".arynox"
MODELS_DIR = APP_DIR / "models"
LLAMA_DIR = APP_DIR / "llama"
VENV_DIR = APP_DIR / "venv"
REPO_DIR = Path(__file__).resolve().parent

SERVER_NAME = "llama-server.exe" if IS_WINDOWS else "llama-server"


def is_wsl():
    try:
        return "microsoft" in (platform.uname().release or "").lower()
    except Exception:
        return False


def venv_python():
    return VENV_DIR / ("Scripts/python.exe" if IS_WINDOWS else "bin/python")


def detect_ram_gb():
    if IS_WINDOWS:
        try:
            class MEMORYSTATUSEX(ctypes.Structure):
                _fields_ = [
                    ("dwLength", ctypes.c_ulong),
                    ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("sullAvailExtendedVirtual", ctypes.c_ulonglong),
                ]

            status = MEMORYSTATUSEX()
            status.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status))
            return round(status.ullTotalPhys / 1e9, 1)
        except Exception:
            return 0.0
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemTotal:"):
                    kb = int(line.split()[1])
                    return round(kb / 1024 / 1024, 1)
    except Exception:
        pass
    return 0.0


def auto_tier(ram_gb):
    if ram_gb < 4:
        return "lite"
    if ram_gb < 8:
        return "standard"
    if ram_gb < 16:
        return "pro"
    return "max"


def free_gb():
    try:
        return round(shutil.disk_usage(APP_DIR.parent).free / 1e9, 1)
    except Exception:
        return 0.0


def download(name, url, dest_dir):
    dest = dest_dir / name
    mode = "wb"
    headers = {}
    if dest.exists() and dest.stat().st_size > 0:
        headers["Range"] = f"bytes={dest.stat().st_size}-"
        mode = "ab"
    try:
        with requests.get(url, stream=True, headers=headers, timeout=(15, 600)) as resp:
            if resp.status_code == 416:
                print(f"  {name} already downloaded")
                return True
            if resp.status_code == 206:
                mode = "ab"
            elif resp.status_code == 200:
                mode = "wb"
            else:
                print(f"  FAILED: {name} (HTTP {resp.status_code})")
                return False
            resume_from = dest.stat().st_size if mode == "ab" else 0
            total = resume_from + int(resp.headers.get("Content-Length") or 0)
            if total and resume_from >= total and resume_from > 0:
                print(f"  {name} already downloaded")
                return True
            print(f"  Downloading {name}")
            written = resume_from
            with open(dest, mode) as f:
                for chunk in resp.iter_content(1 << 20):
                    f.write(chunk)
                    written += len(chunk)
                    if written // (50 << 20) > (written - len(chunk)) // (50 << 20):
                        mb = written >> 20
                        print(f"    {mb} MB / {total >> 20} MB", flush=True)
            return True
    except KeyboardInterrupt:
        print(f"\n  Interrupted - partial file kept, re-run setup to resume.")
        return False
    except Exception as exc:
        print(f"  FAILED: {name} ({exc})")
        return False


def download_llama_cpp():
    exe = LLAMA_DIR / SERVER_NAME
    if exe.exists():
        print("  llama.cpp already downloaded")
        return True
    print("  Fetching latest llama.cpp release")
    resp = requests.get(
        "https://api.github.com/repos/ggml-org/llama.cpp/releases/latest",
        headers={"User-Agent": "arynox-setup"},
        timeout=30,
    )
    resp.raise_for_status()
    pattern = LLAMA_ASSET_PATTERN if IS_WINDOWS else LINUX_ASSET_PATTERN
    asset = None
    for item in resp.json().get("assets", []):
        if item["name"].endswith(pattern):
            asset = item
            break
    if asset is None:
        print(f"  FAILED: no release asset matching {pattern}")
        return False
    archive = APP_DIR / asset["name"]
    if not download(asset["name"], asset["browser_download_url"], APP_DIR):
        return False
    print(f"  Extracting {asset['name']}")
    LLAMA_DIR.mkdir(parents=True, exist_ok=True)
    if str(archive).endswith(".zip"):
        with zipfile.ZipFile(archive) as zf:
            zf.extractall(LLAMA_DIR)
    else:
        with tarfile.open(archive, "r:gz") as tf:
            tf.extractall(LLAMA_DIR)
    archive.unlink(missing_ok=True)
    for root, _, files in os.walk(LLAMA_DIR):
        for name in files:
            if name == SERVER_NAME:
                target = LLAMA_DIR / name
                if os.path.abspath(os.path.join(root, name)) != os.path.abspath(target):
                    shutil.move(os.path.join(root, name), target)
    return exe.exists()


def ensure_python_env():
    print("\n[1/5] Setting up Python environment")
    py = venv_python()
    if not py.exists():
        print("  Creating virtual environment (venv)")
        result = subprocess.run([sys.executable, "-m", "venv", str(VENV_DIR)])
        if result.returncode != 0:
            print("  venv unavailable, falling back to system pip")
            if not IS_WINDOWS:
                subprocess.run(
                    [sys.executable, "-m", "pip", "install", "--user",
                     "--break-system-packages", "--upgrade", "pip"],
                    check=False,
                )
                subprocess.run(
                    [sys.executable, "-m", "pip", "install", "--user",
                     "--break-system-packages", "-r", str(REPO_DIR / "requirements-windows.txt")],
                    check=False,
                )
            else:
                subprocess.run([sys.executable, "-m", "pip", "install", "--upgrade", "pip"], check=False)
                subprocess.run(
                    [sys.executable, "-m", "pip", "install",
                     "-r", str(REPO_DIR / "requirements-windows.txt")],
                    check=False,
                )
            return sys.executable
    print("  Installing Python packages")
    subprocess.run([str(py), "-m", "pip", "install", "--upgrade", "pip"], check=False)
    subprocess.run(
        [str(py), "-m", "pip", "install", "-r", str(REPO_DIR / "requirements-windows.txt")],
        check=False,
    )
    return str(py)


def server_line(exe_path, model, extra, port, log):
    args = [str(exe_path), "-m", str(MODELS_DIR / model)]
    args += [c for c in extra.split() if c]
    args += ["--host", "127.0.0.1", "--port", str(port)]
    arg_str = ", ".join(f"'{a}'" for a in args)
    return (
        f'powershell -NoProfile -ExecutionPolicy Bypass -Command '
        f'"Start-Process -FilePath \'{exe_path}\' -ArgumentList @({arg_str}) '
        f'-WindowStyle Hidden -RedirectStandardOutput \'{APP_DIR / (log + ".log")}\' '
        f'-RedirectStandardError \'{APP_DIR / (log + ".err.log")}\'"'
    )


def write_start_cmd(tier_cfg, py):
    lines = [
        "@echo off",
        "tasklist /FI \"IMAGENAME eq llama-server.exe\" 2>nul | find /I \"llama-server.exe\" >nul",
        "if %errorlevel%==0 (echo Local AI is already running. & exit /b 0)",
        "echo Starting local AI (this takes a few seconds)...",
        server_line(LLAMA_DIR / "llama-server.exe", tier_cfg["llm"][0],
                    f"-c {tier_cfg['ctx']} -n 512 --jinja", 8080, "llm"),
    ]
    if tier_cfg["emb"]:
        lines.append(
            server_line(LLAMA_DIR / "llama-server.exe", tier_cfg["emb"][0],
                        "--embeddings --pooling mean -c 512", 8081, "embed")
        )
    lines += [
        ":wait",
        "curl.exe -sf http://127.0.0.1:8080/health >nul 2>&1",
        "if %errorlevel%==0 goto ready",
        "timeout /t 1 /nobreak >nul",
        "goto wait",
        ":ready",
        "echo Local AI is ready.",
    ]
    (APP_DIR / "start-local.cmd").write_text("\r\n".join(lines) + "\r\n")


def write_run_cmd(py):
    lines = [
        "@echo off",
        "cd /d \"%USERPROFILE%\\.arynox\\app\"",
        'if /I "%1"=="stop" (',
        "  taskkill /F /IM llama-server.exe >nul 2>&1",
        "  powershell -NoProfile -Command \"Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'main\\.py' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }\"",
        "  echo Arynox stopped.",
        "  exit /b 0",
        ")",
        'if /I "%1"=="status" (',
        "  tasklist /FI \"IMAGENAME eq llama-server.exe\" 2>nul | find /I \"llama-server.exe\" >nul && (echo Local models: running) || (echo Local models: stopped)",
        "  exit /b 0",
        ")",
        'if /I "%1"=="start" shift',
        'call "%USERPROFILE%\\.arynox\\start-local.cmd"',
        f'"{py}" main.py %1 %2 %3 %4 %5 %6 %7 %8 %9',
    ]
    (APP_DIR / "run.cmd").write_text("\r\n".join(lines) + "\r\n")


def write_shortcuts():
    """start.cmd / stop.cmd / status.cmd on Windows, start/stop aliases in WSL."""
    if IS_WINDOWS:
        run = APP_DIR / "run.cmd"
        (APP_DIR / "start.cmd").write_text(
            f'@echo off\r\ncall "{run}" start\r\n', encoding="utf-8")
        (APP_DIR / "stop.cmd").write_text(
            f'@echo off\r\ncall "{run}" stop\r\n', encoding="utf-8")
        (APP_DIR / "status.cmd").write_text(
            f'@echo off\r\ncall "{run}" status\r\n', encoding="utf-8")
    else:
        bashrc = Path.home() / ".bashrc"
        lines = [
            f'alias start="bash {APP_DIR / "run.sh"} start"',
            f'alias stop="bash {APP_DIR / "run.sh"} stop"',
        ]
        for line in lines:
            alias = line.split("=", 1)[0]
            if bashrc.exists() and any(alias in l for l in bashrc.read_text(encoding="utf-8").splitlines()):
                continue
            bashrc.write_text((bashrc.read_text(encoding="utf-8") + line + "\n") if bashrc.exists() else line + "\n", encoding="utf-8")


def write_start_sh(tier_cfg, py):
    mm_name = tier_cfg["mm"][0] if tier_cfg["mm"] else ""
    emb_name = tier_cfg["emb"][0] if tier_cfg["emb"] else ""
    ctx = tier_cfg["ctx"]
    server = LLAMA_DIR / SERVER_NAME
    lines = [
        "#!/usr/bin/env bash",
        f'SERVER="{server}"',
        f'MODELS="{MODELS_DIR}"',
        f'LLM="{tier_cfg["llm"][0]}"',
        f'MM="{mm_name}"',
        f'EMB="{emb_name}"',
        f'CTX={ctx}',
        f'LOG="{APP_DIR}"',
        'if pgrep -f "llama-server" > /dev/null 2>&1; then',
        '  echo "Local AI is already running."',
        "  exit 0",
        "fi",
        'echo "Starting local AI (this takes a few seconds)..."',
        'if [ -n "$MM" ]; then',
        '  nohup "$SERVER" -m "$MODELS/$LLM" --mmproj "$MODELS/$MM" -c $CTX -n 512 --jinja --host 127.0.0.1 --port 8080 > "$LOG/llm.log" 2>&1 &',
        "else",
        '  nohup "$SERVER" -m "$MODELS/$LLM" -c $CTX -n 512 --jinja --host 127.0.0.1 --port 8080 > "$LOG/llm.log" 2>&1 &',
        "fi",
        'if [ -n "$EMB" ]; then',
        '  nohup "$SERVER" -m "$MODELS/$EMB" --embeddings --pooling mean -c 512 --host 127.0.0.1 --port 8081 > "$LOG/embed.log" 2>&1 &',
        "fi",
        "for i in $(seq 1 120); do",
        "  if curl -sf http://127.0.0.1:8080/health > /dev/null 2>&1; then",
        '    echo "Local AI is ready."',
        "    exit 0",
        "  fi",
        "  sleep 1",
        "done",
        'echo "Local AI failed to start. Check $LOG/llm.log"',
        "exit 1",
    ]
    (APP_DIR / "start-local.sh").write_text("\n".join(lines) + "\n", newline="\n")


def write_run_sh(py):
    lines = [
        "#!/usr/bin/env bash",
        'cd "$HOME/.arynox/app"',
        'case "${1:-start}" in',
        "  start)",
        '    if [ -f "$HOME/.arynox/start-local.sh" ]; then bash "$HOME/.arynox/start-local.sh"; fi',
        f'    exec "{py}" main.py "${{@:2}}"',
        "    ;;",
        "  stop)",
        '    pkill -f "main.py" 2>/dev/null || true',
        '    pkill -f "llama-server" 2>/dev/null || true',
        '    echo "Arynox stopped."',
        "    ;;",
        "  status)",
        '    if pgrep -f "main.py" > /dev/null 2>&1; then echo "Arynox: running"; else echo "Arynox: stopped"; fi',
        '    if pgrep -f "llama-server" > /dev/null 2>&1; then echo "Local models: running"; else echo "Local models: stopped"; fi',
        "    ;;",
        "  --*)",
        f'    exec "{py}" main.py "$@"',
        "    ;;",
        "  *)",
        '    echo "Usage: bash run.sh [start|stop|status]"',
        "    ;;",
        "esac",
    ]
    (APP_DIR / "run.sh").write_text("\n".join(lines) + "\n", newline="\n")


def main():
    print("=============================================")
    print("   ARYNOX BRAIN - Windows/WSL Setup")
    print("=============================================")
    if is_wsl():
        print("  Detected: WSL (Linux on Windows)")
        print("  Note: webcam and microphone do not work inside WSL.")
        print("  Arynox will run in typed mode. For voice + camera,")
        print("  use native Windows Python instead.")
    print(f"  RAM     : {detect_ram_gb()} GB")
    print(f"  Free    : {free_gb()} GB")
    print()

    APP_DIR.mkdir(parents=True, exist_ok=True)
    MODELS_DIR.mkdir(parents=True, exist_ok=True)

    default_tier = auto_tier(detect_ram_gb())
    print("  Local AI model tiers:")
    for t in ("lite", "standard", "pro", "max"):
        print("   ", TIERS[t]["help"])
    tier = input(f"  Choose tier [{default_tier}] (or 'none'): ").strip().lower() or default_tier
    if tier == "none":
        tier = ""
    if tier and tier not in TIERS:
        print("Invalid tier.")
        sys.exit(1)

    py = ensure_python_env()

    if tier:
        tier_cfg = TIERS[tier]
        need_gb = tier_cfg["need_mb"] / 1000
        free = free_gb()
        if free and free < need_gb:
            ans = input(f"  Only {free} GB free, need ~{need_gb} GB. Continue? [y/N]: ").strip().lower()
            if ans != "y":
                sys.exit(1)
        print("[2/5] Downloading llama.cpp")
        if not download_llama_cpp():
            print("  Falling back to cloud (Gemini) mode.")
            tier = ""
        else:
            print("[3/5] Downloading models (interrupted downloads resume)")
            ok = download(*tier_cfg["llm"], MODELS_DIR)
            if tier_cfg["mm"]:
                ok = download(*tier_cfg["mm"], MODELS_DIR) and ok
            if tier_cfg["emb"]:
                ok = download(*tier_cfg["emb"], MODELS_DIR) and ok
            if not ok:
                print("  Some models failed. Re-run setup to continue.")
                sys.exit(1)

    whisper_name, _ = WHISPER_MODELS["lite" if (tier == "lite" or detect_ram_gb() < 4) else "default"]

    print("[4/5] Installing Arynox files")
    app_dir = APP_DIR / "app"
    shutil.copytree(REPO_DIR / "arynox", app_dir / "arynox", dirs_exist_ok=True)
    shutil.copy2(REPO_DIR / "main.py", app_dir / "main.py")
    if not (APP_DIR / "config.json").exists():
        shutil.copy2(REPO_DIR / "config.json", APP_DIR / "config.json")

    cfg_path = APP_DIR / "config.json"
    cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
    cfg["local"] = {
        "enabled": bool(tier),
        "llm_url": "http://127.0.0.1:8080",
        "embed_url": "http://127.0.0.1:8081" if tier and TIERS[tier]["emb"] else "",
        "models_dir": str(MODELS_DIR),
        "llm_model": TIERS[tier]["llm"][0] if tier else "",
        "vlm_mmproj": TIERS[tier]["mm"][0] if tier and TIERS[tier]["mm"] else "",
        "embed_model": TIERS[tier]["emb"][0] if tier and TIERS[tier]["emb"] else "",
        "context": TIERS[tier]["ctx"] if tier else 2048,
    }
    cfg["stt"] = "auto"
    cfg["whisper_model"] = whisper_name
    cfg["tts_engine"] = "auto"
    cfg_path.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    print(f"  Config written (local backend: {tier or 'disabled'}, offline STT: {whisper_name})")

    print("[5/5] Creating start/run scripts")
    if tier:
        if IS_WINDOWS:
            write_start_cmd(TIERS[tier], py)
        else:
            write_start_sh(TIERS[tier], py)
    if IS_WINDOWS:
        write_run_cmd(py)
    else:
        write_run_sh(py)
    write_shortcuts()

    print()
    print("Setup complete.")
    if IS_WINDOWS:
        print("  Start:   start.cmd   or: run.cmd start   (or double-click start.cmd)")
        print("  Stop:    say 'stop' to Arynox, or: stop.cmd  /  run.cmd stop")
        print("  Status:  status.cmd / run.cmd status")
    else:
        print("  Start:   type 'start'")
        print("  Stop:    say 'stop' to Arynox, or type 'stop'")
        print("  Status:  bash ~/.arynox/run.sh status")
    print()
    print("Offline: vision/chat/memory on-device; speech-to-text via")
    print("faster-whisper (downloads its model on first use, then offline).")
    if is_wsl():
        print("In WSL: typed mode is automatic (no mic/camera).")
    print("First camera/mic use will ask for permission.")

    print()
    print("Hardware self-test...")
    demo = subprocess.run([py, str(app_dir / "main.py"), "--demo"], cwd=str(app_dir))
    print()
    print("Starting Arynox...")
    if IS_WINDOWS:
        subprocess.run(["cmd", "/c", str(APP_DIR / "run.cmd")])
    else:
        subprocess.run(["bash", str(APP_DIR / "run.sh")])


if __name__ == "__main__":
    main()
