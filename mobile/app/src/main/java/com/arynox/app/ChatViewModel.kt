package com.arynox.app

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ChatMsg(val role: String, val text: String)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val modelsDir = File(app.filesDir, "models")
    private val llamaDir = File(app.filesDir, "llama")
    private val memory = MemoryStore(File(app.filesDir, "memory.json"))
    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.getDefault()
    }

    companion object {
        const val EXA_API_KEY = "6bcaffb7-40d4-4bed-a394-a9ca245786ec"
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
    val listenEnabled = MutableStateFlow(true)
    val visionEnabled = MutableStateFlow(true)
    val speaking = MutableStateFlow(false)
    val liveCaption = MutableStateFlow("")
    val webNote = MutableStateFlow<String?>(null)

    private val history = JSONArray()
    private var lastImageB64: String? = null
    private var pendingRememberName: String? = null
    private val visionBusy = MutableStateFlow(false)

    private val netClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

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
                } else {
                    if (File(modelsDir, Models.TIERS[t]!!.llm.name).exists()) {
                        installed.value = true
                        log("Models already installed. Auto-starting...")
                    } else {
                        log("Auto-installing models (first run) ...")
                        doInstall()
                    }
                    doStart()
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
            speaking.value = true
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "arynox")
            viewModelScope.launch {
                delay((text.length * 85L).coerceAtLeast(800))
                speaking.value = false
            }
        } catch (_: Exception) {
            speaking.value = false
        }
    }

    fun toggleTts() {
        ttsEnabled.value = !ttsEnabled.value
    }

    fun toggleListen() {
        listenEnabled.value = !listenEnabled.value
    }

    fun toggleVision() {
        visionEnabled.value = !visionEnabled.value
    }

    fun install() {
        if (phase.value == Phase.Download) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                phase.value = Phase.Download
                doInstall()
            }
        }
    }

    private fun doInstall() {
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
        try {
            if (!dl(t.llm.name, t.llm.url)) { phase.value = Phase.Error; return }
            t.mm?.let { if (!dl(it.name, it.url)) { phase.value = Phase.Error; return } }
            t.emb?.let { if (!dl(it.name, it.url)) { phase.value = Phase.Error; return } }

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
                return
            }
            val tar = File(ctx.cacheDir, "llama.tar.gz")
            installProgress.value = "llama.cpp" to 0f
            if (!Downloader.download(assetUrl, tar) { done, total ->
                    installProgress.value = "llama.cpp" to if (total > 0) done.toFloat() / total else 0f
                }) {
                log("ERROR: llama.cpp download failed")
                phase.value = Phase.Error
                return
            }
            LlamaServer.extractTarGz(tar, llamaDir)
            LlamaServer.chmod(File(llamaDir, "llama-server"))
            tar.delete()
            log("llama.cpp ready.")
            installed.value = true
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            phase.value = Phase.Error
        } finally {
            installProgress.value = null
        }
    }

    private fun doStart() {
        log("Starting Arynox AI (127.0.0.1:8080) ...")
        val t = Models.TIERS[tier.value] ?: Models.TIERS["lite"]!!
        if (!LlamaServer.start(modelsDir, llamaDir, t.ctx)) {
            val tail = LlamaServer.lastOutput.takeLast(5).joinToString(" | ")
            log("ERROR: could not start llama-server. $tail")
            phase.value = Phase.Error
            return
        }
        if (LlamaServer.healthy()) {
            log("AI online. I'm watching, listening and remembering.")
            LlamaServer.startEmbed(modelsDir, llamaDir)
            phase.value = Phase.Running
        } else {
            val tail = LlamaServer.lastOutput.takeLast(5).joinToString(" | ")
            log("ERROR: server did not become healthy. $tail")
            phase.value = Phase.Error
        }
    }

    fun start() {
        viewModelScope.launch {
            phase.value = Phase.Start
            withContext(Dispatchers.IO) { doStart() }
        }
    }

    /** Called ~every 8s with a fresh camera frame while vision mode is on. */
    fun onVisionFrame(b64: String) {
        if (!visionEnabled.value || phase.value != Phase.Running || typing.value || visionBusy.value) return
        visionBusy.value = true
        viewModelScope.launch {
            val cap = withContext(Dispatchers.IO) {
                LlamaServer.chat(buildRequest(
                    "Describe what you see right now in one short sentence.",
                    b64, maxTokens = 40))
            }
            if (cap != null && !cap.isBlank() && visionEnabled.value) {
                liveCaption.value = cap.trim().trim('"')
            }
            visionBusy.value = false
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

    /** Memory commands + vision LLM + web search fallback. */
    private fun handle(text: String, imageB64: String?): String? {
        val q = text.lowercase().trim()

        val rememberAs = Regex("remember this as (.+)").find(q)?.groupValues?.get(1)
        if (rememberAs != null) return rememberWithPhoto(rememberAs, imageB64)

        val rememberAs2 = Regex("remember (?:him|her|them|that|this|person) as (.+)").find(q)?.groupValues?.get(1)
        if (rememberAs2 != null) return rememberWithPhoto(rememberAs2, imageB64)

        val rememberDesc = Regex("remember ([\\w .-]+) as (.+)").find(q)
        if (rememberDesc != null) {
            val name = rememberDesc.groupValues[1].trim()
            val desc = rememberDesc.groupValues[2].trim()
            val emb = LlamaServer.embedText(desc)
            memory.add(name, desc, emb)
            return "Done. I'll remember $name - $desc"
        }

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

        if (q.contains("what do you remember") || q.contains("list memories") || q.contains("who do you know")) {
            val all = memory.list()
            return if (all.isEmpty()) {
                "My memory is empty. Take a photo and say: remember this as Sam."
            } else {
                "I remember: " + all.joinToString(", ") { it.name }
            }
        }

        val forget = Regex("forget (?:about )?(.+)").find(q)?.groupValues?.get(1)
        if (forget != null) {
            val name = forget.removeSuffix("?").trim()
            return if (memory.remove(name)) "Okay, forgotten $name." else "I don't have a memory called $name."
        }

        if (imageB64 != null) {
            val name = pendingRememberName
            if (name != null) {
                pendingRememberName = null
                return rememberWithPhoto(name, imageB64)
            }
        }

        val raw = LlamaServer.chat(buildRequest(text, imageB64)) ?: return null
        return resolveSearch(raw, text, imageB64)
    }

    private fun resolveSearch(raw: String, text: String, imageB64: String?): String {
        val m = Regex("\\[SEARCH:\\s*([^\\]]+)\\]").find(raw) ?: return raw
        val query = m.groupValues[1].trim()
        webNote.value = "Searching the web: $query"
        val results = exaSearch(query)
        val final = LlamaServer.chat(buildRequest(
            "Answer the user's question using this web research. Summarize the facts and add the source links at the end.\n" +
                "User question: $text\n" +
                "Web research for \"$query\":\n$results",
            imageB64, maxTokens = 512)) ?: raw
        webNote.value = null
        return final
    }

    /** Exa web search. Returns plain-text snippets. */
    private fun exaSearch(query: String): String {
        return try {
            val body = JSONObject()
                .put("query", query)
                .put("numResults", 5)
                .put("contents", JSONObject().put("text", JSONObject().put("maxCharacters", 500)))
                .toString()
            val req = Request.Builder()
                .url("https://api.exa.ai/search")
                .addHeader("x-api-key", EXA_API_KEY)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            netClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "(web search failed: HTTP ${resp.code})"
                val json = resp.body?.string() ?: return "(web search failed: no body)"
                val arr = JSONObject(json).optJSONArray("results") ?: JSONArray()
                if (arr.length() == 0) return "(no results found)"
                val sb = StringBuilder()
                for (i in 0 until minOf(arr.length(), 5)) {
                    val r = arr.getJSONObject(i)
                    val text = r.optJSONObject("contents")
                        ?.optJSONObject("text")
                        ?.optString("text")?.take(450) ?: ""
                    sb.append("- ").append(r.optString("title")).append("\n")
                        .append(r.optString("url")).append("\n")
                        .append(text).append("\n\n")
                }
                sb.toString().ifEmpty { "(no readable results)" }
            }
        } catch (e: Exception) {
            "(web search error: ${e.message})"
        }
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

    private fun buildRequest(text: String, imageB64: String?, maxTokens: Int = 256): JSONObject {
        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", text))
        if (imageB64 != null) {
            content.put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageB64")))
        }
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content",
            "You are Arynox, a warm, concise AI companion living on the user's phone. " +
                "You see through the camera, hear through the microphone, and remember people. " +
                "Answer in 1-3 short sentences. If you do not know the answer or need fresh data, " +
                "reply with exactly one line: [SEARCH: your search query]"))
        msgs.put(JSONObject().put("role", "user").put("content", content))
        return JSONObject()
            .put("messages", msgs)
            .put("temperature", 0.7)
            .put("max_tokens", maxTokens)
    }

    fun stop() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                LlamaServer.stop()
                log("AI stopped.")
                phase.value = Phase.Detect
            }
        }
    }
}
