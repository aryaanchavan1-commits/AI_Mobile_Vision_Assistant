import os
import subprocess

from PIL import Image

from .config import PHOTOS_DIR


class EventDetector:
    def __init__(self, cfg):
        self.threshold = float(cfg.get("event_threshold", 10))
        self.baseline = None

    def is_event(self, image_path):
        img = Image.open(image_path).convert("L").resize((80, 60))
        frame = list(img.getdata())
        if self.baseline is None:
            self.baseline = frame
            return False
        diff = sum(abs(a - b) for a, b in zip(frame, self.baseline)) / len(frame)
        self.baseline = frame
        return diff >= self.threshold


def capture_photo(path=None):
    if path is None:
        PHOTOS_DIR.mkdir(parents=True, exist_ok=True)
        existing = [f for f in os.listdir(PHOTOS_DIR) if f.endswith(".jpg")]
        path = str(PHOTOS_DIR / f"snap_{len(existing) + 1:04d}.jpg")
    path = os.path.abspath(path)
    subprocess.run(["termux-camera-photo", "-c", "0", path], check=True, timeout=30)
    return path
