package com.example.kellerkino

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import org.json.JSONObject

internal object KellerkinoCoverStore {
    private const val KEY = "kellerkino_poster_map"
    private val lock = Any()
    private var posters: MutableMap<String, String>? = null
    private var dirty = false

    private fun entries(): MutableMap<String, String> {
        posters?.let { return it }
        val restored = mutableMapOf<String, String>()
        val json = runCatching { JSONObject(getKey<String>(KEY) ?: "{}") }.getOrNull()
        json?.keys()?.forEach { key ->
            json.optString(key).takeIf { it.isNotBlank() }?.let { restored[key] = it }
        }
        posters = restored
        return restored
    }

    fun resolve(url: String, poster: String?): String? = synchronized(lock) {
        val entries = entries()
        if (!poster.isNullOrBlank() && entries[url] != poster) {
            entries[url] = poster
            dirty = true
        }
        entries[url]
    }

    fun save() = synchronized(lock) {
        if (dirty) {
            setKey(KEY, JSONObject(entries().toMap()).toString())
            dirty = false
        }
    }

    fun clear() = synchronized(lock) {
        setKey(KEY, "{}")
        posters = mutableMapOf()
        dirty = false
    }

    fun count(): Int = synchronized(lock) { entries().size }
}
