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
        if (getKey<String>(SETTING_SYNC_REQUESTED) == "true") {
            setKey(SETTING_SYNC_REQUESTED, "false")
            setKey(SETTING_POSTER_MAP, "")
            Log.i(TAG, "getMainPage: sync requested, cleared old cache")
            Thread { runBlocking { syncGenrePosters() } }.start()
        }

        ensureLoggedIn()
        val sections = mutableListOf<HomePageList>()

        try {
            val document = app.get("$mainUrl/beliebte-serien", headers = authHeaders()).document
            document.select(".popular-page > div").forEach { elem ->
                val header = elem.selectFirst("div > h2")?.text()?.trim() ?: return@forEach
                val items = elem.select("a.show-card").mapNotNull { it.toShowCardResult() }
                if (items.isNotEmpty()) {
                    sections.add(HomePageList(header, items))
                }
            }
            if (sections.isEmpty()) {
                val items = document.select("a.show-card").mapNotNull { it.toShowCardResult() }
                if (items.isNotEmpty()) {
                    sections.add(HomePageList("Beliebte Serien", items))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed: ${e.message}")
        }

        try {
            val doc = app.get("$mainUrl/serien?by=genre", headers = authHeaders()).document

            val genreData = doc.select("div.background-1.border-radius-4.px-2.py-2.mb-2").mapNotNull { headingDiv ->
                val genreName = headingDiv.selectFirst("h3")?.text()?.trim()?.let {
                    GENRE_NAMES[it] ?: it.replace("filter.genre_", "").replace("-", " ")
                        .replaceFirstChar { c -> c.uppercase() }
                } ?: return@mapNotNull null
                val ul = headingDiv.nextElementSibling()
                if (ul == null || ul.tagName() != "ul") return@mapNotNull null
                val items = ul.select("li.series-item a").mapNotNull { a ->
                    val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                    val name = a.text().trim()
                    if (name.isEmpty()) return@mapNotNull null
                    Pair(name, href)
                }
                if (items.isEmpty()) return@mapNotNull null
                Pair(genreName, items)
            }

            val posterMap = loadPosterMap()
            Log.i(TAG, "getMainPage: ${posterMap.size} cached posters")
            if (posterMap.isEmpty()) {
                Log.i(TAG, "getMainPage: no cached posters, background sync started")
                Thread { runBlocking { syncGenrePosters() } }.start()
            } else {
                toast("${posterMap.size} Covers geladen")
            }

            genreData.forEach { (genreName, textItems) ->
                val items = textItems.mapNotNull { (name, href) ->
                    newTvSeriesSearchResponse(name, href, TvType.TvSeries) {
                        this.posterUrl = posterMap[href]
                    }
                }
                if (items.isNotEmpty()) {
                    sections.add(HomePageList(genreName, items))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed: ${e.message}")
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
        }.ifEmpty {
            resp.select("a.show-card").mapNotNull { it.toShowCardResult() }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureLoggedIn()

        val document = app.get(url, headers = authHeaders()).document
        val title = document.selectFirst("h1")?.text()
            ?: throw RuntimeException("Failed to find series title")

        val poster = document.select("img[alt='${title.replace("'", "\\'")}']")
            .firstOrNull()?.let { fixUrlNull(it.attr("data-src")) }

        val description = document.selectFirst(".description-text")?.text() ?: ""

        val genres = document.select("a[href*='/genre/']").map { it.text().trim() }

        val actors = document.select("a[href*='/person/']").map { it.text().trim() }

        val trailerUrl = document.selectFirst("button[data-trailer-url]")
            ?.attr("data-trailer-url")

        val episodes = document.select("#season-nav a.alphabet-link").amap {
            val seasonNumber = it.text().trim().toIntOrNull()
            val seasonDocument = app.get(fixUrl(it.attr("href")), headers = authHeaders()).document
            seasonDocument.select("tr.episode-row").map { eps ->
                val episodeLink = eps.attr("onclick")
                    ?.substringAfter("window.location='")
                    ?.substringBefore("'") ?: return@map null
                newEpisode(episodeLink) {
                    this.episode = eps.selectFirst(".episode-number-cell")?.text()?.toIntOrNull()
                    this.name = eps.selectFirst(".episode-title-ger")?.text()
                    this.season = seasonNumber
                }
            }.filterNotNull()
        }.flatten()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.name = title
            this.posterUrl = poster
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

        val document = app.get(data, headers = authHeaders()).document

        val buttons = document.select("button.link-box[data-play-url]")
        if (buttons.isEmpty()) {
            return false
        }

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
        val href = fixUrlNull(
            if (tagName() == "a") attr("href") else selectFirst("a")?.attr("href")
        ) ?: return null
        val imgEl = selectFirst("img") ?: return null
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

    suspend fun testTmdbKey(key: String): Boolean {
        return try {
            val req = okhttp3.Request.Builder()
                .url("https://api.themoviedb.org/3/configuration?api_key=$key")
                .header("User-Agent", DESKTOP_UA)
                .get()
                .build()
            val resp = okhttp3.OkHttpClient().newCall(req).execute()
            val json = org.json.JSONObject(resp.body?.string() ?: "{}")
            json.has("images") && !json.has("status_code")
        } catch (_: Exception) { false }
    }

    suspend fun syncGenrePosters() {
        ensureLoggedIn()
        val tmdbKey = getKey<String>(SETTING_TMDB_KEY) ?: ""
        if (tmdbKey.isBlank()) {
            Log.w(TAG, "Cover sync: kein TMDB API-Key gespeichert")
            setKey(SETTING_SYNC_REQUESTED, "true")
            return
        }

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        try {
            val doc = app.get("$mainUrl/serien?by=genre", headers = authHeaders()).document
            val allSeries = doc.select("div.background-1.border-radius-4.px-2.py-2.mb-2").flatMap { headingDiv ->
                val ul = headingDiv.nextElementSibling()
                if (ul == null || ul.tagName() != "ul") return@flatMap emptyList()
                ul.select("li.series-item a").mapNotNull { a ->
                    val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                    val name = a.text().trim()
                    if (name.isEmpty()) null else Pair(name, href)
                }
            }
            if (allSeries.isEmpty()) {
                Log.w(TAG, "Cover sync: keine Serien in by=genre gefunden")
                return
            }
            Log.i(TAG, "Cover sync: ${allSeries.size} Serien, suche via TMDB...")

            val fullMap = mutableMapOf<String, String>()
            allSeries.forEachIndexed { i, (name, url) ->
                if (i > 0 && i % 10 == 0) {
                    Log.i(TAG, "Cover sync: $i/${allSeries.size} (${fullMap.size} Poster)")
                }
                try {
                    val req = okhttp3.Request.Builder()
                        .url("https://api.themoviedb.org/3/search/tv?api_key=$tmdbKey&query=${java.net.URLEncoder.encode(name, "UTF-8")}&language=de-DE")
                        .header("User-Agent", DESKTOP_UA)
                        .get()
                        .build()
                    val resp = client.newCall(req).execute()
                    val body = resp.body?.string()
                    if (body != null) {
                        val json = org.json.JSONObject(body)
                        json.optJSONArray("results")?.let { arr ->
                            if (arr.length() > 0) {
                                val posterPath = arr.getJSONObject(0).optString("poster_path", "")
                                if (posterPath.isNotBlank()) {
                                    fullMap[url] = "https://image.tmdb.org/t/p/w500$posterPath"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "TMDB search failed for '$name': ${e.message}")
                }
                kotlinx.coroutines.delay(100)
            }

            Log.i(TAG, "Cover sync: ${fullMap.size} Poster von TMDB")
            if (fullMap.isEmpty()) {
                setKey(SETTING_POSTER_MAP, "")
            } else {
                savePosterMap(fullMap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cover sync failed: ${e.message}")
        }
    }

    private fun savePosterMap(map: Map<String, String>) {
        val obj = org.json.JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        setKey(SETTING_POSTER_MAP, obj.toString())
    }

    private fun loadPosterMap(): Map<String, String> {
        val json = getKey<String>(SETTING_POSTER_MAP) ?: return emptyMap()
        if (json.isBlank() || json == "{}") return emptyMap()
        val obj = try { org.json.JSONObject(json) } catch (_: Exception) { return emptyMap() }
        val map = mutableMapOf<String, String>()
        obj.keys().forEach { key ->
            obj.optString(key)?.let { map[key] = it }
        }
        Log.i(TAG, "loadPosterMap: ${map.size} entries")
        return map
    }

    companion object {
        const val SETTING_EMAIL = "serienstream_email"
        const val SETTING_PASSWORD = "serienstream_password"
        const val SETTING_POSTER_MAP = "genre_poster_map"
        const val SETTING_TMDB_KEY = "tmdb_api_key"
        const val SETTING_SYNC_REQUESTED = "sync_requested"
        private const val TAG = "Serienstream"
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val GENRE_NAMES = mapOf(
            "filter.genre_doku-soap" to "Dokusoap",
            "filter.genre_historie" to "History",
            "filter.genre_krankenhausserie" to "Krankenhaus"
        )
    }
}
