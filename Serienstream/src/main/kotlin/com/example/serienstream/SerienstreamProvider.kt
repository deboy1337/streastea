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
import java.net.URLEncoder

open class SerienstreamProvider : MainAPI() {
    override var mainUrl = "https://serienstream.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override var lang = "de"

    private var isLoggedIn = false
    private var triedLogin = false
    private var sessionCookies
        get() = sharedSessionCookies
        set(value) { sharedSessionCookies = value }

    private val mirrorDomains = listOf(
        "https://serienstream.to",
        "https://serienstream.cloud",
        "https://serienstream.lol",
        "https://serienstream.stream"
    )
    private var workingDomainIndex = 0
    private var domainTested = false

    private fun toast(msg: String) {
        try {
            val ctx = CommonActivity.activity ?: return
            ctx.runOnUiThread {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {}
    }

    private suspend fun <T> withDomainFallback(block: (String) -> T): T {
        var lastException: Exception? = null
        
        for (i in workingDomainIndex until mirrorDomains.size) {
            mainUrl = mirrorDomains[i]
            workingDomainIndex = i
            try {
                val result = block(mainUrl)
                if (!domainTested) {
                    domainTested = true
                    toast("Serienstream: Verbunden über ${mainUrl}")
                }
                // Arbeitsdomain speichern für Companion-Funktionen
                setKey(SETTING_WORKING_DOMAIN, mainUrl)
                return result
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Domain $mainUrl fehlgeschlagen: ${e.message}, versuche nächste...")
                if (i == mirrorDomains.lastIndex) break
                continue
            }
        }
        
        throw lastException ?: RuntimeException("Alle Spiegel-Domains fehlgeschlagen")
    }

    private val docClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private suspend fun getDocument(url: String, headers: Map<String, String> = emptyMap()): org.jsoup.nodes.Document {
        return withDomainFallback { baseUrl ->
            val fullUrl = if (url.startsWith("http")) {
                try {
                    val parsed = url.toHttpUrl()
                    if (parsed != null) baseUrl + parsed.encodedPath + (parsed.encodedQuery?.let { "?$it" } ?: "")
                    else url
                } catch (_: Exception) {
                    url
                }
            } else "$baseUrl$url"
            val requestBuilder = Request.Builder().url(fullUrl).header("User-Agent", DESKTOP_UA)
            for ((k, v) in headers) requestBuilder.header(k, v)
            val request = requestBuilder.build()
            val response = docClient.newCall(request).execute()
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
            val html = response.body?.string() ?: throw RuntimeException("Empty response")
            org.jsoup.Jsoup.parse(html, fullUrl)
        }
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

        for (i in workingDomainIndex until mirrorDomains.size) {
            mainUrl = mirrorDomains[i]
            workingDomainIndex = i
            
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
                    Log.e(TAG, "CSRF token not found on login page for $mainUrl")
                    if (i < mirrorDomains.lastIndex) continue
                    toast("Serienstream: CSRF Token nicht gefunden")
                    return
                }

                toast("Serienstream: Login wird versucht... ($mainUrl)")

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

                    if (isLoggedInHtml(verifyHtml)) {
                        isLoggedIn = true
                        val allCookies = client.cookieJar.loadForRequest("$mainUrl/".toHttpUrl())
                        sessionCookies = allCookies.joinToString("; ") { "${it.name}=${it.value}" }
                        if (!domainTested) {
                            domainTested = true
                            toast("Serienstream: Verbunden über $mainUrl")
                        }
                        setKey(SETTING_WORKING_DOMAIN, mainUrl)
                        toast("Serienstream: Login erfolgreich!")
                        return
                    } else {
                        if (i < mirrorDomains.lastIndex) continue
                        toast("Serienstream: Login fehlgeschlagen")
                        return
                    }
                } else if (postCode == 200) {
                    val bodyHtml = postResponse.body?.string() ?: ""
                    if (bodyHtml.contains("Anmelden") && bodyHtml.contains("_token")) {
                        val errorMsg = Regex("""class="alert-danger[^"]*"[^>]*>([^<]+)""").find(bodyHtml)
                            ?.groupValues?.get(1)?.trim() ?: "Falsche Zugangsdaten?"
                        if (i < mirrorDomains.lastIndex) continue
                        toast("Serienstream: Login fehlgeschlagen: $errorMsg")
                        return
                    } else {
                        isLoggedIn = true
                        val allCookies = client.cookieJar.loadForRequest("$mainUrl/".toHttpUrl())
                        sessionCookies = allCookies.joinToString("; ") { "${it.name}=${it.value}" }
                        if (!domainTested) {
                            domainTested = true
                            toast("Serienstream: Verbunden über $mainUrl")
                        }
                        setKey(SETTING_WORKING_DOMAIN, mainUrl)
                        toast("Serienstream: Login erfolgreich!")
                        return
                    }
                } else {
                    if (i < mirrorDomains.lastIndex) continue
                    toast("Serienstream: Unerwarteter Status: $postCode")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Login failed on $mainUrl: ${e.message}")
                if (i == mirrorDomains.lastIndex) {
                    toast("Serienstream: Login auf allen Domains fehlgeschlagen")
                }
            }
        }
    }

    private fun isLoggedInHtml(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("/logout") ||
            lower.contains("abmelden") ||
            lower.contains("willkommen") ||
            lower.contains("mein konto") ||
            lower.contains("angemeldet")
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
            Thread { SerienstreamProvider.syncGenrePosters() }.start()
        }

        ensureLoggedIn()
        val sections = mutableListOf<HomePageList>()

        try {
            val document = getDocument("/beliebte-serien", authHeaders())
            for (elem in document.select(".popular-page > div")) {
                val header = elem.selectFirst("div > h2")?.text()?.trim() ?: continue
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
            val doc = getDocument("/serien?by=genre", authHeaders())

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
                Thread { SerienstreamProvider.syncGenrePosters() }.start()
            } else {
                toast("${posterMap.size} Covers geladen")
            }

            for ((genreName, textItems) in genreData) {
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
        val searchUrl = "/suche?term=${URLEncoder.encode(query, "UTF-8")}&tab=shows"
        val resp = getDocument(searchUrl, authHeaders())

        return resp.select(".results-group .card").mapNotNull {
            it.toSearchResult()
        }.ifEmpty {
            resp.select("a.show-card").mapNotNull { it.toShowCardResult() }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureLoggedIn()

        val document = getDocument(url, authHeaders())
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
            val seasonDocument = getDocument(fixUrl(it.attr("href")), authHeaders())
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

        val document = getDocument(data, authHeaders())

        val gate = document.selectFirst("[data-redirect-gate-tier]")

        if (gate != null) {
            setKey(SETTING_CAPTCHA_URL, data)
            Log.w(TAG, "Gate detected, saved captcha URL")
        }

        val buttons = document.select("button.link-box[data-play-url]").sortedBy { button ->
            when (button.attr("data-language-label").trim()) {
                "Deutsch" -> 0
                "Ger-Sub" -> 1
                else -> 2
            }
        }

        if (buttons.isNotEmpty()) {
            val hosterList = buttons.map { button ->
                listOf(
                    button.attr("data-play-url").trim(),
                    button.attr("data-provider-name"),
                    button.attr("data-language-label").trim()
                )
            }
            processHosters(hosterList, data, subtitleCallback, callback)
            return true
        }

        val cached = getCachedHosters()
        if (cached.isNotEmpty()) {
            Log.w(TAG, "Using ${cached.size} cached hosters")
            setCachedHosters(emptyList())
            setKey(SETTING_CAPTCHA_URL, "")
            processHosters(cached, data, subtitleCallback, callback)
            return true
        }

        if (gate != null) {
            val tier = gate.attr("data-redirect-gate-tier")
            Log.w(TAG, "⚠️ Gate detected ($tier), still no hosters")
            toast("Serienstream: Captcha nötig - Einstellungen > Captcha lösen")
            return false
        }

        Log.w(TAG, "No hosters found on $data")
        return false
    }

    private suspend fun processHosters(
        hosters: List<List<String>>,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        for ((playUrl, source, language) in hosters) {
            if (playUrl.isEmpty()) continue
            Log.d(TAG, "Hoster: $source [$language] -> $playUrl")
            val streamUrl = fixUrl(playUrl)
            val finalUrl = try {
                val reqBuilder = Request.Builder().url(streamUrl).header("User-Agent", DESKTOP_UA)
                for ((k, v) in authHeaders()) reqBuilder.header(k, v)
                val resp = docClient.newCall(reqBuilder.build()).execute()
                resp.use { it.request.url.toString() }
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

    private fun loadPosterMap(): Map<String, String> {
        val json = getKey<String>(SETTING_POSTER_MAP) ?: return emptyMap()
        if (json.isBlank() || json == "{}") return emptyMap()
        val obj = try { org.json.JSONObject(json) } catch (_: Exception) { return emptyMap() }
        val map = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            obj.optString(key)?.let { map[key] = it }
        }
        Log.i(TAG, "loadPosterMap: ${map.size} entries")
        return map
    }

    companion object {
        const val SETTING_EMAIL = "serienstream_email"
        const val SETTING_PASSWORD = "serienstream_password"
        const val SETTING_POSTER_MAP = "genre_poster_map"
        const val SETTING_SYNC_REQUESTED = "sync_requested"
        const val SETTING_CAPTCHA_URL = "serienstream_captcha_url"
        const val SETTING_WORKING_DOMAIN = "serienstream_working_domain"
        private const val TAG = "Serienstream"
        private const val DEFAULT_BASE_URL = "https://serienstream.to"
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        @JvmStatic
        private var sharedSessionCookies = ""

        @JvmStatic
        fun retrieveSessionCookies(): String = sharedSessionCookies

        @JvmStatic
        fun updateSessionCookies(cookies: String) { sharedSessionCookies = cookies }

        @JvmStatic
        fun getWorkingBaseUrl(): String {
            val saved = getKey<String>(SETTING_WORKING_DOMAIN)
            return if (saved.isNullOrBlank()) DEFAULT_BASE_URL else saved
        }

        @JvmStatic
        private var cachedHosters = mutableListOf<List<String>>()

        @JvmStatic
        fun getCachedHosters(): List<List<String>> = cachedHosters

        @JvmStatic
        fun setCachedHosters(hosters: List<List<String>>) { cachedHosters = hosters.toMutableList() }
        private val GENRE_NAMES = mapOf(
            "filter.genre_doku-soap" to "Dokusoap",
            "filter.genre_historie" to "History",
            "filter.genre_krankenhausserie" to "Krankenhaus"
        )

        fun clearCovers() {
            setKey(SETTING_POSTER_MAP, "")
            Log.i(TAG, "Covers gelöscht")
        }

        fun getCaptchaUrl(): String? = getKey<String>(SETTING_CAPTCHA_URL)?.takeIf { it.isNotBlank() }
        fun clearCaptchaUrl() { setKey(SETTING_CAPTCHA_URL, "") }

        private fun doLogin(client: OkHttpClient, email: String, password: String): Map<String, String> {
            val baseUrl = getWorkingBaseUrl()
            val loginPageReq = Request.Builder()
                .url("$baseUrl/login")
                .header("User-Agent", DESKTOP_UA)
                .get()
                .build()
            val loginPageResp = client.newCall(loginPageReq).execute()
            val loginHtml = loginPageResp.body?.string() ?: ""
            val csrf = Regex("""name="_token"\s+value="([^"]+)""").find(loginHtml)?.groupValues?.get(1)
            if (csrf == null) return emptyMap()

            val formBody = FormBody.Builder()
                .add("_token", csrf)
                .add("email", email)
                .add("password", password)
                .build()
            val loginPostReq = Request.Builder()
                .url("$baseUrl/login")
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "$baseUrl/login")
                .header("Origin", baseUrl)
                .post(formBody)
                .build()
            val loginResp = client.newCall(loginPostReq).execute()
            if (loginResp.code == 302) {
                val loc = loginResp.header("Location") ?: ""
                val fullLoc = if (loc.startsWith("http")) loc else "$baseUrl$loc"
                client.newCall(Request.Builder()
                    .url(fullLoc)
                    .header("User-Agent", DESKTOP_UA)
                    .get().build()).execute()
            }

            return client.cookieJar.loadForRequest("$baseUrl/".toHttpUrl())
                .associate { it.name to it.value }
        }

        fun syncGenrePosters() {
            val email = getKey<String>(SETTING_EMAIL) ?: ""
            val password = getKey<String>(SETTING_PASSWORD) ?: ""
            if (email.isBlank() || password.isBlank()) {
                Log.w(TAG, "Sync: keine Login-Daten")
                return
            }

            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .cookieJar(object : CookieJar {
                        private val store = mutableListOf<Cookie>()
                        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { store.addAll(cookies) }
                        override fun loadForRequest(url: HttpUrl): List<Cookie> = store
                    })
                    .build()

                var currentCookies = doLogin(client, email, password)
                if (currentCookies.isEmpty()) { Log.w(TAG, "Sync: Login fehlgeschlagen"); return }

                val baseUrl = getWorkingBaseUrl()

                val genreDoc = org.jsoup.Jsoup.connect("$baseUrl/serien?by=genre")
                    .userAgent(DESKTOP_UA)
                    .cookies(currentCookies)
                    .get()

                val genreSlugs = genreDoc.select("div.background-1.border-radius-4.px-2.py-2.mb-2").mapNotNull { div ->
                    val genreKey = div.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                    if (genreKey.startsWith("filter.genre_"))
                        genreKey.removePrefix("filter.genre_")
                    else
                        genreKey.lowercase().replace(" ", "-")
                }
                Log.i(TAG, "Sync: ${genreSlugs.size} Genres")

                val posterMap = mutableMapOf<String, String>()
                val seenHrefs = mutableSetOf<String>()

                genreSlugs.forEachIndexed { idx, slug ->
                    try {
                        var page = 1
                        while (true) {
                            val pageUrl = if (page == 1) "$baseUrl/genre/$slug"
                                else "$baseUrl/genre/$slug?page=$page"

                            try {
                                val doc = org.jsoup.Jsoup.connect(pageUrl)
                                    .userAgent(DESKTOP_UA)
                                    .cookies(currentCookies)
                                    .get()

                                val cards = doc.select("a.show-card")
                                if (cards.isEmpty()) {
                                    if (doc.title().contains("Anmelden", ignoreCase = true)) {
                                        currentCookies = doLogin(client, email, password)
                                        if (currentCookies.isNotEmpty()) continue
                                    }
                                    break
                                }

                                for (card in cards) {
                                    val href = card.attr("href")
                                    val fullHref = if (href.startsWith("http")) href else "$baseUrl$href"
                                    if (fullHref in seenHrefs) continue

                                    val img = card.selectFirst("img") ?: continue
                                    val poster = img.attr("data-src").ifEmpty { img.attr("src") }
                                    if (poster.isNotBlank()) {
                                        val fullPoster = if (poster.startsWith("http")) poster else "$baseUrl$poster"
                                        posterMap[fullHref] = fullPoster
                                        seenHrefs.add(fullHref)
                                    }
                                }

                                val hasNext = doc.select("a.page-link[rel=next]").isNotEmpty()
                                if (!hasNext) break
                                page++
                                Thread.sleep(200)
                            } catch (e: Exception) {
                                Log.w(TAG, "Fehler $pageUrl: ${e.message}")
                                if (page == 1) break
                                page++
                                Thread.sleep(500)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Genre '$slug' fehlgeschlagen: ${e.message}")
                    }
                    if ((idx + 1) % 5 == 0) {
                        Log.i(TAG, "Sync: ${idx + 1}/${genreSlugs.size} Genres, ${posterMap.size} Poster")
                    }
                }

                Log.i(TAG, "Sync: ${posterMap.size} Poster gespeichert")
                if (posterMap.isNotEmpty()) {
                    val obj = org.json.JSONObject()
                    for ((k, v) in posterMap) obj.put(k, v)
                    setKey(SETTING_POSTER_MAP, obj.toString())
                } else {
                    setKey(SETTING_POSTER_MAP, "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync fehlgeschlagen: ${e.message}")
            }
        }
    }
}
