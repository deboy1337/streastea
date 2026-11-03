package com.example.serienstream

import org.jsoup.nodes.Element
import java.net.URI

internal object SerienstreamPosters {
    fun isUsable(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    fun from(element: Element, baseUrl: String): String? {
        val img = if (element.tagName() == "img") element else element.selectFirst("img") ?: return null
        val candidates = listOf("data-src", "src", "data-srcset", "srcset")
            .map { img.attr(it) } + img.parent()?.select("source")?.flatMap {
                listOf(it.attr("data-srcset"), it.attr("srcset"))
            }.orEmpty()
        return candidates.firstNotNullOfOrNull { candidate ->
            val raw = candidate.trim().substringBefore(',').trim().split(Regex("\\s+"), limit = 2).first()
            if (raw.isBlank() || raw.startsWith("data:") || raw.startsWith("blob:")) return@firstNotNullOfOrNull null
            runCatching { URI(baseUrl).resolve(raw).toString() }.getOrNull()?.takeIf { isUsable(it) }
        }
    }
}
