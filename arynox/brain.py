import os
import re
import time

from . import agents, llm, memory, speech, vision

DESCRIBE_PROMPT = (
    "You are the eyes of Arynox. Describe exactly what you see in detail: the "
    "number of people, their appearance (face, hair, skin tone, clothes), the "
    "environment, objects, and any visible text. Say clearly if someone is "
    "looking at the camera. Under 120 words."
)

PERSON_PROMPT = (
    "Describe this person's face and appearance in detail so they can be "
    "identified later: face shape, eyes, hair, clothes, height, accessories. "
    "Under 80 words."
)

_PERSON_WORDS = re.compile(r"person|him|her|he\b|she\b|man|woman|friend|name|them")

STOPPED = "stop"


class ArynoxBrain:
    def __init__(self, cfg):
        self.cfg = cfg
        self.ai = llm.ArynoxAI(cfg)
        self.mem = memory.Memory(cfg, self.ai if self.ai.available() else None)
        self.detector = vision.EventDetector(cfg)
        self.history = []
        self.last_scene = None
        self.last_speech = 0.0
        self.last_glance = 0.0
        self.last_offer = 0.0

    def speak(self, text):
        speech.speak(text, self.cfg)

    def ask_llm(self, text):
        if not self.ai.available():
            self.speak(
                "I have no brain yet. Run setup again to download local models, "
                "or add a Gemini API key."
            )
            return
        system = self.cfg.get("personality", "")
        self.history.append(("user", text))
        self.history = self.history[-12:]
        try:
            reply = self.ai.chat(self.history, system=system, scene=self.last_scene)
        except Exception as exc:
            reply = f"Sorry, I hit an error: {exc}"
        self.history.append(("model", reply))
        self.speak(reply)

    def look(self, prompt=DESCRIBE_PROMPT):
        try:
            photo = vision.capture_photo()
        except Exception:
            return None, None
        if not self.ai.vision_available():
            return photo, None
        try:
            desc = self.ai.describe(photo, prompt)
        except Exception:
            desc = None
        return photo, desc

    def glance(self):
        if not self.ai.vision_available():
            return None
        try:
            photo = vision.capture_photo()
        except Exception:
            return None
        if not self.detector.is_event(photo):
            return None
        try:
            desc = self.ai.describe(photo, DESCRIBE_PROMPT)
        except Exception:
            desc = None
        self.mem.add_event("scene", desc or "Scene changed", photo)
        self.last_scene = desc
        return desc

    def handle(self, text):
        text = text.strip()
        wake = str(self.cfg.get("wake_word", "")).lower().strip()
        if wake and text.lower().startswith(wake):
            text = text[len(wake):].lstrip(", .!?").strip()
        low = text.lower()

        if re.search(r"go to sleep|\b(?:stop|exit|quit|shutdown|goodbye)\b", low):
            self.speak("Stopping. Type bash run.sh start to wake me up.")
            return STOPPED

        m = re.search(r"(?:remember|memorize|note down)\s*(?:this|that)?\s*(.*)", low)
        if m:
            self.do_remember(m.group(1).strip() or "this person")
            return

        m = (
            re.search(r"who\s+(?:is|are|was|were)\s+(?:the\s+)?(.+)", low)
            or re.search(r"do you (?:know|remember|recall)\s+(.+)", low)
            or re.search(r"what\s+(?:is|are)\s+(this|that|he|she|it|they)(?:'s)?\s?(.+)", low)
        )
        if m:
            query = m.group(1) if m.lastindex == 1 else m.group(1) + " " + m.group(2)
            self.do_recall(query.strip())
            return

        m = re.search(r"(?:forget|remove memory of)\s+(.+)", low)
        if m:
            self.do_forget(m.group(1).strip())
            return

        m = re.search(r"(?:open|launch)\s+(?:the\s+)?(?:app\s+)?(.+)", low)
        if m:
            self.do_open(m.group(1).strip())
            return

        m = re.search(r"(?:find|search)\s+(?:for\s+|my\s+)?(.+)", low)
        if m and not low.startswith("search my memory"):
            self.do_files(m.group(1).strip())
            return

        if re.search(r"\bbattery\b", low):
            self.speak(agents.check_battery())
            return

        if re.search(r"\bsensor", low):
            self.speak(agents.sensors())
            return

        if any(k in low for k in (
            "describe", "look at", "what do you see", "what's in front",
            "what is in front", "what's around", "what is around",
        )):
            self.do_describe()
            return

        if any(k in low for k in ("what can you do", "help", "capabilities")):
            self.speak(
                "I can see through your camera, hear you, answer questions, "
                "remember people and things, open apps, search your files, and "
                "check your battery. Try saying, what do you see, or remember "
                "this person."
            )
            return

        self.ask_llm(text)

    def do_remember(self, phrase):
        name = phrase[:40]
        kind = "person" if _PERSON_WORDS.search(phrase) else "thing"
        self.speak("Let me take a look.")
        prompt = PERSON_PROMPT if kind == "person" else DESCRIBE_PROMPT
        photo, desc = self.look(prompt)
        desc = desc or "No visual description available."
        self.mem.remember(name, kind, desc, photo or "")
        self.speak(f"Done. I will remember {name} and describe them if you ask.")
        self.last_offer = time.time()

    def do_recall(self, query):
        rows = self.mem.recall(query)
        if rows:
            best = rows[0]
            self.speak(f"Yes, I remember {best['name']}. {best['description']}")
        else:
            self.speak(
                f"I don't remember anything about {query or 'that'} yet. "
                "Show me and say, remember this."
            )

    def do_forget(self, fragment):
        count = self.mem.forget(fragment)
        if count:
            self.speak(f"Forgot {count} memory.")
        else:
            self.speak(f"I had nothing stored about {fragment}.")

    def do_open(self, name):
        pkg = agents.open_app(name)
        if pkg:
            self.speak(f"Opening {name}.")
        else:
            self.speak(f"I could not find an app called {name}.")

    def do_files(self, query):
        paths = agents.find_files(query)
        if not paths:
            self.speak(
                f"I could not find anything named {query} in your storage. "
                "Make sure storage permission is granted."
            )
        else:
            names = ", ".join(os.path.basename(p) for p in paths[:3])
            self.speak(f"I found {len(paths)} matches, for example {names}.")

    def do_describe(self):
        if not self.ai.vision_available():
            self.speak(
                "My vision is off on this device. Run setup and choose the "
                "standard or pro tier, or add a Gemini API key."
            )
            return
        self.speak("Let me take a look.")
        photo, desc = self.look()
        if not desc:
            self.speak("I could not see anything. Check the camera permission.")
            return
        self.mem.add_event("asked", desc, photo)
        self.speak(desc)
        if _PERSON_WORDS.search(desc.lower()) and time.time() - self.last_offer > self.cfg.get(
            "memory_offer_seconds", 180
        ):
            self.speak("Do you want me to remember this person? Just say, remember them.")
            self.last_offer = time.time()

    def run(self):
        if self.ai.vision_available():
            greeting = (
                "Hello, I am Arynox. I can see and hear you. Try saying, what do "
                "you see, or remember this person."
            )
        else:
            greeting = "Hello, I am Arynox, listening. Ask me anything."
        self.speak(greeting)
        self.last_speech = time.time()
        while True:
            now = time.time()
            if self.ai.vision_available() and self.cfg.get("proactive", True):
                if now - self.last_glance >= self.cfg.get("camera_interval_seconds", 8):
                    self.last_glance = now
                    desc = self.glance()
                    if (
                        desc
                        and now - self.last_speech > 15
                        and now - self.last_offer > self.cfg.get("memory_offer_seconds", 180)
                        and _PERSON_WORDS.search(desc.lower())
                    ):
                        self.speak(
                            f"Hello. I can see {desc[:140]} You can ask me anything, "
                            "or say remember, to remember this person."
                        )
                        self.last_offer = now
            text = speech.listen_once(self.cfg)
            if text:
                self.last_speech = time.time()
                if self.handle(text) == STOPPED:
                    break
