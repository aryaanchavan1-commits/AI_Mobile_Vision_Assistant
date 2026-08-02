package com.arynox.app

data class ModelFile(val name: String, val url: String)

data class Tier(
    val llm: ModelFile,
    val mm: ModelFile?,
    val emb: ModelFile?,
    val needGb: Int,
    val ctx: Int,
    val label: String,
)

object Models {
    const val ANDROID_ASSET_SUFFIX = "bin-android-arm64.tar.gz"
    const val API = "https://api.github.com/repos/ggml-org/llama.cpp/releases/latest"

    val TIERS = linkedMapOf(
        "lite" to Tier(
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
            needGb = 5, ctx = 2048,
            label = "lite: 3B vision - sees, talks, remembers (small phones)",
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
            needGb = 5, ctx = 4096,
            label = "standard: 3B vision, larger memory",
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
            needGb = 8, ctx = 4096,
            label = "pro: 7B vision - sharper eyes",
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
            needGb = 8, ctx = 8192,
            label = "max: 7B vision, big memory",
        ),
    )
}
