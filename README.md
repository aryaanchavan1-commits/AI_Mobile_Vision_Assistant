# Arynox Brain

An on-device AI vision assistant for your Android phone (Termux) and Windows PC.
Arynox sees through your camera, hears your voice, remembers people and things,
and runs **fully offline** — no cloud, no data leaving your device (unless you
opt into the optional Gemini cloud fallback).

```
You:        "what do you see?"
Arynox:     "I see two people sitting at a table with laptops. The person on
             the left is wearing a blue shirt..."
You:        "remember this person"
Arynox:     "Done. I will remember them and describe them if you ask."
You:        "stop"
Arynox:     "Stopping."
```

## Features

- **Vision** — Qwen2.5-VL runs on-device: describe scenes, people, objects, text
- **Speech** — hear you (whisper.cpp / faster-whisper) and talk back
  (Android TTS / espeak-ng / Windows SAPI), all offline
- **Memory** — remembers people and things in SQLite with local embeddings;
  ask *"who is sam?"*, *"what do you remember?"*
- **Agents** — open apps, search your files, check battery, read sensors
- **Proactive** — notices scene changes and greets people on its own
- **Voice control** — say "stop" / "go to sleep" to shut down; start again with
  one word
- **Typed mode** — no microphone (WSL, headless)? It just falls back to typing

## Quick start

### Android (Termux, from F-Droid)

```bash
termux-setup-storage
git clone https://github.com/aryaanchavan1-commits/AI_Mobile_Vision_Assistant.git
cd AI_Mobile_Vision_Assistant
bash setup.sh
```

Setup detects your phone's RAM, downloads the right model tier, builds the AI
runtimes, self-tests the hardware, and starts Arynox. After that, type
`start` / `stop` to control it. Full guide: [README-TERMUX.md](README-TERMUX.md)

### Windows / WSL

```bat
git clone https://github.com/aryaanchavan1-commits/AI_Mobile_Vision_Assistant.git
cd AI_Mobile_Vision_Assistant
python setup_windows.py
```

Setup picks a tier for your RAM, downloads the llama.cpp build and models
(resumable), then starts Arynox. Use `start.cmd` / `stop.cmd` to control it.
Full guide: [README-WINDOWS.md](README-WINDOWS.md)

### Windows desktop app (EXE)

```
cd desktop
npm install
npx electron-builder --win nsis      # -> dist/Arynox-Setup-1.0.0.exe
```

The app detects your PC (RAM, NVIDIA GPU, OS), downloads the matching
llama.cpp build and models, and gives you a chat + vision UI — all offline.

### Android app (APK)

```
cd mobile
gradlew assembleDebug                # -> app/build/outputs/apk/debug/app-debug.apk
```

The app detects your phone (RAM, storage, CPU), downloads the right model tier
and the arm64 llama.cpp build, runs a local server in app storage, and gives
you an offline chat + photo vision UI (minSdk 26, arm64-v8a).

## Model tiers

| Tier  | Size   | Models                                    | Best for      |
|-------|--------|-------------------------------------------|---------------|
| lite  | ~1.1 GB| 1.5B text-only                           | ≤ 4 GB RAM     |
| standard | ~3.3 GB | 3B vision (sees + talks)               | 4–8 GB RAM     |
| pro   | ~5.9 GB| 7B vision                                | 8–16 GB RAM    |
| max   | ~5.9 GB| 7B vision, larger context                | 16 GB+ RAM     |
| none  | 0      | Gemini cloud fallback (optional API key) | anything       |

## Voice commands

| Say / type                           | Action                                  |
|--------------------------------------|-----------------------------------------|
| "what do you see" / "describe"       | camera description                      |
| "remember this person"               | store the person (with photo)           |
| "who is sam" / "do you remember sam" | recall a memory                         |
| "what do you remember"               | list memories                           |
| "forget sam"                         | delete a memory                         |
| "open whatsapp"                      | launch an app                           |
| "find my resume"                     | search storage                          |
| "check battery"                      | battery level                           |
| "stop" / "go to sleep"               | shut down and stop the AI servers       |
| "what can you do"                    | help                                    |

## Project layout

```
main.py                  entry point (--demo self-test, --once single listen)
setup.sh                 Termux installer (Android)
setup_windows.py         Windows / WSL installer
desktop/                 Electron app (system detection, chat + vision UI)
mobile/                  Kotlin Android app (system detection, chat + vision UI)
arynox/
  brain.py               conversation loop, intent routing
  vision.py              camera capture + scene-change detection
  speech.py              recording, STT (whisper/google), TTS with fallbacks
  memory.py              SQLite memory + FTS5 + vector recall
  agents.py              apps, files, battery, sensors
  llm.py                 local (llama-server) + Gemini backends
  local.py               llama-server client (chat, vision, embeddings)
  models.py              model tier definitions and URLs
  config.py              paths and defaults
tests/test_brain.py      offline smoke tests (any Python 3)
```

## Test

```bash
python tests/test_brain.py        # offline brain + memory tests
python main.py --demo             # hardware self-test (camera, mic, TTS)
```

## Publishing

### Windows installer (EXE)

```powershell
cd desktop
npm install
npx electron-builder --win nsis          # signed/signing-skipped installer
```

The installer is a guided NSIS setup (choose folder, desktop shortcut). To
remove the SmartScreen "unknown publisher" warning, code-sign it with any
Windows code-signing certificate: `electron-builder --config.win.signingHashAlgorithms=sha256` with `CSC_LINK`/`CSC_KEY_PASSWORD` set, or publish unsigned.

### Android APK

```powershell
cd mobile
.\gradlew.bat assembleRelease
```

- `app/build/outputs/apk/release/app-release.apk` is signed with your release
  keystore. The keystore lives at `mobile/keystore/arynox-release.jks` with
  credentials in `mobile/key.properties` (both gitignored — **back them up**;
  you need them for every future update of the app on the same package name).
- Rebuild the icons any time with: `powershell -File tools/build_icons.ps1`
- Distributing on the Play Store additionally requires a Google Play App
  Signing setup (upload key) and signing an AAB:
  `.\gradlew.bat bundleRelease`

### GitHub Releases

Tag a version and push; attach `desktop/dist/Arynox-Setup-*.exe` and
`mobile/app/build/outputs/apk/release/app-release.apk` to the release — those
are the two files your users install.

## Config

`~/.arynox/config.json` — RAM tier, wake word, language, personality, Gemini
key, local backend settings. See the per-platform READMEs for details.

## License

MIT — see [LICENSE](LICENSE).
