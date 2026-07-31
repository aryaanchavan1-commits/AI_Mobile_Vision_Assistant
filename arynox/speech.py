import os
import shutil
import subprocess
import time
from pathlib import Path

try:
    import speech_recognition as sr
except ImportError:
    sr = None

from .config import AUDIO_DIR

TTS_UNUSABLE = False


def record(duration, path):
    AUDIO_DIR.mkdir(parents=True, exist_ok=True)
    path = os.path.abspath(path)
    if os.path.exists(path):
        os.remove(path)
    try:
        subprocess.run(
            ["termux-microphone-record", "-l", str(int(max(duration, 1))), "-f", path],
            timeout=duration + 8,
        )
    except subprocess.TimeoutExpired:
        pass
    subprocess.run(["termux-microphone-record", "-q"], timeout=5)
    if not os.path.exists(path) or os.path.getsize(path) < 1024:
        return None
    return path


def _models_dir(cfg):
    return Path(
        os.path.expanduser((cfg.get("local", {}) or {}).get("models_dir", "~/.arynox/models"))
    )


def whisper_available(cfg):
    if shutil.which("whisper-cli") is None:
        return False
    model = str(cfg.get("whisper_model") or "")
    if not model:
        return False
    return (_models_dir(cfg) / model).exists()


def whisper_transcribe(path, cfg):
    model = str(cfg.get("whisper_model") or "ggml-base.bin")
    model_path = _models_dir(cfg) / model
    if not model_path.exists():
        return None
    lang = str(cfg.get("language", "en-US"))[:2]
    out = str(AUDIO_DIR / "whisper_out")
    for suffix in (".txt", ".srt", ".json"):
        try:
            os.remove(out + suffix)
        except OSError:
            pass
    cmd = [
        "whisper-cli",
        "-m", str(model_path),
        "-f", os.path.abspath(path),
        "-l", lang,
        "-otxt",
        "-of", out,
        "--no-prints",
        "-t", "4",
    ]
    try:
        result = subprocess.run(cmd, timeout=180)
        if result.returncode != 0:
            return None
        txt = out + ".txt"
        if os.path.exists(txt):
            text = Path(txt).read_text(encoding="utf-8", errors="ignore").strip()
            return text or None
    except Exception:
        return None
    return None


def google_transcribe(path, language):
    if sr is None:
        return None
    recognizer = sr.Recognizer()
    try:
        with sr.AudioFile(path) as source:
            audio = recognizer.record(source)
        return recognizer.recognize_google(audio, language=language)
    except sr.UnknownValueError:
        return None
    except sr.RequestError:
        return "__NETWORK__"
    except Exception:
        return None


def transcribe(path, cfg):
    if path is None:
        return None
    language = str(cfg.get("language", "en-US"))
    engine = str(cfg.get("stt", "auto")).lower()
    if engine == "google":
        return google_transcribe(path, language)
    if engine == "local" or (engine == "auto" and whisper_available(cfg)):
        text = whisper_transcribe(path, cfg)
        if text is not None or engine == "local":
            return text
    text = google_transcribe(path, language)
    if text == "__NETWORK__":
        return whisper_transcribe(path, cfg)
    return text


def listen_once(cfg):
    block = int(cfg.get("listen_block_seconds", 4))
    limit = int(cfg.get("listen_max_seconds", 30))
    transcript = ""
    empty = 0
    total = 0
    while total < limit:
        wav = str(AUDIO_DIR / "listen.wav")
        path = record(min(block, limit - total), wav)
        total += block
        if path is None:
            empty += 1
            if transcript and empty >= 2:
                break
            continue
        text = transcribe(path, cfg)
        if text == "__NETWORK__":
            time.sleep(2)
            empty += 1
            continue
        if text:
            transcript = (transcript + " " + text).strip()
            empty = 0
        else:
            empty += 1
        if transcript and empty >= 2:
            break
    return transcript or None


def speak(text, cfg):
    text = (text or "").strip()
    if not text:
        return
    rate = str(cfg.get("tts_rate", 1.05))
    lang = str(cfg.get("language", "en-US"))[:2]
    global TTS_UNUSABLE
    for chunk in _chunks(text):
        ok = False
        if not TTS_UNUSABLE:
            ok = _speak_tts(chunk, rate, lang)
            if not ok:
                TTS_UNUSABLE = True
        if not ok:
            _speak_espeak(chunk, lang)


def _speak_tts(text, rate, lang):
    try:
        result = subprocess.run(
            ["termux-tts-speak", "-r", rate, "-l", lang, text], timeout=120
        )
        return result.returncode == 0
    except Exception:
        return False


def _speak_espeak(text, lang):
    try:
        subprocess.run(["espeak-ng", "-v", lang, "-s", "170", text], timeout=120)
    except Exception:
        pass


def _chunks(text, size=400):
    words = text.split()
    parts, buf, n = [], "", 0
    for word in words:
        if n + len(word) > size:
            parts.append(buf.strip())
            buf, n = "", 0
        buf += word + " "
        n += len(word) + 1
    if buf.strip():
        parts.append(buf.strip())
    return parts or [""]
