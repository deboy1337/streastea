package com.example.serienstream

import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element

open class SerienstreamProvider : MainAPI() {
    override var mainUrl = "https://serienstream.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override var lang = "de"

    private var isLoggedIn = false
    private var triedLogin = false

    private fun toast(msg: String) {
        try {
            val ctx = CommonActivity.activity ?: return
            ctx.runOnUiThread {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {}
    }

    private suspend fun ensureLoggedIn() {
        if (isLoggedIn || triedLogin) return
        triedLogin = true

        val email = getKey<String>(SETTING_EMAIL)
        val password = getKey<String>(SETTING_PASSWORD)
        if (email.isNullOrBlank() || password.isNullOrBlank()) {
            Log.w(TAG, "No login credentials stored")
            toast("Serienstream: Keine Zugangsdaten gespeichert")
            return
        }

        try {
            val loginPageResp = app.get(
                "$mainUrl/login",
                headers = mapOf("User-Agent" to DESKTOP_UA)
            )
            val doc = loginPageResp.document
            val csrfToken = doc.selectFirst("input[name='_token']")?.attr("value")

            if (csrfToken == null) {
                Log.e(TAG, "CSRF token not found on login page")
                toast("Serienstream: CSRF Token nicht gefunden")
                return
            }

            Log.d(TAG, "Attempting login with email: $email")
            toast("Serienstream: Login wird versucht...")

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
                    "User-Agent" to DESKTOP_UA,
                    "X-XSRF-TOKEN" to csrfToken
                )
            )

            val respUrl = loginResp.url.toString()
            val respDoc = loginResp.document
            val hasError = respDoc.select(".alert-danger, .invalid-feedback, .error").isNotEmpty()
            val stillOnLogin = respUrl.contains("/login")

            Log.d(TAG, "Login response URL: $respUrl, stillOnLogin: $stillOnLogin, hasError: $hasError")

            if (stillOnLogin || hasError) {
                val errorText = respDoc.select(".alert-danger, .invalid-feedback").text()
                Log.e(TAG, "Login failed. Error: $errorText")
                toast("Serienstream: Login fehlgeschlagen: ${errorText.ifEmpty { "Unbekannter Fehler" }}")
                return
            }

            // Verify by checking /account page for username
            Log.d(TAG, "Login POST succeeded, verifying via /account...")
            val accountResp = app.get("$mainUrl/account", headers = mapOf("User-Agent" to DESKTOP_UA))
            val accountDoc = accountResp.document
            val accountUrl = accountResp.url.toString()
            val accountHtml = accountDoc.html()

            // Check if we got redirected back to login
            if (accountUrl.contains("/login")) {
                Log.e(TAG, "Account check redirected to login - session not maintained")
                toast("Serienstream: Session nicht gueltig (weitergeleitet zu Login)")
                return
            }

            // Check for username indicators in the account page
            val hasWelcome = accountHtml.contains("Willkommen")
            val hasLogout = accountDoc.select("a[href*='logout']").isNotEmpty()
            val hasProfileLink = accountDoc.select("a[href*='profil'], a[href*='account']").isNotEmpty()
            val bodyText = accountDoc.body()?.text() ?: ""

            Log.d(TAG, "Account page - hasWelcome: $hasWelcome, hasLogout: $hasLogout, hasProfileLink: $hasProfileLink")
            Log.d(TAG, "Account page title: ${accountDoc.title()}")
            Log.d(TAG, "Account body text (first 500 chars): ${bodyText.take(500)}")

            if (hasLogout || hasWelcome) {
                isLoggedIn = true
                val msg = "Serienstream: Login erfolgreich! Willkommen!"
                Log.d(TAG, msg)
                toast(msg)
            } else {
                Log.w(TAG, "Login verification unclear - account page doesn't show welcome/logout")
                toast("Serienstream: Login unklar - Account-Seite pruefen")
                // Still try to proceed - maybe login worked but account page is different
                isLoggedIn = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login exception: ${e.message}", e)
            toast("Serienstream: Login Fehler: ${e.message}")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureLoggedIn()
        val document = app.get("$mainUrl/beliebte-serien", headers = mapOf("User-Agent" to DESKTOP_UA)).document
        val items = document.select("a.show-card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(listOf(HomePageList("Beliebte Serien", items)), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureLoggedIn()
        val document = app.get("$mainUrl/search", params = mapOf("q" to query), headers = mapOf("User-Agent" to DESKTOP_UA)).document
        return document.select("a.show-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to DESKTOP_UA)).document
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
            val seasonDoc = app.get(seasonUrl, headers = mapOf("User-Agent" to DESKTOP_UA)).document
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

        toast("Serienstream: Lade Streams fuer $data")

        val document = app.get(data, headers = mapOf("User-Agent" to DESKTOP_UA)).document
        val pageHtml = document.html()
        val title = document.selectFirst("h1")?.text() ?: "unbekannt"

        Log.d(TAG, "Episode page title: $title")
        Log.d(TAG, "Episode page URL: $data")
        Log.d(TAG, "Has gate div: ${pageHtml.contains("episode-redirect-gate")}")
        Log.d(TAG, "Has play buttons: ${pageHtml.contains("data-play-url")}")

        val buttons = document.select("button.link-box[data-play-url]")
        if (buttons.isEmpty()) {
            val allButtons = document.select("button")
            val allLinks = document.select("a[href*='stream'], a[href*='embed']")
            Log.w(TAG, "No hoster buttons found. All buttons: ${allButtons.size}, stream links: ${allLinks.size}")
            Log.d(TAG, "Page body (first 2000 chars): ${document.body()?.html()?.take(2000)}")
            toast("Serienstream: Keine Hoster gefunden auf '$title'")
            return false
        }

        Log.d(TAG, "Found ${buttons.size} hoster buttons, logged in: $isLoggedIn")
        toast("Serienstream: ${buttons.size} Hoster gefunden fuer '$title'")

        buttons.amap { button ->
            val playUrl = button.attr("data-play-url").trim()
            val source = button.attr("data-provider-name")
            val language = button.attr("data-language-label")
            if (playUrl.isEmpty()) return@amap

            Log.d(TAG, "Trying hoster: $source [$language] -> $playUrl")
            toast("Serienstream: Teste $source ($language)...")

            val streamUrl = fixUrl(playUrl)
            val finalUrl = try {
                val resp = app.get(streamUrl, headers = mapOf("User-Agent" to DESKTOP_UA))
                val respBody = resp.document.html()
                Log.d(TAG, "Hoster response URL: ${resp.url}")
                Log.d(TAG, "Hoster response has iframe: ${respBody.contains("<iframe")}")
                Log.d(TAG, "Hoster response (first 1000): ${respBody.take(1000)}")
                resp.url
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve hoster URL $streamUrl: ${e.message}")
                toast("Serienstream: Fehler bei $source: ${e.message}")
                streamUrl
            }

            loadExtractor(finalUrl, data, subtitleCallback) { link ->
                Log.d(TAG, "Loaded stream from ${link.name}: quality=${link.quality}")
                toast("Serienstream: Stream gefunden: ${link.name} (${link.quality})")
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
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
