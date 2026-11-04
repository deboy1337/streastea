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
        val html = client.newCall(
            Request.Builder().url(full).header("User-Agent", UA).header("Referer", mainUrl).build()
        ).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}: $full")
            response.body.string()
        }
        val doc = Jsoup.parse(html, full)
        if (doc.title().contains("Just a moment", true)) throw RuntimeException("Cloudflare-Challenge")
        return doc
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), false)
        val sections = mutableListOf<HomePageList>()
        try {
            val home = document(mainUrl)
            AniworldCatalog.home(home, mainUrl).forEach { row ->
                val items = row.anime.map { anime ->
                    newTvSeriesSearchResponse(anime.title, anime.url, TvType.Anime) {
                        posterUrl = AniworldCoverStore.resolve(anime.url, anime.poster)
                    }
                }
                sections += HomePageList(row.title, items)
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
            if (latest.isNotEmpty()) sections.add(
                if (sections.firstOrNull()?.name == "Beliebt bei AniWorld") 1 else 0,
                HomePageList("Die 50 neuesten Episoden", latest)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Startseite: ${e.message}")
        }

        try {
            val genres = document("/animes-genres")
            val rows = AniworldCatalog.genres(genres, mainUrl)
            check(rows.isNotEmpty()) { "Keine Anime-Genres erkannt" }
            rows.forEach { row ->
                val items = row.anime.map { anime ->
                    newTvSeriesSearchResponse(anime.title, anime.url, TvType.Anime) {
                        posterUrl = AniworldCoverStore.resolve(anime.url, null)
                    }
                }
                sections += HomePageList(row.title, items)
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
                val series = AniworldCatalog.genres(genres, provider.mainUrl)
                    .flatMap { it.anime }.map { it.url }.distinct()
                check(series.isNotEmpty()) { "Keine Animes gefunden" }
                val pending = series.filter { AniworldCoverStore.resolve(it, null).isNullOrBlank() }
                val failed = mutableListOf<String>()
                val workers = java.util.concurrent.Executors.newFixedThreadPool(4)
                try {
                    pending.chunked(6).forEachIndexed { index, batch ->
                        progress("Fehlende Cover ${index * 6}/${pending.size} – ${AniworldCoverStore.count()} gespeichert")
                        val tasks = batch.map { url ->
                            workers.submit(java.util.concurrent.Callable {
                                val page = provider.document(url)
                                val image = page.selectFirst("img[data-src*='/public/img/cover/'], img[src*='/public/img/cover/']")
                                val poster = image?.let { AniworldCatalog.poster(it, provider.mainUrl) }
                                checkNotNull(poster) { "Kein Cover erkannt" }
                                url to poster
                            })
                        }
                        val found = tasks.mapIndexedNotNull { itemIndex, task ->
                            try {
                                task.get()
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                throw e
                            } catch (e: Exception) {
                                failed += "${URI(batch[itemIndex]).path.substringAfterLast('/')}: ${e.cause?.message ?: e.message}"
                                null
                            }
                        }.toMap()
                        if (found.isNotEmpty()) AniworldCoverStore.mergeAndSave(found)
                        progress("Fehlende Cover ${index * 6 + batch.size}/${pending.size} – ${AniworldCoverStore.count()} gespeichert")
                    }
                } finally {
                    workers.shutdownNow()
                }
                val count = AniworldCoverStore.count()
                check(failed.isEmpty()) {
                    "${failed.size} Covers konnten nicht geladen werden. $count bleiben gespeichert. ${failed.first()}"
                }
                status = "$count Covers gespeichert"
                return count
            } catch (e: Exception) {
                status = "Cover-Sync fehlgeschlagen: ${e.message}"
                throw e
            } finally {
                running.set(false)
            }
        }
    }
}
