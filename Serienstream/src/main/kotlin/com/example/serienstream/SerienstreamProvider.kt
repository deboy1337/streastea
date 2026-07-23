package com.example.serienstream

import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

open class SerienstreamProvider : MainAPI() {
    override var mainUrl = "https://serienstream.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override var lang = "de"

    private var isLoggedIn = false
    private var triedLogin = false

    private suspend fun ensureLoggedIn() {
        if (isLoggedIn || triedLogin) return
        triedLogin = true

        val email = getKey<String>(SETTING_EMAIL)
        val password = getKey<String>(SETTING_PASSWORD)
        if (email.isNullOrBlank() || password.isNullOrBlank()) {
            Log.w(TAG, "No login credentials stored")
            return
        }

        try {
            val loginPage = app.get("$mainUrl/login")
            val doc = loginPage.document
            val csrfToken = doc.selectFirst("input[name='_token']")?.attr("value")

            if (csrfToken == null) {
                Log.e(TAG, "CSRF token not found on login page")
                return
            }

            Log.d(TAG, "Attempting login with email: $email")

            val loginResp = app.post(
                "$mainUrl/login",
                data = mapOf(
                    "_token" to csrfToken,
                    "email" to email,
                    "password" to password
                ),
                headers = mapOf(
                    "Referer" to "$mainUrl/login",
                    "Origin" to mainUrl,
                    "Content-Type" to "application/x-www-form-urlencoded"
                )
            )

            val respDoc = loginResp.document
            val hasError = respDoc.select(".alert-danger, .invalid-feedback, .error").isNotEmpty()
            val stillOnLogin = loginResp.url.contains("/login")
            val hasLogout = respDoc.select("a[href*='logout'], a[href*='profil'], .user-menu, .dropdown-menu").isNotEmpty()

            if (stillOnLogin || hasError) {
                Log.e(TAG, "Login failed - still on login page: $stillOnLogin, has error: $hasError")
                val errorText = respDoc.select(".alert-danger, .invalid-feedback").text()
                if (errorText.isNotEmpty()) {
                    Log.e(TAG, "Login error message: $errorText")
                }
            } else {
                isLoggedIn = true
                Log.d(TAG, "Login successful! Final URL: ${loginResp.url}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login exception: ${e.message}", e)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureLoggedIn()
        val document = app.get("$mainUrl/beliebte-serien").document
        val items = document.select("a.show-card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(listOf(HomePageList("Beliebte Serien", items)), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureLoggedIn()
        val document = app.get("$mainUrl/search", params = mapOf("q" to query)).document
        return document.select("a.show-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()
            ?: throw Error("Titel konnte nicht gefunden werden")
        val poster = fixUrlNull(
            document.selectFirst("img[alt='$title']")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
        )
        val description = document.selectFirst(".description-text")?.text()
        val genres = document.select("a[href*='/genre/']").map { it.text() }
        val actors = document.select("a[href*='/person/']").map { it.text() }
        val trailerUrl = document.selectFirst("button[data-trailer-url]")
            ?.attr("data-trailer-url")
        val seasons = document.select("#season-nav a.alphabet-link")
        val episodes = seasons.flatMap { seasonLink ->
            val seasonNum = seasonLink.text().trim().toIntOrNull() ?: return@flatMap emptyList()
            val seasonUrl = fixUrl(seasonLink.attr("href"))
            val seasonDoc = app.get(seasonUrl).document
            seasonDoc.select("tr.episode-row").mapNotNull { row ->
                val episodeNum = row.selectFirst(".episode-number-cell")
                    ?.text()?.trim()?.toIntOrNull()
                val epTitle = row.selectFirst(".episode-title-ger")?.text()?.trim()
                    ?: row.selectFirst(".episode-title-cell strong")?.text()?.trim()
                val href = run {
                    val onclick = row.attr("onclick")
                    val onclickUrl = onclick
                        .substringAfter("window.location='", "")
                        .substringBefore("'", "")
                        .trim()
                    if (onclickUrl.isNotEmpty()) return@run onclickUrl
                    val linkEl = row.selectFirst("a[href*='episode-']")
                    linkEl?.attr("href") ?: ""
                }.trim()
                if (href.isEmpty()) return@mapNotNull null
                newEpisode(fixUrl(href)) {
                    this.episode = episodeNum
                    this.name = epTitle
                    this.season = seasonNum
                }
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            addActors(actors)
            addTrailer(trailerUrl)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureLoggedIn()

        val document = app.get(data).document
        val buttons = document.select("button.link-box[data-play-url]")
        if (buttons.isEmpty()) {
            Log.w(TAG, "No hoster buttons found on $data")
            return false
        }

        Log.d(TAG, "Found ${buttons.size} hoster buttons, logged in: $isLoggedIn")

        buttons.amap { button ->
            val playUrl = button.attr("data-play-url").trim()
            val source = button.attr("data-provider-name")
            val language = button.attr("data-language-label")
            if (playUrl.isEmpty()) return@amap

            Log.d(TAG, "Trying hoster: $source [$language] -> $playUrl")

            val streamUrl = fixUrl(playUrl)
            val finalUrl = try {
                val resp = app.get(streamUrl)
                Log.d(TAG, "Hoster redirect: $streamUrl -> ${resp.url}")
                resp.url
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve hoster URL $streamUrl: ${e.message}")
                streamUrl
            }

            loadExtractor(finalUrl, data, subtitleCallback) { link ->
                Log.d(TAG, "Loaded stream from ${link.name}: ${link.url}")
                callback.invoke(link)
            }
        }
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val imgEl = this.selectFirst("img") ?: return null
        val title = imgEl.attr("alt") ?: return null
        val posterUrl = fixUrlNull(
            imgEl.attr("data-src").ifEmpty { imgEl.attr("src") }
        )
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    companion object {
        const val SETTING_EMAIL = "serienstream_email"
        const val SETTING_PASSWORD = "serienstream_password"
        private const val TAG = "Serienstream"
    }
}
