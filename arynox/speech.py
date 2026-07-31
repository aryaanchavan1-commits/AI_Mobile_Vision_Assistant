import os
import subprocess
import time

try:
    import speech_recognition as sr
except ImportError:
    sr = None

from .config import AUDIO_DIR


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


def transcribe(path, language="en-US"):
    if path is None or sr is None:
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


def listen_once(cfg):
    block = int(cfg.get("listen_block_seconds", 4))
    limit = int(cfg.get("listen_max_seconds", 30))
    lang = cfg.get("language", "en-US")
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
        text = transcribe(path, lang)
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
    for chunk in _chunks(text):
        subprocess.run(
            ["termux-tts-speak", "-r", rate, "-l", lang, chunk],
            timeout=120,
        )


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
