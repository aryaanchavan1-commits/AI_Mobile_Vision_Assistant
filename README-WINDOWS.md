# Arynox Brain - Windows Setup Guide

Arynox is an on-device AI companion: it sees through your webcam, hears through your
microphone, speaks back, answers questions, remembers people and things, opens apps,
searches files, and checks your battery - fully offline after setup.

This guide is for the **Windows prototype**. For Android/Termux, see
[README-TERMUX.md](README-TERMUX.md).

## Requirements

| Item | Requirement |
|---|---|
| Windows | 10 or 11, 64-bit |
| Python | 3.10+ (from [python.org](https://www.python.org/downloads/), tick "Add to PATH") |
| RAM | 4 GB minimum, 8 GB recommended |
| Storage | 4-10 GB free depending on tier |
| Hardware | Webcam and microphone (first use asks for Windows permissions) |
| Internet | Only for the one-time download |

## Install

```bat
git clone https://github.com/aryaanchavan1-commits/AI_Mobile_Vision_Assistant.git
cd AI_Mobile_Vision_Assistant
python setup_windows.py
```

The setup script:

1. Reads your PC's RAM and suggests a model tier
2. Installs Python packages (OpenCV, sounddevice, faster-whisper, pyttsx3, ...)
3. Downloads the official llama.cpp Windows build
4. Downloads the models for your tier
5. Writes the config and creates `start-local.cmd` / `run.cmd`

### Model tiers (pick by RAM)

| Tier | RAM | Models | Size |
|---|---|---|---|
| lite | <= 4 GB | text-only 1.5B | ~1.1 GB |
| standard | 4-8 GB | 3B vision (sees + talks) | ~3.3 GB |
| pro | 8-16 GB | 7B vision | ~5.9 GB |
| max | 16+ GB | 7B vision, large context | ~5.9 GB |

Type `none` to skip local models and use the Gemini cloud only.

## Run

```bat
%USERPROFILE%\.arynox\run.cmd            rem start Arynox (default)
%USERPROFILE%\.arynox\run.cmd status     rem check what is running
%USERPROFILE%\.arynox\run.cmd stop       rem stop Arynox + local models
```

Or just say **"stop"**, **"exit"**, **"goodbye"**, or **"go to sleep"** to Arynox -
it will shut down and stop the AI servers itself.

### Self-test

```bat
cd %USERPROFILE%\.arynox\app
python main.py --demo
```

The demo tests webcam capture, vision description, microphone recording,
speech-to-text, and text-to-speech, speaking the results out loud.

## Voice commands

| You say | What happens |
|---|---|
| what do you see | takes a photo and describes the scene aloud |
| remember this person | stores a visual description in memory |
| who is she / do you know him | recalls people and things from memory |
| forget X | deletes a memory |
| open notepad | launches an app/program |
| find my files / search X | searches your user folder |
| check battery | reads battery percentage via Win32_Battery |
| stop / goodbye / go to sleep | shuts Arynox down |

Anything else is answered by the local AI model.

## What is offline

- Vision + chat: llama-server (Qwen2.5-VL GGUF on device)
- Speech-to-text: faster-whisper (downloads its model on first use, then offline)
- Text-to-speech: Windows voices via pyttsx3
- Memory: SQLite + local embeddings

## Troubleshooting

| Problem | Fix |
|---|---|
| `--demo` camera fails | check webcam drivers; another app may hold the camera |
| mic records silence | check Windows privacy settings (Settings > Privacy > Microphone) |
| no speech-to-text | run `python -m pip install -r requirements-windows.txt` again |
| model download interrupted | re-run `python setup_windows.py` (skips finished files) |
| voice "stop" does nothing | whisper misheard it - also works via `run.cmd stop` |
| RAM too low for tier | pick a lower tier or add a Gemini API key to `%USERPROFILE%\.arynox\config.json` |
