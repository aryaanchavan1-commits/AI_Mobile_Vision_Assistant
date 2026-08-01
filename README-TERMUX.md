# Arynox Brain - Termux (Android) Setup Guide

Arynox is an on-device AI companion: it watches through your camera, hears through
your microphone, speaks back, answers questions, remembers people and things, opens
apps, searches files, and checks your battery - fully offline after setup.

This guide is for **Android via Termux**. For Windows, see
[README-WINDOWS.md](README-WINDOWS.md).

## Requirements

| Item | Requirement |
|---|---|
| Android | 8+ (arm64 / aarch64) |
| Termux | from [F-Droid](https://f-droid.org/en/packages/com.termux/) or GitHub - **NOT the Play Store version** (it is outdated and breaks setup) |
| RAM | 4 GB minimum, 8 GB recommended |
| Storage | 4-10 GB free depending on tier |
| Internet | Only for the one-time download |

## Install

Open Termux and run:

```bash
pkg install -y git
git clone https://github.com/aryaanchavan1-commits/AI_Mobile_Vision_Assistant.git
cd AI_Mobile_Vision_Assistant
bash setup.sh
```

The setup script:

1. Updates Termux and installs system packages
2. Reads your phone's hardware (brand, Android version, CPU, **RAM**, free storage)
3. **Picks the model tier automatically** for your RAM (downgrades if storage is
   too small; no prompts - override with `ARYNOX_TIER=pro bash setup.sh`)
4. Downloads the prebuilt llama.cpp Android build (fast path) - or compiles it
   from source if the download fails (10-25 minutes, done only once)
5. Downloads the models for your tier
6. Builds whisper.cpp for offline speech-to-text and downloads its model
7. Writes the config, creates `run.sh` / `start-local.sh`
8. Saves an optional Gemini API key from `ARYNOX_GEMINI_KEY` (cloud fallback)
9. Runs a self-test (camera, mic, speech)

The only dialogs are the Android permission popups - **tap Allow** for camera,
microphone, and storage. Everything else runs without any input.

### Model tiers (picked automatically from your RAM)

| Tier | RAM | Models | Size |
|---|---|---|---|
| lite | <= 4 GB | text-only 1.5B | ~1.1 GB |
| standard | 4-8 GB | 3B vision (sees + talks) | ~3.3 GB |
| pro | 8-16 GB | 7B vision | ~5.9 GB |
| max | 16+ GB | 7B vision, large context | ~5.9 GB |

Override anytime with an env var, e.g. `ARYNOX_TIER=lite bash setup.sh`
or `ARYNOX_TIER=none bash setup.sh` for cloud-only mode.

## Run

Setup ends by **starting Arynox automatically**. Later, just type:

```bash
start                          # start Arynox again
stop                           # stop Arynox + local models
```

(`start` and `stop` are aliases added to `~/.bashrc` by setup; they map to
`bash ~/.arynox/run.sh start/stop`.)

Or just say **"stop"**, **"exit"**, **"goodbye"**, or **"go to sleep"** to Arynox -
it will shut down and stop the AI servers itself.

### Self-test

```bash
bash ~/.arynox/run.sh start --demo
```

Tests camera capture, vision description, microphone recording, speech-to-text,
and text-to-speech, speaking the results out loud.

## Voice commands

| You say | What happens |
|---|---|
| what do you see | takes a photo and describes the scene aloud |
| remember this person | stores a visual description in memory |
| who is she / do you know him | recalls people and things from memory |
| forget X | deletes a memory |
| open whatsapp | launches an installed app |
| find my files / search X | searches /sdcard |
| check battery | reads battery via termux-api |
| stop / goodbye / go to sleep | shuts Arynox down |

Anything else is answered by the local AI model.

## What is offline

- Vision + chat: llama-server (Qwen2.5-VL GGUF on device)
- Speech-to-text: whisper-cli (on device)
- Text-to-speech: Android TTS engine, espeak-ng fallback
- Memory: SQLite + local embeddings

## Updating

```bash
cd AI_Mobile_Vision_Assistant
git pull
bash setup.sh          # safe to re-run: skips existing models and builds
```

## Troubleshooting

| Problem | Fix |
|---|---|
| Termux says "command not found" on setup | install from F-Droid/GitHub, not Play Store |
| permission dialogs never appear | run `termux-setup-storage` and restart Termux |
| `--demo` camera fails | grant Termux camera permission in Android settings |
| mic records silence | grant microphone permission |
| no speech-to-text | check `~/.arynox/whisper-build.log`; whisper build failed - Google STT is used instead |
| model download interrupted | re-run `bash setup.sh` (downloads resume) |
| llama-server won't start | check `~/.arynox/llm.log`; if the prebuilt build failed, delete `$PREFIX/bin/llama-server` and re-run setup to compile from source |
| voice "stop" does nothing | whisper misheard it - also works via `bash ~/.arynox/run.sh stop` |
| RAM too low for tier | pick a lower tier or add a Gemini API key to `~/.arynox/config.json` |
