package com.example.serienstream

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import org.json.JSONObject
import java.net.URI

internal object SerienstreamCoverStore {
    private val lock = Any()
    private var posters: MutableMap<String, String>? = null
    private var dirty = false

    private fun key(url: String): String = runCatching {
        URI(url).rawPath.orEmpty().trimEnd('/').let { if (it.startsWith('/')) it else "/$it" }
    }.getOrDefault(url)

    private fun entries(): MutableMap<String, String> {
        posters?.let { return it }
        val restored = mutableMapOf<String, String>()
        val json = runCatching {
            JSONObject(getKey<String>(SerienstreamProvider.SETTING_POSTER_MAP) ?: "{}")
        }.getOrNull()
        json?.keys()?.forEach { url ->
            json.optString(url).takeIf { it.isNotBlank() }?.let { restored[key(url)] = it }
        }
        posters = restored
        return restored
    }

    fun resolve(url: String, poster: String?): String? = synchronized(lock) {
        val entries = entries()
        val key = key(url)
        if (!poster.isNullOrBlank() && entries[key] != poster) {
            entries[key] = poster
            dirty = true
        }
        entries[key]
    }

    fun save() = synchronized(lock) {
        if (dirty) {
            setKey(SerienstreamProvider.SETTING_POSTER_MAP, JSONObject(entries().toMap()).toString())
            dirty = false
        }
    }

    fun mergeAndSave(found: Map<String, String>): Int = synchronized(lock) {
        found.forEach { (url, poster) -> resolve(url, poster) }
        save()
        entries().size
    }

    fun clear() = synchronized(lock) {
        setKey(SerienstreamProvider.SETTING_POSTER_MAP, "{}")
        posters = mutableMapOf()
        dirty = false
    }

    fun count(): Int = synchronized(lock) { entries().size }
}
