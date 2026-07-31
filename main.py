import argparse

from arynox import brain, config, llm, speech, vision
from arynox.local import ensure_server


def demo(cfg):
    ai = llm.ArynoxAI(cfg)
    backend = "local" if ai.local.available() else ("gemini" if ai.gemini.available() else "none")
    print("backend:", backend)
    print("[demo] camera")
    photo = None
    try:
        photo = vision.capture_photo()
        print("photo:", photo)
    except Exception as exc:
        print("camera failed:", exc)
    if photo and ai.vision_available():
        try:
            desc = ai.describe(photo, brain.DESCRIBE_PROMPT)
            print("vision:", desc)
            speech.speak(desc, cfg)
        except Exception as exc:
            print("vision failed:", exc)
            speech.speak("Vision failed.", cfg)
    else:
        speech.speak(
            "Camera works, but no vision model is available. Run setup and pick "
            "a vision tier, or add a Gemini key.",
            cfg,
        )
    print("[demo] microphone, please speak for 4 seconds")
    speech.speak("Microphone test. Please speak now.", cfg)
    wav = str(config.AUDIO_DIR / "demo.wav")
    path = speech.record(4, wav)
    text = speech.transcribe(path, cfg.get("language", "en-US"))
    print("stt:", repr(text))
    if text == "__NETWORK__":
        speech.speak("Microphone works, but speech recognition needs internet.", cfg)
    elif text:
        speech.speak(f"You said: {text}", cfg)
    else:
        speech.speak("I could not hear anything. Check the microphone permission.", cfg)


def main():
    parser = argparse.ArgumentParser(description="Arynox Brain")
    parser.add_argument("--demo", action="store_true", help="run hardware self-test")
    parser.add_argument("--once", action="store_true", help="listen once then exit")
    args = parser.parse_args()
    cfg = config.load()
    config.ensure_dirs()
    if ensure_server(cfg):
        print("Local models ready.")
    elif cfg.get("local", {}).get("enabled"):
        print("Warning: local models are enabled but not running. Falling back to cloud or basic mode.")
    if args.demo:
        demo(cfg)
        return
    b = brain.ArynoxBrain(cfg)
    if args.once:
        text = speech.listen_once(cfg)
        if text:
            b.handle(text)
        return
    b.run()


if __name__ == "__main__":
    main()
