package com.arynox.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMsg(val role: String, val text: String)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val modelsDir = File(app.filesDir, "models")
    private val llamaDir = File(app.filesDir, "llama")

    sealed class Phase {
        object Detect : Phase()
        object Download : Phase()
        object Start : Phase()
        object Running : Phase()
        object Error : Phase()
    }

    val phase = MutableStateFlow<Phase>(Phase.Detect)
    val logs = MutableStateFlow<List<String>>(emptyList())
    val messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val installProgress = MutableStateFlow<Pair<String, Float>?>(null)
    val tier = MutableStateFlow("")
    val installed = MutableStateFlow(false)

    private val history = JSONArray()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val t = Detector.tier(getApplication())
                tier.value = t
                log("Arynox APK v1.0.0")
                log("RAM: %.1f GB | Free: %.1f GB | ABI: arm64-v8a".format(
                    Detector.ramGb(getApplication()), Detector.freeGb(getApplication())))
                log("Selected tier: ${Models.TIERS[t]!!.label}")
                if (!Detector.abiSupported()) {
                    phase.value = Phase.Error
                    log("ERROR: this device is not arm64-v8a; llama.cpp needs a 64-bit ARM CPU.")
                } else if (modelsDir.listFiles()?.isNotEmpty() == true &&
                    File(modelsDir, Models.TIERS[t]!!.llm.name).exists()
                ) {
                    log("Models already installed (offline storage).")
                    installed.value = true
                } else {
                    log("Tap Install to download models.")
                }
            }
        }
    }

    fun log(s: String) {
        logs.value = logs.value + s
    }

    fun install() {
        if (phase.value == Phase.Download) return
        viewModelScope.launch {
            phase.value = Phase.Download
            val ctx: Application = getApplication()
            val t = Models.TIERS[tier.value] ?: Models.TIERS["lite"]!!
            fun dl(name: String, url: String): Boolean {
                log("Downloading $name ...")
                val dest = File(modelsDir, name)
                val ok = Downloader.download(url, dest) { done, total ->
                    installProgress.value = name to if (total > 0) done.toFloat() / total else 0f
                }
                if (!ok) log("FAILED: $name") else log("Done: $name")
                return ok
            }
            withContext(Dispatchers.IO) {
                try {
                    if (!dl(t.llm.name, t.llm.url)) { phase.value = Phase.Error; return@withContext }
                    t.mm?.let { if (!dl(it.name, it.url)) { phase.value = Phase.Error; return@withContext } }
                    t.emb?.let { if (!dl(it.name, it.url)) { phase.value = Phase.Error; return@withContext } }

                    log("Downloading llama.cpp (arm64) ...")
                    val json = Downloader.getJson(Models.API)
                    val assets = JSONObject(json).getJSONArray("assets")
                    var assetUrl: String? = null
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.getString("name").endsWith(Models.ANDROID_ASSET_SUFFIX)) {
                            assetUrl = a.getString("browser_download_url")
                            break
                        }
                    }
                    if (assetUrl == null) {
                        log("ERROR: no android arm64 asset in latest llama.cpp release")
                        phase.value = Phase.Error
                        return@withContext
                    }
                    val tar = File(ctx.cacheDir, "llama.tar.gz")
                    installProgress.value = "llama.cpp" to 0f
                    if (!Downloader.download(assetUrl, tar) { done, total ->
                            installProgress.value = "llama.cpp" to if (total > 0) done.toFloat() / total else 0f
                        }) {
                        log("ERROR: llama.cpp download failed")
                        phase.value = Phase.Error
                        return@withContext
                    }
                    LlamaServer.extractTarGz(tar, llamaDir)
                    LlamaServer.chmod(File(llamaDir, "llama-server"))
                    log("llama.cpp ready.")
                    installed.value = true
                    log("Everything installed. Tap Start.")
                    phase.value = Phase.Detect
                } catch (e: Exception) {
                    log("ERROR: ${e.message}")
                    phase.value = Phase.Error
                } finally {
                    installProgress.value = null
                }
            }
        }
    }

    fun start() {
        viewModelScope.launch {
            phase.value = Phase.Start
            withContext(Dispatchers.IO) {
                log("Starting llama-server (127.0.0.1:8080) ...")
                if (!LlamaServer.start(modelsDir, llamaDir, if (tier.value == "lite") 2048 else 4096)) {
                    log("ERROR: could not start llama-server")
                    phase.value = Phase.Error
                    return@withContext
                }
                if (LlamaServer.healthy()) {
                    log("Server online. You can talk now.")
                    phase.value = Phase.Running
                } else {
                    log("ERROR: server did not become healthy")
                    phase.value = Phase.Error
                }
            }
        }
    }

    fun send(text: String, imageB64: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        messages.value = messages.value + ChatMsg("user", if (imageB64 != null) "$trimmed [image attached]" else trimmed)
        viewModelScope.launch {
            val content = JSONArray()
            content.put(JSONObject().put("type", "text").put("text", trimmed))
            if (imageB64 != null) {
                content.put(JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageB64")))
            }
            history.put(JSONObject().put("role", "user").put("content", content))
            val req = JSONObject()
                .put("messages", history)
                .put("temperature", 0.7)
                .put("max_tokens", 256)
            val reply = withContext(Dispatchers.IO) { LlamaServer.chat(req) }
            if (reply.isNullOrEmpty()) {
                messages.value = messages.value + ChatMsg("assistant", "(no response — is the server running?)")
            } else {
                history.put(JSONObject().put("role", "assistant").put("content", reply))
                messages.value = messages.value + ChatMsg("assistant", reply)
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                LlamaServer.stop()
                log("Server stopped.")
                phase.value = Phase.Detect
            }
        }
    }
}
