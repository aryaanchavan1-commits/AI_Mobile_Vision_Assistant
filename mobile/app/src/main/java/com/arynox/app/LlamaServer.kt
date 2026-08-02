package com.arynox.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object LlamaServer {
    private const val SERVER = "llama-server"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()
    private var process: Process? = null
    private var embedProc: Process? = null
    val lastOutput = mutableListOf<String>()

    fun extractTarGz(archive: File, destDir: File) {
        destDir.mkdirs()
        GZIPInputStream(FileInputStream(archive)).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val out = File(destDir, entry.name.removePrefix("./"))
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tar.copyTo(it) }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        val found = destDir.walkTopDown().firstOrNull { it.isFile && it.name == SERVER }
        if (found != null && found.absolutePath != File(destDir, SERVER).absolutePath) {
            found.copyTo(File(destDir, SERVER), overwrite = true)
        }
        destDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".so") }
            .forEach { it.copyTo(File(destDir, it.name), overwrite = true) }
    }

    fun chmod(file: File): Boolean {
        return try {
            val p = ProcessBuilder("chmod", "755", file.absolutePath).start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun start(modelsDir: File, llamaDir: File, ctx: Int): Boolean {
        val exe = File(llamaDir, SERVER)
        if (!exe.exists() || !exe.canExecute()) return false
        val llm = modelsDir.listFiles()
            ?.firstOrNull { it.name.endsWith(".gguf") && it.name.startsWith("Qwen", ignoreCase = true) }
            ?: return false
        val mm = File(modelsDir, "mmproj-F16.gguf")
        val cmd = mutableListOf(exe.absolutePath, "-m", llm.absolutePath)
        if (mm.exists()) cmd += listOf("--mmproj", mm.absolutePath)
        cmd += listOf("-c", ctx.toString(), "-n", "512", "--jinja",
            "--host", "127.0.0.1", "--port", "8080")
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        process = try {
            pb.start().also { drainProc(it) }
        } catch (_: Exception) {
            return false
        }
        return true
    }

    fun startEmbed(modelsDir: File, llamaDir: File): Boolean {
        val exe = File(llamaDir, SERVER)
        val bge = File(modelsDir, "bge-small-en-v1.5-q4_k_m.gguf")
        if (!exe.exists() || !bge.exists() || embedProc != null) return false
        val cmd = listOf(exe.absolutePath, "-m", bge.absolutePath, "--embeddings",
            "--pooling", "mean", "-c", "512", "-n", "1",
            "--host", "127.0.0.1", "--port", "8081")
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        return try {
            pb.start().also { embedProc = it; drainProc(it) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun drainProc(p: Process) {
        Thread {
            val reader = p.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                synchronized(lastOutput) {
                    lastOutput.add(line)
                    if (lastOutput.size > 40) lastOutput.removeAt(0)
                }
            }
        }.isDaemon = true
    }

    fun embedText(text: String): FloatArray? {
        try {
            val body = JSONObject().put("content", text).toString()
            val req = Request.Builder()
                .url("http://127.0.0.1:8081/embeddings")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code != 200) return null
                val json = JSONObject(resp.body?.string() ?: return null)
                val arr = json.getJSONArray("data").getJSONObject(0).getJSONArray("embedding")
                return FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            }
        } catch (_: Exception) {
            return null
        }
    }

    fun healthy(timeoutMs: Long = 120_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val req = Request.Builder().url("http://127.0.0.1:8080/health").build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 200) {
                        val body = resp.body?.string() ?: ""
                        if (body.contains("\"ok\"")) return true
                    }
                }
            } catch (_: Exception) {
            }
            Thread.sleep(1000)
        }
        return false
    }

    fun chat(messages: JSONObject): String? {
        val body = messages.toString()
        val req = Request.Builder()
            .url("http://127.0.0.1:8080/v1/chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (resp.code != 200) return null
                val json = JSONObject(resp.body?.string() ?: return null)
                return json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
            }
        } catch (_: Exception) {
            return null
        }
    }

    fun stop() {
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        process = null
        try {
            embedProc?.destroy()
        } catch (_: Exception) {
        }
        embedProc = null
        synchronized(lastOutput) {
            lastOutput.clear()
        }
    }
}
