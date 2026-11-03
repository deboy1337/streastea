package com.example.serienstream

import org.jsoup.nodes.Element
import java.net.URI
import java.text.Normalizer
import java.util.Locale

internal object SerienstreamGenreLinks {
    private fun normalized(value: String): String = Normalizer.normalize(
        value.removePrefix("filter.genre_").lowercase(Locale.ROOT).replace("ß", "ss"),
        Normalizer.Form.NFD
    ).replace(Regex("[^a-z0-9]"), "")

    fun resolve(key: String, displayName: String, element: Element, baseUrl: String): String? {
        val base = URI(baseUrl)
        val names = setOf(normalized(key), normalized(displayName))
        return element.select("a[href*='/genre/']").mapNotNull { link ->
            val target = runCatching { base.resolve(link.attr("href")) }.getOrNull() ?: return@mapNotNull null
            if (target.host != base.host || target.scheme !in setOf("https", "http")) return@mapNotNull null
            val slug = target.path?.removePrefix("/genre/")?.trimEnd('/') ?: return@mapNotNull null
            if (!target.path.startsWith("/genre/") || '/' in slug || slug.isBlank()) return@mapNotNull null
            if (normalized(slug) !in names && normalized(link.text()) !in names) return@mapNotNull null
            target.toString()
        }.distinct().singleOrNull()
    }
}
