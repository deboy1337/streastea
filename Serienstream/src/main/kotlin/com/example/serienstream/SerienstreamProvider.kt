package com.example.serienstream

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.USER_AGENT
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jsoup.nodes.Element
import kotlin.coroutines.resume

open class SerienstreamProvider : MainAPI() {
    override var mainUrl = "https://serienstream.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(TvType.TvSeries)

    override val hasMainPage = true
    override var lang = "de"

    private var isLoggedIn = false

    private suspend fun ensureLoggedIn() {
        if (isLoggedIn) return
        val email = getKey<String>(SETTING_EMAIL)
        val password = getKey<String>(SETTING_PASSWORD)
        if (email.isNullOrBlank() || password.isNullOrBlank()) return
        try {
            val loginPage = app.get("$mainUrl/login").document
            val csrfToken = loginPage.selectFirst("input[name='_token']")?.attr("value")
                ?: return
            val loginResp = app.post(
                "$mainUrl/login",
                data = mapOf("_token" to csrfToken, "email" to email, "password" to password),
                headers = mapOf("Referer" to "$mainUrl/login")
            )
            isLoggedIn = !loginResp.url.contains("/login")
        } catch (_: Exception) { }
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
            val seasonNum = seasonLink.text().trim().toIntOrNull()
                ?: return@flatMap emptyList()
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
        if (buttons.isEmpty()) return false

        val hasCaptcha = document.select("#episode-redirect-gate-root").isNotEmpty()

        if (hasCaptcha) {
            val embedUrl = resolveEmbedViaWebView(data) ?: return false
            loadExtractor(embedUrl, data, subtitleCallback) { link ->
                callback.invoke(link)
            }
            return true
        }

        buttons.amap { button ->
            val playUrl = button.attr("data-play-url").trim()
            val source = button.attr("data-provider-name")
            val language = button.attr("data-language-label")
            if (playUrl.isEmpty()) return@amap

            val streamUrl = fixUrl(playUrl)
            val finalUrl = try {
                val resp = app.get(streamUrl)
                resp.url
            } catch (e: Exception) { streamUrl }

            loadExtractor(finalUrl, data, subtitleCallback) { link ->
                val fixedLink = runBlocking {
                    newExtractorLink(
                        source = source,
                        name = "$source [$language]",
                        url = link.url
                    ) {
                        referer = link.referer
                        quality = link.quality
                        type = link.type
                        headers = link.headers
                        extractorData = link.extractorData
                    }
                }
                callback.invoke(fixedLink)
            }
        }
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveEmbedViaWebView(episodeUrl: String): String? {
        val capturedUrls = mutableListOf<String>()
        val embedFound = CompletableDeferred<String?>()

        Handler(Looper.getMainLooper()).post {
            try {
                val context = CloudStreamApp.context ?: return@post
                val webView = WebView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = USER_AGENT
                }

                val progress = ProgressBar(context)
                val header = TextView(context).apply {
                    text = "Bitte loese das Captcha, dann druecke zurueck."
                    setPadding(32, 24, 32, 8)
                    setTextColor(android.graphics.Color.WHITE)
                }

                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(header)
                    addView(progress)
                    addView(webView)
                }

                val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
                    .setView(container)
                    .setNegativeButton("Abbrechen") { d, _ ->
                        d.dismiss()
                        if (!embedFound.isCompleted) embedFound.complete(null)
                    }
                    .create()

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        progress.progress = newProgress
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                    }
                }

                webView.addJavascriptInterface(object : Any() {
                    @JavascriptInterface
                    fun onEmbedUrl(url: String) {
                        if (!embedFound.isCompleted) {
                            embedFound.complete(url)
                            Handler(Looper.getMainLooper()).post {
                                try { dialog.dismiss() } catch (_: Exception) {}
                                webView.destroy()
                            }
                        }
                    }
                }, "CSBridge")

                val monitorJs = """
                    (function() {
                        function checkIframe() {
                            var iframe = document.getElementById('player-iframe');
                            if (!iframe) { setTimeout(checkIframe, 1000); return; }
                            var obs = new MutationObserver(function() {
                                var src = iframe.src || '';
                                if (src && src !== '' && !src.includes('/r?t=') && !src.includes('/r?t%3D')) {
                                    CSBridge.onEmbedUrl(src);
                                }
                            });
                            obs.observe(iframe, { attributes: true, attributeFilter: ['src'] });
                            if (iframe.src && !iframe.src.includes('/r?t=') && !iframe.src.includes('/r?t%3D')) {
                                CSBridge.onEmbedUrl(iframe.src);
                            }
                        }
                        checkIframe();
                    })();
                """.trimIndent()

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(monitorJs, null)
                    }
                }

                webView.loadUrl(episodeUrl)
                dialog.show()

                dialog.setOnDismissListener {
                    if (!embedFound.isCompleted) embedFound.complete(null)
                    try { webView.destroy() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebView error: ${e.message}", e)
                if (!embedFound.isCompleted) embedFound.complete(null)
            }
        }

        val timeoutMs = 120_000L
        val startTime = System.currentTimeMillis()
        while (!embedFound.isCompleted && System.currentTimeMillis() - startTime < timeoutMs) {
            delay(500)
        }
        if (!embedFound.isCompleted) embedFound.complete(null)

        return try { embedFound.await() } catch (_: Exception) { null }
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
