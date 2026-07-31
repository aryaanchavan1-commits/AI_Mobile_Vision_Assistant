import base64
import os
import subprocess

import requests

from .config import DATA_DIR


class Local:
    def __init__(self, cfg):
        local_cfg = cfg.get("local", {}) or {}
        self.enabled = (
            cfg.get("backend", "auto") != "gemini" and bool(local_cfg.get("enabled"))
        )
        self.url = str(local_cfg.get("llm_url", "http://127.0.0.1:8080")).rstrip("/")
        embed_url = str(local_cfg.get("embed_url", "")).strip()
        self.embed_url = embed_url.rstrip("/") if embed_url else ""
        self.mmproj = str(local_cfg.get("vlm_mmproj", "")).strip()

    def healthy(self, url=None):
        target = url or self.url
        try:
            resp = requests.get(target + "/health", timeout=3)
            return resp.status_code == 200 and resp.json().get("status") == "ok"
        except Exception:
            return False

    def available(self):
        return self.enabled and self.healthy()

    def vision_available(self):
        return self.enabled and bool(self.mmproj) and self.healthy()

    def chat(self, messages, system=None, scene=None):
        payload_messages = []
        if system:
            text = system
            if scene:
                text += f"\nRight now you see: {scene}"
            payload_messages.append({"role": "system", "content": text})
        payload_messages += [
            {"role": role, "content": content} for role, content in messages
        ]
        payload = {
            "messages": payload_messages,
            "temperature": 0.7,
            "max_tokens": 256,
        }
        resp = requests.post(self.url + "/v1/chat/completions", json=payload, timeout=180)
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"].strip()

    def describe(self, image_path, prompt):
        with open(image_path, "rb") as f:
            b64 = base64.b64encode(f.read()).decode()
        payload = {
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {
                            "type": "image_url",
                            "image_url": {"url": f"data:image/jpeg;base64,{b64}"},
                        },
                    ],
                }
            ],
            "temperature": 0.6,
            "max_tokens": 256,
        }
        resp = requests.post(self.url + "/v1/chat/completions", json=payload, timeout=180)
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"].strip()

    def embed(self, text):
        if not self.enabled or not self.embed_url:
            return None
        try:
            resp = requests.post(
                self.embed_url + "/v1/embeddings", json={"input": text}, timeout=60
            )
            if resp.status_code != 200:
                return None
            return resp.json()["data"][0]["embedding"]
        except Exception:
            return None


def stop_server(cfg):
    local_cfg = cfg.get("local", {}) or {}
    if not local_cfg.get("enabled"):
        return
    try:
        subprocess.run(["pkill", "-f", "llama-server"], capture_output=True)
    except Exception:
        pass


def ensure_server(cfg):
    local_cfg = cfg.get("local", {}) or {}
    if cfg.get("backend", "auto") == "gemini" or not local_cfg.get("enabled"):
        return False
    if not local_cfg.get("llm_model"):
        return False
    probe = Local(cfg)
    if probe.healthy():
        return True
    script = DATA_DIR / "start-local.sh"
    if not script.exists():
        return False
    try:
        subprocess.run(["bash", str(script)], timeout=300)
    except Exception:
        return False
    return probe.healthy()
