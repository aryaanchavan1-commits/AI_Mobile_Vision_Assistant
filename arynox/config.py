import json
import os
import sys
from pathlib import Path

IS_WINDOWS = sys.platform == "win32"

HOME = Path(os.environ.get("HOME", str(Path.home())))
DATA_DIR = Path(os.environ.get("ARYNOX_DIR", str(HOME / ".arynox")))
CONFIG_FILE = DATA_DIR / "config.json"
PHOTOS_DIR = DATA_DIR / "photos"
AUDIO_DIR = DATA_DIR / "audio"
MODELS_DIR = DATA_DIR / "models"

DEFAULTS = {
    "gemini_api_key": "",
    "backend": "auto",
    "stt": "auto",
    "whisper_model": "ggml-base.bin",
    "tts_engine": "auto",
    "chat_model": "gemini-2.5-flash",
    "vision_model": "gemini-2.5-flash",
    "embedding_model": "gemini-embedding-001",
    "wake_word": "arynox",
    "language": "en-US",
    "tts_rate": 1.05,
    "listen_block_seconds": 4,
    "listen_max_seconds": 30,
    "camera_interval_seconds": 8,
    "event_threshold": 10.0,
    "proactive": True,
    "memory_offer_seconds": 180,
    "personality": (
        "You are Arynox, a friendly real-time AI companion living on an Android "
        "phone. You see through the camera and hear through the microphone. "
        "Speak naturally and concisely in 2 to 4 short sentences, because your "
        "answers are read aloud. Be warm, curious and helpful. If you do not "
        "know something, say so honestly."
    ),
    "local": {
        "enabled": False,
        "llm_url": "http://127.0.0.1:8080",
        "embed_url": "http://127.0.0.1:8081",
        "models_dir": "~/.arynox/models",
        "llm_model": "",
        "vlm_mmproj": "",
        "embed_model": "",
        "context": 2048,
    },
}


def ensure_dirs():
    for d in (DATA_DIR, PHOTOS_DIR, AUDIO_DIR, MODELS_DIR):
        d.mkdir(parents=True, exist_ok=True)


def load():
    cfg = dict(DEFAULTS)
    if CONFIG_FILE.exists():
        try:
            cfg.update(json.loads(CONFIG_FILE.read_text(encoding="utf-8")))
        except Exception:
            pass
    return cfg
