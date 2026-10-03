package com.example.kellerkino

import android.util.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

open class KellerkinoProvider : MainAPI() {
    override var mainUrl = "https://www.kellerkino.com"
    override var name = "Kellerkino"
    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = true
    override var lang = "de"

    private val docClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private suspend fun getDocument(url: String): Document {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val request = Request.Builder().url(fullUrl)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", mainUrl)
            .build()
        val response = docClient.newCall(request).execute()
        if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}: $fullUrl")
        val html = response.body?.string() ?: throw RuntimeException("Leere Antwort: $fullUrl")
        val document = org.jsoup.Jsoup.parse(html, fullUrl)
        if (document.title().contains("Just a moment", ignoreCase = true)) {
            throw RuntimeException("Cloudflare-Challenge: $fullUrl")
        }
        return document
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)
        val sections = mutableListOf<HomePageList>()

        try {
            val home = getDocument(mainUrl)

            val cinema = home.select("a.dmt-home-cinema-focus, a.dmt-home-cinema-secondary, a.dmt-home-cinema-more-card")
                .mapNotNull { it.toCinemaCard() }
            if (cinema.isNotEmpty()) sections += HomePageList("Aktuelle Kinofilme", cinema)

            topCoverRow(home, "dmt-home-top-legacy", "Neuerscheinungen")?.let { sections += it }
            topCoverRow(home, "dmt-home-streaming-legacy-row", "Zuletzt hinzugefügt")?.let { sections += it }

            home.select("div[data-dmt-home-theme-panel]").forEach { panel ->
                val key = panel.attr("data-dmt-home-theme-panel")
                val label = home.selectFirst("button[data-dmt-home-theme-tab='$key']")
                    ?.attr("data-dmt-home-theme-label")?.trim()?.takeIf { it.isNotEmpty() } ?: key
                val items = panel.select("article.movie-card").mapNotNull { it.toMovieCard() }
                if (items.isNotEmpty()) sections += HomePageList(label, items)
            }

            val empItems = home.select("div.movie-grid").lastOrNull()
                ?.select("article.movie-card")?.mapNotNull { it.toMovieCard() }.orEmpty()
            if (empItems.isNotEmpty()) sections += HomePageList("Empfehlungen", empItems)
        } catch (e: Exception) {
            Log.w(TAG, "Startseite fehlgeschlagen: ${e.message}")
        }

        listOf("IMDb Topliste" to "$mainUrl/imdb-rating/", "Alle Filme" to "$mainUrl/archiv/")
            .forEach { (title, url) ->
                try {
                    val doc = getDocument(url)
                    val items = doc.select("article.movie-card").mapNotNull { it.toMovieCard() }
                    if (items.isNotEmpty()) sections += HomePageList(title, items)
                } catch (e: Exception) {
                    Log.w(TAG, "$title fehlgeschlagen: ${e.message}")
                }
            }

        for ((genre, slug) in CATEGORIES) {
            try {
                val doc = getDocument("$mainUrl/$slug/")
                val items = doc.select("article.movie-card").mapNotNull { it.toMovieCard() }
                if (items.isNotEmpty()) sections += HomePageList(genre, items)
            } catch (e: Exception) {
                Log.w(TAG, "Kategorie '$slug' fehlgeschlagen: ${e.message}")
            }
        }

        return newHomePageResponse(sections, hasNext = false)
    }

    private fun topCoverRow(doc: Document, cssClass: String, title: String): HomePageList? {
        val items = doc.select("div.$cssClass a.top-cover").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val name = a.attr("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val poster = a.selectFirst("img")?.attr("src")?.let { fixUrlNull(it) }
            newMovieSearchResponse(name, href, TvType.Movie) { this.posterUrl = poster }
        }
        return if (items.isEmpty()) null else HomePageList(title, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=${URLEncoder.encode(query.trim(), "UTF-8")}&per_page=24&_fields=title,link"
        val results = mutableListOf<SearchResponse>()
        val links = mutableListOf<String>()
        try {
            val request = Request.Builder().url(apiUrl)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "application/json")
                .build()
            val response = docClient.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            if (!body.trimStart().startsWith("[")) return emptyList()
            val arr = org.json.JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val title = obj.optJSONObject("title")?.optString("rendered")
                    ?.let { Parser.unescapeEntities(it, false) }?.trim()
                    ?: continue
                val link = obj.optString("link").trim().ifBlank { continue }
                results += newMovieSearchResponse(title, link, TvType.Movie)
                links += link
            }
        } catch (e: Exception) {
            Log.w(TAG, "Suche fehlgeschlagen: ${e.message}")
        }
        if (links.isEmpty()) return results

        val posters = links.take(10).amap { link ->
            try { getDocument(link).selectFirst(".poster-box img")?.attr("src") } catch (_: Exception) { null }
        }
        posters.forEachIndexed { idx, poster ->
            if (idx < results.size && !poster.isNullOrBlank()) results[idx].posterUrl = fixUrlNull(poster)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url)
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: throw RuntimeException("Kein Titel gefunden: $url")

        val poster = document.selectFirst(".poster-box img")?.attr("src")?.let { fixUrlNull(it) }
        val plot = document.selectFirst(".movie-description p")?.text()?.trim() ?: ""
        val year = document.selectFirst(".info-row-jahr .info-value")?.text()?.trim()?.toIntOrNull()
        val imdb = document.selectFirst(".info-row-imdb-bewertung .info-value")?.text()?.trim()
            ?.replace(",", ".")?.toDoubleOrNull()
        val tags = document.select(".info-row-genre .nfo-movie-info-link")
            .map { it.text().trim() }.filter { it.isNotEmpty() }
        val actors = document.selectFirst(".info-row-schauspieler .info-value")?.text()
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val trailer = document.selectFirst("a.nfo-movie-trailer-link")?.attr("href")
        val recommendations = document.select("[id^=nfo-similar-recommendations] article.movie-card")
            .mapNotNull { it.toMovieCard() }

        return newMovieLoadResponse(title, url, TvType.Movie, dataUrl = url) {
            this.name = title
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = Score.from10(imdb)
            this.tags = tags.takeIf { it.isNotEmpty() }
            addActors(actors)
            addTrailer(trailer)
            this.recommendations = recommendations.takeIf { it.isNotEmpty() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDocument(data)
        val tabLabels = document.select("button[data-nfo-player-tab]")
            .associate { it.attr("data-nfo-player-tab") to it.text().trim() }
        val panels = document.select("div[data-nfo-player-panel]")
        if (panels.isEmpty()) {
            Log.w(TAG, "Keine Player-Panels auf $data")
            return false
        }
        var emitted = false
        for (panel in panels) {
            val key = panel.attr("data-nfo-player-panel")
            val label = tabLabels[key]?.takeIf { it.isNotEmpty() } ?: key
            val src = panel.selectFirst("iframe")?.attr("src")
                ?: Regex("""<iframe[^>]+src="([^"]+)""").find(panel.html())?.groupValues?.get(1)
                ?: continue
            val finalSrc = fixUrlNull(src) ?: continue
            emitted = true
            loadExtractor(finalSrc, data, subtitleCallback) { link ->
                val named = runBlocking {
                    newExtractorLink(
                        source = label,
                        name = label,
                        url = link.url
                    ) {
                        referer = link.referer
                        quality = link.quality
                        type = link.type
                        headers = link.headers
                        extractorData = link.extractorData
                    }
                }
                callback.invoke(named)
            }
        }
        return emitted
    }

    private fun Element.toCinemaCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("strong")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val poster = selectFirst("picture source")?.attr("srcset")?.substringBefore(" ")?.let { fixUrlNull(it) }
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    private fun Element.toMovieCard(): SearchResponse? {
        val a = selectFirst("a.movie-thumb") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val img = a.selectFirst("img") ?: return null
        val title = img.attr("alt").trim()
            .ifBlank { selectFirst(".movie-card-body h2 a")?.text()?.trim().orEmpty() }
        if (title.isEmpty()) return null
        val poster = img.attr("data-dmt-theme-src").ifBlank { img.attr("src") }.let { fixUrlNull(it) }
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    companion object {
        private const val TAG = "Kellerkino"
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val CATEGORIES = listOf(
            "Abenteuer" to "abenteuer",
            "Action" to "action",
            "Animation" to "animation",
            "Dokumentarfilm" to "dokumentarfilm",
            "Drama" to "drama",
            "Familie" to "familie",
            "Fantasy" to "fantasy",
            "Historie" to "historie",
            "Horror" to "horror",
            "Komödie" to "komoedie",
            "Kriegsfilm" to "kriegsfilm",
            "Krimi" to "krimi",
            "Liebesfilm" to "liebesfilm",
            "Musik" to "musik",
            "Mystery" to "mystery",
            "Science-Fiction" to "science-fiction",
            "Thriller" to "thriller",
            "TV-Film" to "tv-film",
            "Western" to "western"
        )
    }
}