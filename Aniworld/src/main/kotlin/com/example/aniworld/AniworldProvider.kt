package com.example.aniworld

import android.util.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

open class AniworldProvider : MainAPI() {
    override var mainUrl = "https://aniworld.to"
    override var name = "AniWorld"
    override val supportedTypes = setOf(TvType.Anime)
    override val hasMainPage = true
    override var lang = "de"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun document(url: String): org.jsoup.nodes.Document {
        val full = if (url.startsWith("http")) url else "$mainUrl$url"
        val response = client.newCall(
            Request.Builder().url(full).header("User-Agent", UA).header("Referer", mainUrl).build()
        ).execute()
        val html = response.body.string()
        if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}: $full")
        val doc = Jsoup.parse(html, full)
        if (doc.title().contains("Just a moment", true)) throw RuntimeException("Cloudflare-Challenge")
        return doc
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), false)
        val sections = mutableListOf<HomePageList>()
        try {
            val home = document(mainUrl)
            listOf(
                "Beliebt bei AniWorld",
                "Neue Animes",
                "Derzeit beliebt",
                "Das sehen andere AniWorld Nutzer"
            ).forEach { title ->
                val heading = home.select("h1, h2").firstOrNull { it.text().contains(title) } ?: return@forEach
                val box = heading.parent()?.parent()?.nextElementSibling() ?: heading.parent()?.nextElementSibling()
                val items = box?.select("a[href*='/anime/stream/']")?.mapNotNull { it.toSeriesCard() }
                    ?.distinctBy { it.url }.orEmpty()
                if (items.isNotEmpty()) sections += HomePageList(title, items)
            }
            val latest = home.select("a[href*='/staffel-'][href*='/episode-']").mapNotNull { link ->
                val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                val series = seriesUrl(href)
                val name = link.selectFirst("strong")?.text()?.trim().orEmpty().ifBlank { return@mapNotNull null }
                val tag = link.selectFirst(".listTag")?.text()?.trim().orEmpty()
                newTvSeriesSearchResponse("$name $tag".trim(), series, TvType.Anime) {
                    posterUrl = AniworldCoverStore.resolve(series, null)
                }
            }.distinctBy { it.name }.take(50)
            if (latest.isNotEmpty()) sections.add(0, HomePageList("Die 50 neuesten Episoden", latest))
        } catch (e: Exception) {
            Log.w(TAG, "Startseite: ${e.message}")
        }

        try {
            val genres = document("/animes-genres")
            genres.select("h3").forEach { heading ->
                val name = heading.text().trim()
                if (name.isBlank() || name.contains("Hilfe")) return@forEach
                val list = heading.nextElementSibling()?.takeIf { it.tagName() == "ul" } ?: return@forEach
                val items = list.select("a[href*='/anime/stream/']").mapNotNull { link ->
                    val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                    val title = link.text().trim().ifBlank { return@mapNotNull null }
                    newTvSeriesSearchResponse(title, href, TvType.Anime) {
                        posterUrl = AniworldCoverStore.resolve(href, null)
                    }
                }
                if (items.isNotEmpty()) sections += HomePageList(name, items)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Genres: ${e.message}")
        }
        AniworldCoverStore.save()
        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val body = FormBody.Builder().add("keyword", query.trim()).build()
        val response = client.newCall(
            Request.Builder().url("$mainUrl/ajax/search").post(body)
                .header("User-Agent", UA).header("X-Requested-With", "XMLHttpRequest").build()
        ).execute()
        val json = response.body.string()
        if (!json.trimStart().startsWith("[")) return emptyList()
        val arr = org.json.JSONArray(json)
        return (0 until arr.length()).mapNotNull { index ->
            val item = arr.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title").replace(Regex("<[^>]+>"), "").trim()
            val link = fixUrlNull(item.optString("link")) ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, link, TvType.Anime) {
                posterUrl = AniworldCoverStore.resolve(link, null)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val series = seriesUrl(url)
        val page = document(series)
        val title = page.selectFirst("h1 span")?.text()?.trim()
            ?: page.selectFirst("h1")?.text()?.trim()
            ?: throw RuntimeException("Kein Titel")
        val poster = AniworldCoverStore.resolve(series, page.selectFirst("img[data-src*='/public/img/cover/'], img[src*='/public/img/cover/']")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }?.let { fixUrlNull(it) })
        val plot = page.selectFirst(".seri_des")?.attr("data-full-description")
            ?.ifBlank { page.selectFirst(".seri_des")?.text() }.orEmpty()
        val year = page.selectFirst("[itemprop=startDate]")?.text()?.trim()?.toIntOrNull()
        val tags = page.select("a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotEmpty() }
        val seasons = page.select("a[href*='/staffel-']").mapNotNull { link ->
            val href = link.attr("href")
            if (href.contains("/episode-")) return@mapNotNull null
            val number = Regex("""/staffel-(\d+)$""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            number to fixUrlNull(href)
        }.distinctBy { it.first }.sortedBy { it.first }
        val episodes = seasons.amap { (season, seasonUrl) ->
            if (seasonUrl == null) return@amap emptyList()
            document(seasonUrl).select("a[href*='/episode-']").mapNotNull { link ->
                val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                val episode = Regex("""/episode-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(href) {
                    this.season = season
                    this.episode = episode
                    this.name = link.attr("title").ifBlank { "Episode ${episode ?: ""}" }
                }
            }.distinctBy { it.data }
        }.flatten()
        AniworldCoverStore.save()
        return newTvSeriesLoadResponse(title, series, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = document(data)
        val links = page.select("li[data-link-target]").sortedBy { item ->
            when (item.attr("data-lang-key")) {
                "1" -> 0
                "3" -> 1
                else -> 2
            }
        }
        var found = false
        for (item in links) {
            val target = fixUrlNull(item.attr("data-link-target")) ?: continue
            val hoster = item.selectFirst("h4")?.text()?.trim().orEmpty().ifBlank { "Hoster" }
            val language = when (item.attr("data-lang-key")) {
                "1" -> "Deutsch"
                "3" -> "Ger-Sub"
                "2" -> "Eng-Sub"
                else -> ""
            }
            val finalUrl = runCatching {
                client.newCall(Request.Builder().url(target).header("User-Agent", UA).header("Referer", mainUrl).build())
                    .execute().use { it.request.url.toString() }
            }.getOrDefault(target)
            found = true
            loadExtractor(finalUrl, data, subtitleCallback) { link ->
                callback.invoke(runBlocking {
                    newExtractorLink(hoster, "$hoster - $language".trim(), link.url) {
                        referer = link.referer
                        quality = link.quality
                        type = link.type
                        headers = link.headers
                        extractorData = link.extractorData
                    }
                })
            }
        }
        return found
    }

    private fun Element.toSeriesCard(): SearchResponse? {
        val href = attr("href")
        if (!href.contains("/anime/stream/") || href.contains("/staffel-") || href.contains("/episode-")) return null
        val url = fixUrlNull(href) ?: return null
        val title = attr("title").substringBefore(" als ").substringBefore(" alle ").substringBefore(" jetzt ")
            .ifBlank { selectFirst("h3, h6, strong")?.text().orEmpty() }.trim()
        if (title.isBlank()) return null
        val image = selectFirst("img")
        val poster = image?.attr("data-src")?.ifBlank { image.attr("src") }?.let { fixUrlNull(it) }
        return newTvSeriesSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = AniworldCoverStore.resolve(url, poster?.takeUnless { it.startsWith("data:") })
        }
    }

    private fun seriesUrl(url: String): String {
        val path = URI(url).path.substringAfter("/anime/stream/").substringBefore("/")
        return "$mainUrl/anime/stream/$path"
    }

    companion object {
        const val SETTING_EMAIL = "aniworld_email"
        const val SETTING_PASSWORD = "aniworld_password"
        private const val TAG = "AniWorld"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val running = AtomicBoolean(false)
        @Volatile private var status = ""

        fun isCoverSyncRunning() = running.get()
        fun getCoverSyncStatus() = status

        fun clearCovers() {
            check(!running.get()) { "Cover-Sync läuft noch" }
            AniworldCoverStore.clear()
        }

        fun syncCovers(onProgress: (String) -> Unit = {}): Int {
            check(running.compareAndSet(false, true)) { "Cover-Sync läuft bereits" }
            fun progress(message: String) {
                status = message
                onProgress(message)
            }
            try {
                val provider = AniworldProvider()
                progress("Genres laden …")
                val genres = provider.document("/animes-genres")
                val series = genres.select("h3").flatMap { heading ->
                    if (heading.text().contains("Hilfe")) emptyList()
                    else heading.nextElementSibling()?.takeIf { it.tagName() == "ul" }
                        ?.select("a[href*='/anime/stream/']").orEmpty()
                }.mapNotNull { provider.run { fixUrlNull(it.attr("href")) } }.distinct()
                check(series.isNotEmpty()) { "Keine Animes gefunden" }
                val failed = mutableListOf<String>()
                series.chunked(6).forEachIndexed { index, batch ->
                    progress("Cover ${index * 6}/${series.size} – ${AniworldCoverStore.count()} gespeichert")
                    val found = batch.mapNotNull { url ->
                        runCatching {
                            val page = provider.document(url)
                            val image = page.selectFirst("img[data-src*='/public/img/cover/'], img[src*='/public/img/cover/']")
                            val poster = image?.attr("data-src")?.ifBlank { image.attr("src") }?.let { provider.run { fixUrlNull(it) } }
                            if (poster.isNullOrBlank()) null else url to poster
                        }.onFailure { failed += "${URI(url).path.substringAfterLast('/')}: ${it.message}" }.getOrNull()
                    }.toMap()
                    if (found.isNotEmpty()) AniworldCoverStore.mergeAndSave(found)
                }
                val count = AniworldCoverStore.count()
                check(failed.size < series.size) { "Cover-Sync fehlgeschlagen. $count Covers bleiben gespeichert." }
                status = "$count Covers gespeichert" + if (failed.isEmpty()) "" else ", ${failed.size} fehlgeschlagen"
                return count
            } finally {
                running.set(false)
            }
        }
    }
}
