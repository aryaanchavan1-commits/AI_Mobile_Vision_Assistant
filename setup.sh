#!/data/data/com.termux/files/usr/bin/bash
set -e

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$HOME/.arynox"
MODELS_DIR="$APP_DIR/models"

echo "============================================="
echo "   ARYNOX BRAIN - Termux Installer"
echo "============================================="
echo
echo "If you copied this folder from a PC, put it in your home dir first:"
echo "  termux-setup-storage"
echo "  cp -r /sdcard/Download/arynox ~/arynox"
echo "  bash ~/arynox/setup.sh"
echo

echo "[1/7] Updating Termux packages"
pkg update -y
pkg upgrade -y

echo "[2/7] Installing system packages"
pkg install -y python python-pillow termux-api flac ffmpeg

echo "[3/7] Detecting your phone"
RAM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)
STORAGE_MB=$(df -m "$HOME" 2>/dev/null | awk 'NR==2 {print $4}' || echo 0)
CORES=$(nproc 2>/dev/null || echo "?")
if command -v getprop >/dev/null 2>&1; then
  BRAND=$(getprop ro.product.brand 2>/dev/null || echo unknown)
  DEVICE_MODEL=$(getprop ro.product.model 2>/dev/null || echo unknown)
  ANDROID=$(getprop ro.build.version.release 2>/dev/null || echo unknown)
  ABI=$(getprop ro.product.cpu.abi 2>/dev/null || echo unknown)
else
  BRAND="unknown"; DEVICE_MODEL="unknown"; ANDROID="unknown"; ABI="unknown"
fi
echo "  Phone   : $BRAND $DEVICE_MODEL"
echo "  Android : $ANDROID"
echo "  CPU     : $ABI, $CORES cores"
echo "  RAM     : ${RAM_MB} MB"
echo "  Free    : ${STORAGE_MB} MB"
echo

if [ -n "$ABI" ] && [ "$ABI" != "arm64-v8a" ] && [ "$ABI" != "unknown" ]; then
  echo "  Warning: local models are built for arm64. Only cloud (Gemini) mode will work well."
fi

AUTO_TIER="standard"
if [ "$RAM_MB" -gt 0 ] && [ "$RAM_MB" -lt 4000 ]; then AUTO_TIER="lite"
elif [ "$RAM_MB" -lt 8000 ]; then AUTO_TIER="standard"
elif [ "$RAM_MB" -lt 16000 ]; then AUTO_TIER="pro"
else AUTO_TIER="max"
fi

echo "[4/7] Local AI models (chosen for your RAM)"
echo "  lite     ~1.1 GB   text-only 1.5B model (4 GB RAM or less)"
echo "  standard ~5.5 GB   3B vision model, sees and talks (4-8 GB RAM)"
echo "  pro      ~6.3 GB   7B vision model (8-16 GB RAM)"
echo "  max      ~6.3 GB   7B vision, large context (16 GB+ RAM)"
echo "  none     no downloads, uses Gemini cloud instead"
read -rp "  Choose tier [$AUTO_TIER]: " TIER
TIER="${TIER:-$AUTO_TIER}"

case "$TIER" in
  lite)
    LLM_NAME="qwen2.5-1.5b-instruct-q4_k_m.gguf"
    LLM_URL="https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
    MM=""; EMB=""; CTX=2048
    ;;
  standard)
    LLM_NAME="qwen2.5-vl-3b-instruct-q4_k_m.gguf"
    LLM_URL="https://huggingface.co/Qwen/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/qwen2.5-vl-3b-instruct-q4_k_m.gguf"
    MM="qwen2.5-vl-3b-instruct-mmproj-f16.gguf"
    MM_URL="https://huggingface.co/Qwen/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/qwen2.5-vl-3b-instruct-mmproj-f16.gguf"
    EMB="bge-small-en-v1.5-q5_k_m.gguf"; CTX=2048
    ;;
  pro)
    LLM_NAME="qwen2.5-vl-7b-instruct-q4_k_m.gguf"
    LLM_URL="https://huggingface.co/Qwen/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/qwen2.5-vl-7b-instruct-q4_k_m.gguf"
    MM="qwen2.5-vl-7b-instruct-mmproj-f16.gguf"
    MM_URL="https://huggingface.co/Qwen/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/qwen2.5-vl-7b-instruct-mmproj-f16.gguf"
    EMB="bge-small-en-v1.5-q5_k_m.gguf"; CTX=2048
    ;;
  max)
    LLM_NAME="qwen2.5-vl-7b-instruct-q4_k_m.gguf"
    LLM_URL="https://huggingface.co/Qwen/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/qwen2.5-vl-7b-instruct-q4_k_m.gguf"
    MM="qwen2.5-vl-7b-instruct-mmproj-f16.gguf"
    MM_URL="https://huggingface.co/Qwen/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/qwen2.5-vl-7b-instruct-mmproj-f16.gguf"
    EMB="bge-small-en-v1.5-q5_k_m.gguf"; CTX=4096
    ;;
  none)
    TIER=""
    ;;
  *)
    echo "Invalid tier. Re-run setup."
    exit 1
    ;;
esac

if [ -n "$TIER" ]; then
  case "$TIER" in
    lite) NEED_MB=1500 ;;
    standard) NEED_MB=6000 ;;
    *) NEED_MB=7000 ;;
  esac
  if [ "${STORAGE_MB:-0}" -gt 0 ] && [ "$STORAGE_MB" -lt "$NEED_MB" ]; then
    echo "  Warning: only ${STORAGE_MB} MB free, this tier needs ~${NEED_MB} MB."
    read -rp "  Continue anyway? [y/N]: " ANS
    [ "$ANS" = "y" ] || exit 1
  fi
  echo "  Installing the local model runtime (llama.cpp)"
  pkg install -y llama-cpp || {
    echo "  llama-cpp is not available in the Termux repo on this device."
    echo "  Falling back to cloud (Gemini) mode."
    TIER=""
  }
fi

if [ -n "$TIER" ]; then
  mkdir -p "$MODELS_DIR"
  download() {
    local name="$1" url="$2"
    if [ -s "$MODELS_DIR/$name" ]; then
      echo "  $name already downloaded"
      return 0
    fi
    echo "  Downloading $name"
    curl -L --fail --retry 3 -C - -o "$MODELS_DIR/$name" "$url" || return 1
  }
  download "$LLM_NAME" "$LLM_URL" || echo "  FAILED: $LLM_NAME"
  if [ -n "$MM" ]; then download "$MM" "$MM_URL" || echo "  FAILED: $MM"; fi
  if [ -n "$EMB" ]; then
    download "$EMB" "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q5_k_m.gguf" || echo "  FAILED: $EMB"
  fi
  echo "  Installed model files:"
  for f in "$MODELS_DIR"/*.gguf; do
    [ -e "$f" ] || continue
    ls -lh "$f" | awk '{print "    "$9" = "$5}'
  done
fi

echo "[5/7] Installing Python packages"
pip install --upgrade pip
pip install -r "$SRC_DIR/requirements.txt"

echo "[6/7] Installing Arynox files and config"
mkdir -p "$APP_DIR/app"
cp -r "$SRC_DIR/arynox" "$APP_DIR/app/"
cp "$SRC_DIR/main.py" "$APP_DIR/app/"
if [ ! -f "$APP_DIR/config.json" ]; then
    cp "$SRC_DIR/config.json" "$APP_DIR/config.json"
fi

TIER="$TIER" LLM_NAME="$LLM_NAME" MM="$MM" EMB="$EMB" CTX="$CTX" python3 - <<'PY'
import json, os
p = os.path.expanduser("~/.arynox/config.json")
c = json.load(open(p))
tier = os.environ.get("TIER", "")
c["local"] = {
    "enabled": bool(tier),
    "llm_url": "http://127.0.0.1:8080",
    "embed_url": "http://127.0.0.1:8081" if os.environ.get("EMB") else "",
    "models_dir": os.path.expanduser("~/.arynox/models"),
    "llm_model": os.environ.get("LLM_NAME", ""),
    "vlm_mmproj": os.environ.get("MM", ""),
    "embed_model": os.environ.get("EMB", ""),
    "context": int(os.environ.get("CTX", "2048")),
}
json.dump(c, open(p, "w"), indent=2)
print("  Local backend:", tier or "disabled (cloud mode)")
PY

if [ -n "$TIER" ]; then
  cat > "$APP_DIR/start-local.sh" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
MODELS="$MODELS_DIR"
LLM="$LLM_NAME"
MM="$MM"
EMB="$EMB"
CTX=$CTX
LOG="$APP_DIR"
if pgrep -f "llama-server" > /dev/null 2>&1; then
  echo "Local AI is already running."
  exit 0
fi
echo "Starting local AI (this takes a few seconds)..."
if [ -n "\$MM" ]; then
  nohup llama-server -m "\$MODELS/\$LLM" --mmproj "\$MODELS/\$MM" -c \$CTX -n 512 --jinja --host 127.0.0.1 --port 8080 > "\$LOG/llm.log" 2>&1 &
else
  nohup llama-server -m "\$MODELS/\$LLM" -c \$CTX -n 512 --jinja --host 127.0.0.1 --port 8080 > "\$LOG/llm.log" 2>&1 &
fi
if [ -n "\$EMB" ]; then
  nohup llama-server -m "\$MODELS/\$EMB" --embeddings --pooling mean -c 512 --host 127.0.0.1 --port 8081 > "\$LOG/embed.log" 2>&1 &
fi
for i in \$(seq 1 120); do
  if curl -sf http://127.0.0.1:8080/health > /dev/null 2>&1; then
    echo "Local AI is ready."
    exit 0
  fi
  sleep 1
done
echo "Local AI failed to start. Check \$LOG/llm.log"
exit 1
EOF
  chmod +x "$APP_DIR/start-local.sh"
fi

echo "[7/7] Gemini API key (optional, used as fallback or cloud mode)"
python3 - <<'PY'
import json, os
p = os.path.expanduser("~/.arynox/config.json")
key = json.load(open(p)).get("gemini_api_key", "")
print("  Current key:", (key[:12] + "...") if key else "(none)")
PY
read -rp "  Paste a free Gemini API key from https://aistudio.google.com (Enter to skip): " KEY
if [ -n "$KEY" ]; then
    python3 - "$KEY" <<'PY'
import json, os, sys
p = os.path.expanduser("~/.arynox/config.json")
c = json.load(open(p))
c["gemini_api_key"] = sys.argv[1].strip()
json.dump(c, open(p, "w"), indent=2)
print("  Key saved.")
PY
fi

cat > "$APP_DIR/run.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
cd "$HOME/.arynox/app"
exec python3 main.py "$@"
EOF
chmod +x "$APP_DIR/run.sh"

echo "Final steps: permissions and self-test"
termux-setup-storage || true
termux-wake-lock || true
python3 "$APP_DIR/app/main.py" --demo || true

echo
echo "Setup complete."
echo "Start Arynox:     bash ~/.arynox/run.sh"
echo "Self-test again:  bash ~/.arynox/run.sh --demo"
echo "Stop models:      killall llama-server"
echo "Config file:      ~/.arynox/config.json"
