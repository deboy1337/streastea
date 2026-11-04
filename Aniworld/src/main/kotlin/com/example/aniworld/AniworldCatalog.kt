package com.example.aniworld

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

internal object AniworldCatalog {
    data class Anime(val title: String, val url: String, val poster: String?)
    data class Row(val title: String, val anime: List<Anime>)

    private val seriesPath = Regex("/anime/stream/[^/]+/?")
    private val homeTitles = listOf(
        "Beliebt bei AniWorld", "Neue Animes", "Derzeit beliebt", "Das sehen andere AniWorld Nutzer"
    )

    private fun absolute(raw: String, baseUrl: String): String? = runCatching {
        if (raw.isBlank()) return null
        val uri = URI(baseUrl).resolve(raw.trim())
        uri.toString().takeIf { uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() }
    }.getOrNull()

    fun poster(element: Element, baseUrl: String): String? {
        val image = if (element.tagName() == "img") element else element.selectFirst("img") ?: return null
        return listOf("data-src", "src").firstNotNullOfOrNull { attribute ->
            absolute(image.attr(attribute), baseUrl)
        }
    }

    private fun anime(link: Element, baseUrl: String, textList: Boolean): Anime? {
        val url = absolute(link.attr("href"), baseUrl) ?: return null
        val uri = URI(url)
        if (uri.host != URI(baseUrl).host || !seriesPath.matches(uri.path)) return null
        val title = if (textList) link.text().trim() else {
            link.selectFirst("h3, h6, strong")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: link.attr("title").substringBefore(" als ").substringBefore(" alle ")
                    .substringBefore(" jetzt ").trim()
        }
        if (title.isBlank()) return null
        return Anime(title, url, poster(link, baseUrl))
    }

    fun genres(document: Document, baseUrl: String): List<Row> =
        document.select(".seriesList .genre").mapNotNull { genre ->
            val title = genre.selectFirst(".seriesGenreList h3")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val list = genre.children().firstOrNull { it.tagName() == "ul" } ?: return@mapNotNull null
            val items = list.select("li a[href]").mapNotNull { anime(it, baseUrl, true) }.distinctBy { it.url }
            if (items.isEmpty()) null else Row(title, items)
        }

    fun home(document: Document, baseUrl: String): List<Row> = homeTitles.mapNotNull { title ->
        val heading = document.select("h2").firstOrNull { it.text().trim() == title } ?: return@mapNotNull null
        val container = if (title == "Das sehen andere AniWorld Nutzer") {
            heading.parent()?.nextElementSibling()?.takeIf { it.hasClass("homeContentPromotionBox") }
        } else {
            heading.closest(".carousel")?.selectFirst(".previews")
        } ?: return@mapNotNull null
        val items = container.select("a[href]").mapNotNull { anime(it, baseUrl, false) }.distinctBy { it.url }
        if (items.isEmpty()) null else Row(title, items)
    }
}
