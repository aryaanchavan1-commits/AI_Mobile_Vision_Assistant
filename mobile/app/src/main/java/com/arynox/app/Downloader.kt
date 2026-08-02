package com.arynox.app

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object Downloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): Boolean {
        dest.parentFile?.mkdirs()
        val existing = if (dest.exists()) dest.length() else 0L
        val builder = Request.Builder().url(url)
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        val resp = client.newCall(builder.build()).execute()
        resp.use {
            if (it.code == 416) return true
            if (it.code != 200 && it.code != 206) return false
            val body = it.body ?: return false
            val append = it.code == 206
            var written = if (append) existing else 0L
            val total = written + (body.contentLength().coerceAtLeast(0))
            body.byteStream().use { input ->
                FileOutputStream(dest, append).use { out ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        onProgress(written, total)
                    }
                }
            }
            return true
        }
    }

    fun getJson(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "arynox-android").build()
        client.newCall(req).execute().use { return it.body?.string() ?: "" }
    }
}
