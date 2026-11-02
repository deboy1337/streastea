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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private suspend fun getDocument(url: String): Document = withContext(Dispatchers.IO) {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val request = Request.Builder().url(fullUrl)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", mainUrl)
            .build()
        val html = docClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}: $fullUrl")
            response.body.string()
        }
        val document = org.jsoup.Jsoup.parse(html, fullUrl)
        if (document.title().contains("Just a moment", ignoreCase = true)) {
            throw RuntimeException("Cloudflare-Challenge: $fullUrl")
        }
        document
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)
        val cached = cachedCatalog ?: KellerkinoCatalogStore.load()?.also { cachedCatalog = it }
        if (cached != null) {
            if (System.currentTimeMillis() - cached.updatedAt >= CATALOG_TTL_MS && refreshStarted.compareAndSet(false, true)) {
                Thread {
                    try {
                        runBlocking { refreshCatalog() }
                    } catch (e: Exception) {
                        Log.w(TAG, "Katalog-Aktualisierung fehlgeschlagen: ${e.message}")
                    } finally {
                        refreshStarted.set(false)
                    }
                }.start()
            }
            return catalogResponse(cached)
        }
        return catalogResponse(refreshCatalog())
    }

    private fun catalogResponse(catalog: KellerkinoCatalogStore.Catalog): HomePageResponse {
        val sections = catalog.rows.map { row ->
            HomePageList(row.title, row.movies.map { movie ->
                newMovieSearchResponse(movie.title, movie.url, TvType.Movie) {
                    posterUrl = movie.poster
                }
            })
        }
        return newHomePageResponse(sections, hasNext = false)
    }

    suspend fun updateCatalog(onProgress: (String) -> Unit = {}): Int {
        val catalog = refreshCatalog(onProgress)
        return catalog.rows.flatMap { it.movies }.distinctBy { it.url }.size
    }

    private suspend fun refreshCatalog(onProgress: (String) -> Unit = {}): KellerkinoCatalogStore.Catalog = catalogMutex.withLock {
        val sections = mutableListOf<HomePageList>()
        var complete = true
        listingCache.clear()

        try {
            onProgress("Startseite laden …")
            val home = getDocument(mainUrl)
            if (home.select("a.top-cover, article.movie-card").isEmpty()) {
                throw IllegalStateException("Keine Filme auf der Startseite gefunden")
            }

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
            complete = false
            Log.w(TAG, "Startseite fehlgeschlagen: ${e.message}")
        }

        listOf("IMDb Topliste" to "$mainUrl/imdb-rating/", "Alle Filme" to "$mainUrl/archiv/")
            .forEach { (title, url) ->
                try {
                    val items = preloadListing(url) { onProgress("$title: $it") }
                    if (items.isNotEmpty()) sections += HomePageList(title, items)
                } catch (e: Exception) {
                    complete = false
                    Log.w(TAG, "$title fehlgeschlagen: ${e.message}")
                }
            }

        for ((genre, slug) in CATEGORIES) {
            try {
                val items = preloadListing("$mainUrl/$slug/") { onProgress("$genre: $it") }
                if (items.isNotEmpty()) sections += HomePageList(genre, items)
            } catch (e: Exception) {
                complete = false
                Log.w(TAG, "Kategorie '$slug' fehlgeschlagen: ${e.message}")
            }
        }

        KellerkinoCoverStore.save()
        if (!complete || sections.isEmpty()) {
            throw IllegalStateException("Katalog nicht vollständig geladen. Vorhandener Katalog bleibt erhalten; bitte später erneut versuchen.")
        }
        val catalog = KellerkinoCatalogStore.Catalog(System.currentTimeMillis(), sections.map { section ->
            KellerkinoCatalogStore.Row(section.name, section.list.map { movie ->
                KellerkinoCatalogStore.Movie(movie.name, movie.url, movie.posterUrl)
            })
        })
        KellerkinoCatalogStore.save(catalog)
        cachedCatalog = catalog
        catalog
    }

    suspend fun syncCovers(): Int {
        val archive = "$mainUrl/archiv/"
        listingCache.remove(archive)
        preloadListing(archive)
        KellerkinoCoverStore.save()
        return KellerkinoCoverStore.count()
    }

    fun clearCovers() {
        listingCache.clear()
        KellerkinoCoverStore.clear()
    }

    private suspend fun preloadListing(baseUrl: String, onProgress: (String) -> Unit = {}): List<SearchResponse> {
        listingCache[baseUrl]?.let { (timestamp, cached) ->
            if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS) return cached
        }

        val first = getDocument(baseUrl)
        val result = mutableListOf<SearchResponse>()
        result += first.select("article.movie-card").mapNotNull { it.toMovieCard() }
        if (result.isEmpty()) throw IllegalStateException("Keine Filme gefunden: $baseUrl")

        val pageLinks = first.select(".pagination a.page-numbers[href*='seite/']")
        val firstHref = pageLinks.firstOrNull()?.attr("href")
        val query = if (firstHref != null && firstHref.contains("?")) "?" + firstHref.substringAfter("?") else ""
        val maxPage = pageLinks.mapNotNull { link ->
            Regex("""/seite/(\d+)""").find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 1
        onProgress("Seite 1 von $maxPage")
        var failed = false

        if (maxPage > 1) {
            for (batch in (2..maxPage).toList().chunked(PAGE_BATCH)) {
                val pages = batch.amap { page ->
                    try {
                        val items = getDocument("${baseUrl}seite/$page/$query")
                            .select("article.movie-card").mapNotNull { it.toMovieCard() }
                        if (items.isEmpty()) throw IllegalStateException("Leere Filmseite $page")
                        items
                    } catch (e: Exception) {
                        Log.w(TAG, "Seite $page von $baseUrl fehlgeschlagen: ${e.message}")
                        emptyList()
                    }
                }
                if (pages.any { it.isEmpty() }) failed = true
                pages.forEach { result += it }
                onProgress("Seite ${batch.last()} von $maxPage")
            }
        }

        val distinct = result.distinctBy { it.url }
        KellerkinoCoverStore.save()
        if (failed) throw IllegalStateException("Nicht alle Seiten geladen: $baseUrl")
        if (distinct.isNotEmpty()) listingCache[baseUrl] = System.currentTimeMillis() to distinct
        return distinct
    }

    private fun topCoverRow(doc: Document, cssClass: String, title: String): HomePageList? {
        val items = doc.select("div.$cssClass a.top-cover").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val name = a.attr("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val poster = KellerkinoCoverStore.resolve(href, a.selectFirst("img")?.attr("src")?.let { fixUrlNull(it) })
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
                results += newMovieSearchResponse(title, link, TvType.Movie) {
                    posterUrl = KellerkinoCoverStore.resolve(link, null)
                }
                links += link
            }
        } catch (e: Exception) {
            Log.w(TAG, "Suche fehlgeschlagen: ${e.message}")
        }
        if (links.isEmpty()) return results

        results.filter { it.posterUrl.isNullOrBlank() }.take(10).amap { result ->
            try {
                val poster = getDocument(result.url).selectFirst(".poster-box img")?.attr("src")?.let { fixUrlNull(it) }
                result.posterUrl = KellerkinoCoverStore.resolve(result.url, poster)
            } catch (_: Exception) { }
        }
        KellerkinoCoverStore.save()
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url)
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: throw RuntimeException("Kein Titel gefunden: $url")

        val poster = KellerkinoCoverStore.resolve(url, document.selectFirst(".poster-box img")?.attr("src")?.let { fixUrlNull(it) })
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

        KellerkinoCoverStore.save()
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
        val poster = KellerkinoCoverStore.resolve(href, selectFirst("picture source")?.attr("srcset")?.substringBefore(" ")?.let { fixUrlNull(it) })
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    private fun Element.toMovieCard(): SearchResponse? {
        val a = selectFirst("a.movie-thumb") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val img = a.selectFirst("img") ?: return null
        val title = img.attr("alt").trim()
            .ifBlank { selectFirst(".movie-card-body h2 a")?.text()?.trim().orEmpty() }
        if (title.isEmpty()) return null
        val poster = KellerkinoCoverStore.resolve(href, img.attr("data-dmt-theme-src").ifBlank { img.attr("src") }.let { fixUrlNull(it) })
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    companion object {
        private const val TAG = "Kellerkino"
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val PAGE_BATCH = 8
        private const val CACHE_TTL_MS = 2L * 60 * 60 * 1000
        private const val CATALOG_TTL_MS = 24L * 60 * 60 * 1000
        private val catalogMutex = Mutex()
        private val refreshStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile private var cachedCatalog: KellerkinoCatalogStore.Catalog? = null

        private val listingCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<SearchResponse>>>()

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
