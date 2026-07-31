TIERS = {
    "lite": {
        "llm": ("qwen2.5-1.5b-instruct-q4_k_m.gguf",
                "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"),
        "mm": None,
        "emb": None,
        "ctx": 2048,
        "need_mb": 3000,
        "help": "lite     ~1.1 GB   text-only 1.5B model (4 GB RAM or less)",
    },
    "standard": {
        "llm": ("Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/unsloth/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"),
        "mm": ("mmproj-F16.gguf",
               "https://huggingface.co/unsloth/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/mmproj-F16.gguf"),
        "emb": ("bge-small-en-v1.5-q4_k_m.gguf",
                "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q4_k_m.gguf"),
        "ctx": 2048,
        "need_mb": 6000,
        "help": "standard ~3.3 GB   3B vision model, sees and talks (4-8 GB RAM)",
    },
    "pro": {
        "llm": ("Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/unsloth/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf"),
        "mm": ("mmproj-F16.gguf",
               "https://huggingface.co/unsloth/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/mmproj-F16.gguf"),
        "emb": ("bge-small-en-v1.5-q4_k_m.gguf",
                "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q4_k_m.gguf"),
        "ctx": 2048,
        "need_mb": 8000,
        "help": "pro      ~5.9 GB   7B vision model (8-16 GB RAM)",
    },
    "max": {
        "llm": ("Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/unsloth/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf"),
        "mm": ("mmproj-F16.gguf",
               "https://huggingface.co/unsloth/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/mmproj-F16.gguf"),
        "emb": ("bge-small-en-v1.5-q4_k_m.gguf",
                "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q4_k_m.gguf"),
        "ctx": 4096,
        "need_mb": 8000,
        "help": "max      ~5.9 GB   7B vision, large context (16 GB+ RAM)",
    },
}

WHISPER_MODELS = {
    "lite": ("ggml-tiny.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"),
    "default": ("ggml-base.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"),
}

LLAMA_ASSET_PATTERN = "bin-win-cpu-x64.zip"
