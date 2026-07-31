import ctypes
import json
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

import requests

from arynox.models import LLAMA_ASSET_PATTERN, TIERS, WHISPER_MODELS

APP_DIR = Path.home() / ".arynox"
MODELS_DIR = APP_DIR / "models"
LLAMA_DIR = APP_DIR / "llama"
REPO_DIR = Path(__file__).resolve().parent


def detect_ram_gb():
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
    if dest.exists() and dest.stat().st_size > 0:
        print(f"  {name} already downloaded")
        return True
    print(f"  Downloading {name}")
    try:
        with requests.get(url, stream=True, timeout=(15, 600)) as resp:
            resp.raise_for_status()
            with open(dest, "wb") as f:
                for chunk in resp.iter_content(1 << 20):
                    f.write(chunk)
        return True
    except Exception as exc:
        print(f"  FAILED: {name} ({exc})")
        return False


def download_llama_cpp():
    if (LLAMA_DIR / "llama-server.exe").exists():
        print("  llama.cpp already downloaded")
        return True
    print("  Fetching latest llama.cpp release")
    resp = requests.get(
        "https://api.github.com/repos/ggml-org/llama.cpp/releases/latest",
        headers={"User-Agent": "arynox-setup"},
        timeout=30,
    )
    resp.raise_for_status()
    asset = None
    for item in resp.json().get("assets", []):
        if item["name"].endswith(LLAMA_ASSET_PATTERN):
            asset = item
            break
    if asset is None:
        print("  FAILED: no Windows x64 llama.cpp release asset found")
        return False
    zip_path = APP_DIR / asset["name"]
    download(asset["name"], asset["browser_download_url"], APP_DIR)
    print(f"  Extracting {asset['name']}")
    with zipfile.ZipFile(zip_path) as zf:
        zf.extractall(LLAMA_DIR)
    zip_path.unlink(missing_ok=True)
    for root, _, files in os.walk(LLAMA_DIR):
        for name in files:
            if name == "llama-server.exe":
                shutil.move(os.path.join(root, name), LLAMA_DIR / name)
    return (LLAMA_DIR / "llama-server.exe").exists()


def write_start_cmd(tier_cfg):
    exe = LLAMA_DIR / "llama-server.exe"
    llm_name, _ = tier_cfg["llm"]
    mm_name = tier_cfg["mm"][0] if tier_cfg["mm"] else ""
    emb_name = tier_cfg["emb"][0] if tier_cfg["emb"] else ""
    ctx = tier_cfg["ctx"]

    def server_line(exe_path, model, extra, port, log):
        args = [str(exe_path), "-m", str(MODELS_DIR / model)]
        if mm_name and "mmproj" not in extra:
            args += ["--mmproj", str(MODELS_DIR / mm_name)]
        args += [c for c in extra.split() if c]
        args += ["--host", "127.0.0.1", "--port", str(port)]
        arg_str = ", ".join(f"'{a}'" for a in args)
        return (
            f'powershell -NoProfile -ExecutionPolicy Bypass -Command '
            f'"Start-Process -FilePath \'{exe}\' -ArgumentList @({arg_str}) '
            f'-WindowStyle Hidden -RedirectStandardOutput \'{APP_DIR / (log + ".log")}\' '
            f'-RedirectStandardError \'{APP_DIR / (log + ".err.log")}\'"'
        )

    lines = [
        "@echo off",
        "tasklist /FI \"IMAGENAME eq llama-server.exe\" 2>nul | find /I \"llama-server.exe\" >nul",
        "if %errorlevel%==0 (echo Local AI is already running. & exit /b 0)",
        "echo Starting local AI (this takes a few seconds)...",
        server_line(exe, llm_name, f"-c {ctx} -n 512 --jinja", 8080, "llm"),
    ]
    if emb_name:
        lines.append(server_line(exe, emb_name, "--embeddings --pooling mean -c 512", 8081, "embed"))
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


def write_run_cmd():
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
        "python main.py %*",
    ]
    (APP_DIR / "run.cmd").write_text("\r\n".join(lines) + "\r\n")


def main():
    print("=============================================")
    print("   ARYNOX BRAIN - Windows Setup (prototype)")
    print("=============================================")
    ram_gb = detect_ram_gb()
    free = free_gb()
    print(f"  RAM     : {ram_gb} GB")
    print(f"  Free    : {free} GB")
    print()

    APP_DIR.mkdir(parents=True, exist_ok=True)
    MODELS_DIR.mkdir(parents=True, exist_ok=True)

    default_tier = auto_tier(ram_gb)
    print("  Local AI model tiers:")
    for t in ("lite", "standard", "pro", "max"):
        print("   ", TIERS[t]["help"])
    tier = input(f"  Choose tier [{default_tier}] (or 'none'): ").strip().lower() or default_tier
    if tier == "none":
        tier = ""
    if tier and tier not in TIERS:
        print("Invalid tier.")
        sys.exit(1)

    print("\n[1/5] Installing Python packages")
    subprocess.run(
        [sys.executable, "-m", "pip", "install", "--upgrade", "pip"], check=False
    )
    subprocess.run(
        [sys.executable, "-m", "pip", "install", "-r", str(REPO_DIR / "requirements-windows.txt")],
        check=False,
    )

    if tier:
        tier_cfg = TIERS[tier]
        need_gb = tier_cfg["need_mb"] / 1000
        if free and free < need_gb:
            ans = input(f"  Only {free} GB free, need ~{need_gb} GB. Continue? [y/N]: ").strip().lower()
            if ans != "y":
                sys.exit(1)
        print("[2/5] Downloading llama.cpp for Windows")
        if not download_llama_cpp():
            print("  Falling back to cloud (Gemini) mode.")
            tier = ""
        else:
            print("[3/5] Downloading models (resume not supported, re-run to continue)")
            ok = download(*tier_cfg["llm"], MODELS_DIR)
            if tier_cfg["mm"]:
                ok = download(*tier_cfg["mm"], MODELS_DIR) and ok
            if tier_cfg["emb"]:
                ok = download(*tier_cfg["emb"], MODELS_DIR) and ok
            if not ok:
                print("  Some models failed. Re-run setup to continue.")
                sys.exit(1)

    whisper_name, whisper_url = WHISPER_MODELS[
        "lite" if (tier == "lite" or ram_gb < 4) else "default"
    ]

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

    if tier:
        print("[5/5] Creating start/run scripts")
        write_start_cmd(TIERS[tier])
    write_run_cmd()

    print()
    print("Setup complete.")
    print(f"  Start:   {APP_DIR}\\run.cmd          (or: run.cmd start)")
    print(f"  Stop:    say 'stop' to Arynox,  or: run.cmd stop")
    print(f"  Status:  run.cmd status")
    print(f"  Demo:    cd {APP_DIR}\\app && python main.py --demo")
    print()
    print("Offline: vision/chat/memory on-device; speech-to-text via")
    print("faster-whisper (downloads a model on first use, then offline);")
    print("text-to-speech via Windows voices.")
    print("First camera/mic use will ask for Windows permissions.")


if __name__ == "__main__":
    main()
