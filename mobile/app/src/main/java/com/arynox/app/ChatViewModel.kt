package com.arynox.app

import android.app.Application
import android.speech.tts.TextToSpeech
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
import java.util.Locale

data class ChatMsg(val role: String, val text: String)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val modelsDir = File(app.filesDir, "models")
    private val llamaDir = File(app.filesDir, "llama")
    private val memory = MemoryStore(File(app.filesDir, "memory.json"))
    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.getDefault()
    }

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
    val typing = MutableStateFlow(false)
    val ttsEnabled = MutableStateFlow(true)

    private val history = JSONArray()
    private var lastImageB64: String? = null
    private var pendingRememberName: String? = null

    init {
        memory.load()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val t = Detector.tier(ctx)
                tier.value = t
                log("Arynox - on-device AI")
                log("RAM: %.1f GB | Free: %.1f GB | %s".format(
                    Detector.ramGb(ctx), Detector.freeGb(ctx), Detector.abiSupported().let {
                        if (it) "arm64-v8a" else "UNSUPPORTED CPU"
                    }))
                log("Tier: ${Models.TIERS[t]!!.label}")
                if (!Detector.abiSupported()) {
                    phase.value = Phase.Error
                    log("ERROR: needs a 64-bit ARM (arm64-v8a) phone.")
                } else if (File(modelsDir, Models.TIERS[t]!!.llm.name).exists()) {
                    log("Models already installed. Tap Start.")
                    installed.value = true
                } else {
                    log("Tap Install - it downloads everything automatically.")
                }
            }
        }
    }

    fun log(s: String) {
        logs.value = logs.value + s
    }

    fun speak(text: String) {
        if (!ttsEnabled.value) return
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "arynox")
        } catch (_: Exception) {
        }
    }

    fun toggleTts() {
        ttsEnabled.value = !ttsEnabled.value
    }

    fun install() {
        if (phase.value == Phase.Download) return
        viewModelScope.launch {
            phase.value = Phase.Download
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
                    tar.delete()
                    log("llama.cpp ready.")
                    installed.value = true
                    log("Everything installed. Tap Start - try: what do you see?")
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
                log("Starting Arynox AI (127.0.0.1:8080) ...")
                val t = Models.TIERS[tier.value] ?: Models.TIERS["lite"]!!
                if (!LlamaServer.start(modelsDir, llamaDir, t.ctx)) {
                    val tail = LlamaServer.lastOutput.takeLast(5).joinToString(" | ")
                    log("ERROR: could not start llama-server. $tail")
                    phase.value = Phase.Error
                    return@withContext
                }
                if (LlamaServer.healthy()) {
                    log("AI online. Ask me anything - I can see, hear and remember.")
                    LlamaServer.startEmbed(modelsDir, llamaDir)
                    phase.value = Phase.Running
                } else {
                    val tail = LlamaServer.lastOutput.takeLast(5).joinToString(" | ")
                    log("ERROR: server did not become healthy. $tail")
                    phase.value = Phase.Error
                }
            }
        }
    }

    fun send(text: String, imageB64: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && imageB64 == null) return
        if (phase.value != Phase.Running) return

        val display = buildString {
            append(trimmed)
            if (imageB64 != null) append(" [photo]")
        }
        messages.value = messages.value + ChatMsg("user", display)
        if (imageB64 != null) lastImageB64 = imageB64

        viewModelScope.launch {
            typing.value = true
            val reply = withContext(Dispatchers.IO) { handle(trimmed, imageB64) }
            typing.value = false
            if (reply.isNullOrEmpty()) {
                messages.value = messages.value + ChatMsg("assistant", "(no response - server busy? try again)")
            } else {
                history.put(JSONObject().put("role", "user").put("content", if (imageB64 != null) "$trimmed [photo attached]" else trimmed))
                history.put(JSONObject().put("role", "assistant").put("content", reply))
                if (history.length() > 12) {
                    while (history.length() > 12) history.remove(0)
                }
                messages.value = messages.value + ChatMsg("assistant", reply)
                speak(reply)
            }
        }
    }

    /** Memory commands + fallback to the vision LLM. */
    private fun handle(text: String, imageB64: String?): String? {
        val q = text.lowercase().trim()

        // remember this as NAME  (with a photo: describe it first)
        val rememberAs = Regex("remember this as (.+)").find(q)?.groupValues?.get(1)
        if (rememberAs != null) return rememberWithPhoto(rememberAs, imageB64)

        val rememberAs2 = Regex("remember (?:him|her|them|that|this|person) as (.+)").find(q)?.groupValues?.get(1)
        if (rememberAs2 != null) return rememberWithPhoto(rememberAs2, imageB64)

        // remember NAME as DESCRIPTION  (no photo needed)
        val rememberDesc = Regex("remember ([\\w .-]+) as (.+)").find(q)
        if (rememberDesc != null) {
            val name = rememberDesc.groupValues[1].trim()
            val desc = rememberDesc.groupValues[2].trim()
            val emb = LlamaServer.embedText(desc)
            memory.add(name, desc, emb)
            return "Done. I'll remember $name - $desc"
        }

        // who is NAME / do you remember NAME / what about NAME
        val who = Regex("(?:who is|who's|do you remember|what about|tell me about|who do you know as) (.+)")
            .find(q)?.groupValues?.get(1)?.trim()
        if (who != null) {
            val name = who.removeSuffix("?").trim()
            val queryEmb = LlamaServer.embedText(name)
            val hit = memory.recall(name, queryEmb)
            if (hit != null) {
                return "${hit.name}: ${hit.desc}"
            }
            return "I don't remember anyone by that name yet. Try: remember this as Sam, with a photo."
        }

        // what do you remember / list memories
        if (q.contains("what do you remember") || q.contains("list memories") || q.contains("who do you know")) {
            val all = memory.list()
            return if (all.isEmpty()) {
                "My memory is empty. Take a photo and say: remember this as Sam."
            } else {
                "I remember: " + all.joinToString(", ") { it.name }
            }
        }

        // forget NAME
        val forget = Regex("forget (?:about )?(.+)").find(q)?.groupValues?.get(1)
        if (forget != null) {
            val name = forget.removeSuffix("?").trim()
            return if (memory.remove(name)) "Okay, forgotten $name." else "I don't have a memory called $name."
        }

        // pending: photo attached after "remember this as X" was interrupted
        if (imageB64 != null) {
            val name = pendingRememberName
            if (name != null) {
                pendingRememberName = null
                return rememberWithPhoto(name, imageB64)
            }
        }

        // plain vision/chat
        return LlamaServer.chat(buildRequest(text, imageB64))
    }

    private fun rememberWithPhoto(name: String, imageB64: String?): String {
        if (imageB64 == null) {
            pendingRememberName = name
            return "Got it - send me a photo of $name and I'll remember them."
        }
        val desc = LlamaServer.chat(buildRequest(
            "Describe this person in 1-2 sentences: appearance, clothes, distinguishing features.",
            imageB64)) ?: "(photo saved, no description)"
        val emb = LlamaServer.embedText(desc)
        memory.add(name, desc, emb)
        return "Done. I'll remember $name: $desc"
    }

    private fun buildRequest(text: String, imageB64: String?): JSONObject {
        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", text))
        if (imageB64 != null) {
            content.put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageB64")))
        }
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content",
            "You are Arynox, a warm, concise AI companion living on the user's phone. " +
                "Answer in 1-3 short sentences."))
        msgs.put(JSONObject().put("role", "user").put("content", content))
        return JSONObject()
            .put("messages", msgs)
            .put("temperature", 0.7)
            .put("max_tokens", 256)
    }

    fun stop() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                LlamaServer.stop()
                log("AI stopped. Tap Start to wake me up.")
                phase.value = Phase.Detect
            }
        }
    }
}
