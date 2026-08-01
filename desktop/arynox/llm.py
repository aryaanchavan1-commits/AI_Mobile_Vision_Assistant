import base64

import requests

from .local import Local

API = "https://generativelanguage.googleapis.com/v1beta"


def _with_fallbacks(model):
    chain = [model] if model else []
    chain += ["gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash"]
    seen, out = set(), []
    for m in chain:
        if m and m not in seen:
            seen.add(m)
            out.append(m)
    return out


class Gemini:
    def __init__(self, cfg):
        self.key = str(cfg.get("gemini_api_key", "")).strip()
        self.chat_models = _with_fallbacks(cfg.get("chat_model"))
        self.vision_models = _with_fallbacks(cfg.get("vision_model"))
        self.embedding_models = _with_fallbacks(cfg.get("embedding_model")) + [
            "text-embedding-004"
        ]

    def available(self):
        return bool(self.key)

    def _post(self, models, path, payload):
        if not self.key:
            raise RuntimeError("No Gemini API key configured")
        last = None
        for model in models:
            url = f"{API}/models/{model}:{path}"
            try:
                resp = requests.post(url, params={"key": self.key}, json=payload, timeout=60)
            except requests.RequestException as exc:
                raise RuntimeError(f"Network error: {exc}")
            if resp.status_code == 404:
                last = f"model {model} not available"
                continue
            if resp.status_code != 200:
                raise RuntimeError(f"Gemini API error {resp.status_code}: {resp.text[:300]}")
            data = resp.json()
            if data.get("candidates"):
                parts = data["candidates"][0].get("content", {}).get("parts", [])
                text = "".join(p.get("text", "") for p in parts).strip()
                if text:
                    return text
            raise RuntimeError(f"Gemini returned no content: {data}")
        raise RuntimeError(f"Gemini: {last}")

    def chat(self, messages, system=None, scene=None):
        contents = [{"role": role, "parts": [{"text": text}]} for role, text in messages]
        payload = {"contents": contents, "generationConfig": {"temperature": 0.7}}
        if system:
            payload["systemInstruction"] = {"parts": [{"text": system}]}
        if scene:
            payload["systemInstruction"]["parts"][0]["text"] += f"\nRight now you see: {scene}"
        return self._post(self.chat_models, "generateContent", payload)

    def describe(self, image_path, prompt):
        with open(image_path, "rb") as f:
            b64 = base64.b64encode(f.read()).decode()
        payload = {
            "contents": [
                {
                    "role": "user",
                    "parts": [
                        {"text": prompt},
                        {"inline_data": {"mime_type": "image/jpeg", "data": b64}},
                    ],
                }
            ]
        }
        return self._post(self.vision_models, "generateContent", payload)

    def embed(self, text):
        if not self.key:
            return None
        payload = {"content": {"parts": [{"text": text}]}}
        for model in self.embedding_models:
            url = f"{API}/models/{model}:embedContent"
            try:
                resp = requests.post(url, params={"key": self.key}, json=payload, timeout=30)
            except requests.RequestException:
                return None
            if resp.status_code != 200:
                continue
            try:
                values = resp.json()["embedding"]["values"]
                if values:
                    return values
            except Exception:
                continue
        return None


class ArynoxAI:
    def __init__(self, cfg):
        self.local = Local(cfg)
        self.gemini = Gemini(cfg)

    def available(self):
        return self.local.available() or self.gemini.available()

    def vision_available(self):
        return self.local.vision_available() or self.gemini.available()

    def chat(self, messages, system=None, scene=None):
        if self.local.available():
            return self.local.chat(messages, system, scene)
        return self.gemini.chat(messages, system, scene)

    def describe(self, image_path, prompt):
        if self.local.vision_available():
            try:
                return self.local.describe(image_path, prompt)
            except Exception:
                pass
        return self.gemini.describe(image_path, prompt)

    def embed(self, text):
        vec = self.local.embed(text)
        if vec:
            return vec
        return self.gemini.embed(text)
