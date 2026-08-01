package com.arynox.app

data class ModelFile(val name: String, val url: String)

data class Tier(
    val llm: ModelFile,
    val mm: ModelFile?,
    val emb: ModelFile?,
    val needGb: Int,
    val label: String,
)

object Models {
    const val ANDROID_ASSET_SUFFIX = "bin-android-arm64.tar.gz"
    const val API = "https://api.github.com/repos/ggml-org/llama.cpp/releases/latest"

    val TIERS = linkedMapOf(
        "lite" to Tier(
            llm = ModelFile(
                "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            ),
            mm = null, emb = null, needGb = 3,
            label = "lite: text-only 1.5B (~1.1 GB)",
        ),
        "standard" to Tier(
            llm = ModelFile(
                "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/unsloth/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf",
            ),
            mm = ModelFile(
                "mmproj-F16.gguf",
                "https://huggingface.co/unsloth/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/mmproj-F16.gguf",
            ),
            emb = ModelFile(
                "bge-small-en-v1.5-q4_k_m.gguf",
                "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q4_k_m.gguf",
            ),
            needGb = 6,
            label = "standard: 3B vision (~3.3 GB)",
        ),
        "pro" to Tier(
            llm = ModelFile(
                "Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/unsloth/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf",
            ),
            mm = ModelFile(
                "mmproj-F16.gguf",
                "https://huggingface.co/unsloth/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/mmproj-F16.gguf",
            ),
            emb = ModelFile(
                "bge-small-en-v1.5-q4_k_m.gguf",
                "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q4_k_m.gguf",
            ),
            needGb = 8,
            label = "pro: 7B vision (~5.9 GB)",
        ),
        "max" to Tier(
            llm = ModelFile(
                "Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/unsloth/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf",
            ),
            mm = ModelFile(
                "mmproj-F16.gguf",
                "https://huggingface.co/unsloth/Qwen2.5-VL-7B-Instruct-GGUF/resolve/main/mmproj-F16.gguf",
            ),
            emb = ModelFile(
                "bge-small-en-v1.5-q4_k_m.gguf",
                "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/main/bge-small-en-v1.5-q4_k_m.gguf",
            ),
            needGb = 8,
            label = "max: 7B vision, large context (~5.9 GB)",
        ),
    )
}
