package com.arynox.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

class MemoryStore(private val file: File) {

    data class Entry(
        val name: String,
        val desc: String,
        val time: Long,
        val emb: FloatArray? = null,
    )

    private val entries = mutableListOf<Entry>()

    fun load() {
        entries.clear()
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = o.optJSONArray("emb")
                val emb = if (e != null) FloatArray(e.length()) { e.getDouble(it).toFloat() } else null
                entries.add(Entry(
                    o.getString("name"), o.getString("desc"), o.getLong("time"), emb))
            }
        } catch (_: Exception) {
        }
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            for (e in entries) {
                val o = JSONObject()
                    .put("name", e.name)
                    .put("desc", e.desc)
                    .put("time", e.time)
                if (e.emb != null) {
                    val ea = JSONArray()
                    e.emb.forEach { ea.put(it.toDouble()) }
                    o.put("emb", ea)
                }
                arr.put(o)
            }
            file.writeText(arr.toString(1))
        } catch (_: Exception) {
        }
    }

    fun add(name: String, desc: String, emb: FloatArray? = null) {
        val clean = name.trim()
        entries.removeAll { it.name.equals(clean, ignoreCase = true) }
        entries.add(0, Entry(clean, desc.trim(), System.currentTimeMillis(), emb))
        save()
    }

    fun remove(name: String): Boolean {
        val before = entries.size
        entries.removeAll { it.name.contains(name, ignoreCase = true) }
        if (entries.size != before) save()
        return entries.size != before
    }

    fun list(): List<Entry> = entries.toList()

    /** Exact/contains match first, then semantic (embedding cosine) recall. */
    fun recall(query: String, queryEmb: FloatArray?): Entry? {
        val q = query.lowercase()
        entries.firstOrNull { it.name.lowercase() == q }?.let { return it }
        entries.firstOrNull { it.name.lowercase().contains(q) }?.let { return it }
        if (queryEmb != null) {
            var best: Entry? = null
            var bestScore = 0.35f
            for (e in entries) {
                if (e.emb == null) continue
                val s = cosine(queryEmb, e.emb)
                if (s > bestScore) {
                    bestScore = s
                    best = e
                }
            }
            return best
        }
        return null
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val den = sqrt(na) * sqrt(nb)
        return if (den == 0f) 0f else dot / den
    }
}
