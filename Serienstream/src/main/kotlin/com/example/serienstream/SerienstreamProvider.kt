package com.example.serienstream

import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
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
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

open class SerienstreamProvider : MainAPI() {
    override var mainUrl = "https://serienstream.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override var lang = "de"

    private var isLoggedIn = false
    private var triedLogin = false
    private var sessionCookies = ""

    private fun toast(msg: String) {
        try {
            val ctx = CommonActivity.activity ?: return
            ctx.runOnUiThread {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {}
    }

    private fun createCookieClient(): OkHttpClient {
        val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

        val cookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val key = url.host
                cookieStore.getOrPut(key) { mutableListOf() }.apply {
                    removeAll { existing -> cookies.any { it.name == existing.name && it.path == existing.path } }
                    addAll(cookies)
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val key = url.host
                val now = System.currentTimeMillis() / 1000
                return cookieStore[key]?.filter { cookie ->
                    val notExpired = cookie.expiresAt == -1L || cookie.expiresAt > now
                    val pathMatch = url.encodedPath.startsWith(cookie.path)
                    val domainMatch = url.host == cookie.domain || url.host.endsWith(".${cookie.domain}")
                    notExpired && pathMatch && domainMatch
                } ?: emptyList()
            }
        }

        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
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

        val client = createCookieClient()

        try {
            val loginPageRequest = Request.Builder()
                .url("$mainUrl/login")
                .header("User-Agent", DESKTOP_UA)
                .get()
                .build()

            val loginPageResponse = client.newCall(loginPageRequest).execute()
            val loginPageHtml = loginPageResponse.body?.string() ?: ""

            val csrfToken = Regex("""name="_token"\s+value="([^"]+)""").find(loginPageHtml)
                ?.groupValues?.get(1)
                ?: Regex("""content="([^"]+)"\s*""").find(
                    Regex("""meta\s+name="csrf-token"\s+content="([^"]+)""").find(loginPageHtml)?.value ?: ""
                )?.groupValues?.get(1)

            if (csrfToken == null) {
                Log.e(TAG, "CSRF token not found on login page")
                toast("Serienstream: CSRF Token nicht gefunden")
                return
            }

            toast("Serienstream: Login wird versucht...")

            val formBody = FormBody.Builder()
                .add("_token", csrfToken)
                .add("email", email)
                .add("password", password)
                .build()

            val postRequest = Request.Builder()
                .url("$mainUrl/login")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "$mainUrl/login")
                .header("Origin", mainUrl)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
                .header("X-XSRF-TOKEN", csrfToken)
                .post(formBody)
                .build()

            val postResponse = client.newCall(postRequest).execute()
            val postCode = postResponse.code

            if (postCode == 302 || postCode == 301) {
                val redirectLocation = postResponse.header("Location") ?: ""
                val fullRedirectUrl = if (redirectLocation.startsWith("http")) redirectLocation
                    else "$mainUrl$redirectLocation"

                val verifyRequest = Request.Builder()
                    .url(fullRedirectUrl)
                    .header("User-Agent", DESKTOP_UA)
                    .get()
                    .build()

                val verifyResponse = client.newCall(verifyRequest).execute()
                val verifyHtml = verifyResponse.body?.string() ?: ""

                if (verifyHtml.contains("Willkommen") || verifyHtml.contains("logout")) {
                    isLoggedIn = true
                    val allCookies = client.cookieJar.loadForRequest("$mainUrl/".toHttpUrl())
                    sessionCookies = allCookies.joinToString("; ") { "${it.name}=${it.value}" }
                    toast("Serienstream: Login erfolgreich!")
                } else {
                    toast("Serienstream: Login fehlgeschlagen")
                }
            } else if (postCode == 200) {
                val bodyHtml = postResponse.body?.string() ?: ""
                if (bodyHtml.contains("Anmelden") && bodyHtml.contains("_token")) {
                    val errorMsg = Regex("""class="alert-danger[^"]*"[^>]*>([^<]+)""").find(bodyHtml)
                        ?.groupValues?.get(1)?.trim() ?: "Falsche Zugangsdaten?"
                    toast("Serienstream: Login fehlgeschlagen: $errorMsg")
                } else {
                    isLoggedIn = true
                    val allCookies = client.cookieJar.loadForRequest("$mainUrl/".toHttpUrl())
                    sessionCookies = allCookies.joinToString("; ") { "${it.name}=${it.value}" }
                    toast("Serienstream: Login erfolgreich!")
                }
            } else {
                toast("Serienstream: Login Fehler (HTTP $postCode)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login exception: ${e.message}", e)
            toast("Serienstream: Login Fehler: ${e.message}")
        }
    }

    private fun authHeaders(): Map<String, String> {
        val h = mutableMapOf("User-Agent" to DESKTOP_UA)
        if (sessionCookies.isNotEmpty()) {
            h["Cookie"] = sessionCookies
        }
        return h
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureLoggedIn()
        val sections = mutableListOf<HomePageList>()

        try {
            val document = app.get("$mainUrl/beliebte-serien", headers = authHeaders()).document

            // Get sections from beliebte-serien page (e.g. "Neue Staffeln diese Woche", etc.)
            document.select(".popular-page > div").forEach { elem ->
                val header = elem.selectFirst("div > h2")?.text()?.trim() ?: return@forEach
                val items = elem.select("a.show-card").mapNotNull { it.toShowCardResult() }
                if (items.isNotEmpty()) {
                    sections.add(HomePageList(header, items))
                }
            }

            // Add genre sections by fetching a few genre pages
            val genreLinks = document.select("a[href*='/genre/']")
            val genreNames = mutableListOf<String>()
            genreLinks.take(6).forEach { el ->
                val href = fixUrlNull(el.attr("href")) ?: return@forEach
                val genreName = el.select(".h5, .fw-bold").lastOrNull()?.text()?.trim()
                    ?: el.text().trim()
                if (genreName.isEmpty() || genreNames.contains(genreName)) return@forEach
                if (genreName == "Sammlungen") return@forEach
                genreNames.add(genreName)

                try {
                    val genreDoc = app.get(href, headers = authHeaders()).document
                    val genreItems = genreDoc.select("a.show-card").mapNotNull { it.toShowCardResult() }
                    if (genreItems.isNotEmpty()) {
                        sections.add(HomePageList(genreName, genreItems))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load genre $genreName: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load main page: ${e.message}")
            toast("Serienstream: Fehler beim Laden der Startseite")
        }

        return newHomePageResponse(sections, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureLoggedIn()
        val resp = app.get(
            "$mainUrl/suche",
            params = mapOf("term" to query, "tab" to "shows"),
            headers = authHeaders()
        ).document

        return resp.select(".results-group .card").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureLoggedIn()
        val document = app.get(url, headers = authHeaders()).document
        val metaContainer = document.selectFirst(".show-header-wrapper .container-fluid > div")
            ?: throw RuntimeException("Metadata container not found")

        val title = metaContainer.selectFirst("h1")?.text()
            ?: throw RuntimeException("Failed to find series title")
        val poster = fixUrlNull(metaContainer.selectFirst("img")?.attr("data-src"))
        val year = metaContainer.selectFirst("h1 + p > a")?.text()?.toIntOrNull()
        val description = metaContainer.select(".description-text").text()
        val actors = metaContainer.select("li.series-group:contains(Besetzung:) a").map { it.text() }
        val genres = metaContainer.select("li.series-group:contains(Genre:) a").map { it.text() }
        val trailerUrl = metaContainer.selectFirst("button[data-trailer-url]")?.attr("data-trailer-url")

        val episodes = document.select("#season-nav ul > li a").amap {
            val seasonNumber = it.text().trim().toIntOrNull()
            val seasonDocument = app.get(fixUrl(it.attr("href")), headers = authHeaders()).document

            seasonDocument.select(".episode-section .episode-row").map { eps ->
                val episodeLink = eps.attr("onclick")
                    .substringAfter("=")
                    .trim('\'')

                newEpisode(episodeLink) {
                    this.episode = eps.selectFirst(".episode-number-cell")?.text()?.toIntOrNull()
                    this.name = eps.select(".episode-title-cell > *")
                        .joinToString(" - ") { el -> el.text() }
                    this.season = seasonNumber
                }
            }
        }.flatten()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.name = title
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = genres
            addTrailer(trailerUrl)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureLoggedIn()

        toast("Serienstream: Lade Streams...")

        val document = app.get(data, headers = authHeaders()).document
        val title = document.selectFirst("h1")?.text() ?: "unbekannt"

        val buttons = document.select("button.link-box[data-play-url]")
        if (buttons.isEmpty()) {
            toast("Serienstream: Keine Hoster fuer '$title'")
            return false
        }

        toast("Serienstream: ${buttons.size} Hoster fuer '$title'")

        buttons.amap { button ->
            val playUrl = button.attr("data-play-url").trim()
            val source = button.attr("data-provider-name")
            val language = button.attr("data-language-label").trim()
            if (playUrl.isEmpty()) return@amap

            Log.d(TAG, "Hoster: $source [$language] -> $playUrl")

            val streamUrl = fixUrl(playUrl)
            val finalUrl = try {
                val resp = app.get(streamUrl, headers = authHeaders())
                resp.url
            } catch (e: Exception) {
                Log.e(TAG, "Failed: $streamUrl: ${e.message}")
                streamUrl
            }

            loadExtractor(finalUrl, data, subtitleCallback) { link ->
                val linkWithLang = runBlocking {
                    newExtractorLink(
                        source = source,
                        name = "$source - $language",
                        url = link.url
                    ) {
                        referer = link.referer
                        quality = link.quality
                        type = link.type
                        headers = link.headers
                        extractorData = link.extractorData
                    }
                }
                callback.invoke(linkWithLang)
            }
        }
        return true
    }

    private fun Element.toShowCardResult(): SearchResponse? {
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val imgEl = this.selectFirst("img") ?: return null
        val title = imgEl.attr("alt").ifEmpty { return null }
        val posterUrl = fixUrlNull(
            imgEl.attr("data-src").ifEmpty { imgEl.attr("src") }
        )
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val imgEl = this.selectFirst("img") ?: return null
        val title = imgEl.attr("alt").ifEmpty { return null }
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
