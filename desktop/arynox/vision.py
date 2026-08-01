import os
import subprocess

from PIL import Image

from .config import IS_WINDOWS, PHOTOS_DIR


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


def _windows_capture(path):
    import cv2

    cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
    if not cap.isOpened():
        cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        raise RuntimeError("No webcam found")
    ok, frame = cap.read()
    cap.release()
    if not ok:
        raise RuntimeError("Could not read from the webcam")
    cv2.imwrite(path, frame)
    return path


def capture_photo(path=None):
    if path is None:
        PHOTOS_DIR.mkdir(parents=True, exist_ok=True)
        existing = [f for f in os.listdir(PHOTOS_DIR) if f.endswith(".jpg")]
        path = str(PHOTOS_DIR / f"snap_{len(existing) + 1:04d}.jpg")
    path = os.path.abspath(path)
    if IS_WINDOWS:
        return _windows_capture(path)
    subprocess.run(["termux-camera-photo", "-c", "0", path], check=True, timeout=30)
    return path
