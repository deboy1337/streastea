package com.example.serienstream

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.extractors.Voe1
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SerienstreamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SerienstreamProvider())
        registerExtractorAPI(Voe1())

        openSettings = { ctx ->
            fun dp(value: Int) = (value * ctx.resources.displayMetrics.density).toInt()
            val emailInput = EditText(ctx).apply {
                id = View.generateViewId()
                hint = "Email"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                setText(getKey<String>(SerienstreamProvider.SETTING_EMAIL) ?: "")
                setSingleLine()
            }
            val passwordInput = EditText(ctx).apply {
                id = View.generateViewId()
                hint = "Passwort"
                setText(getKey<String>(SerienstreamProvider.SETTING_PASSWORD) ?: "")
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

            val label = TextView(ctx).apply {
                text = "Serienstream.to Login\n${SerienstreamCoverStore.count()} Covers auf diesem Gerät gespeichert"
                setPadding(0, dp(8), 0, dp(8))
            }

            val coverControls = mutableListOf<Button>()
            val syncBtn = Button(ctx).apply {
                id = View.generateViewId()
                text = "Covers sync"
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener {
                    if (SerienstreamProvider.isCoverSyncRunning()) {
                        label.text = SerienstreamProvider.getCoverSyncStatus()
                        return@setOnClickListener
                    }
                    setKey(SerienstreamProvider.SETTING_EMAIL, emailInput.text.toString())
                    setKey(SerienstreamProvider.SETTING_PASSWORD, passwordInput.text.toString())
                    coverControls.forEach { it.isEnabled = false }
                    text = "Cover-Sync läuft …"
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    Thread {
                        val result = runCatching {
                            SerienstreamProvider.syncGenrePosters { progress ->
                                handler.post { label.text = progress }
                            }
                        }
                        handler.post {
                            coverControls.forEach { it.isEnabled = true }
                            text = "Covers sync"
                            label.text = result.fold(
                                onSuccess = { "$it Covers auf diesem Gerät gespeichert\nStartseite erneut öffnen, um sie anzuzeigen." },
                                onFailure = { "Cover-Sync fehlgeschlagen: ${it.message}\n${SerienstreamCoverStore.count()} Covers gespeichert" }
                            )
                            Toast.makeText(ctx, result.fold(
                                onSuccess = { "$it Covers gespeichert" },
                                onFailure = { "Cover-Sync fehlgeschlagen: ${it.message}" }
                            ), Toast.LENGTH_LONG).show()
                        }
                    }.start()
                }
            }

            val clearBtn = Button(ctx).apply {
                id = View.generateViewId()
                text = "Covers leeren"
                setTextColor(Color.parseColor("#E53935"))
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener {
                    runCatching { SerienstreamProvider.clearCovers() }.fold(
                        onSuccess = {
                            label.text = "Cover-Speicher geleert"
                            Toast.makeText(ctx, "Covers gelöscht!", Toast.LENGTH_LONG).show()
                        },
                        onFailure = { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
                    )
                }
            }
            coverControls.addAll(listOf(syncBtn, clearBtn))
            val syncStatusHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val syncStatusPoll = object : Runnable {
                private var wasRunning = false
                override fun run() {
                    val running = SerienstreamProvider.isCoverSyncRunning()
                    if (running) {
                        label.text = SerienstreamProvider.getCoverSyncStatus()
                        syncBtn.text = "Cover-Sync läuft …"
                        coverControls.forEach { it.isEnabled = false }
                    } else if (wasRunning) {
                        label.text = "${SerienstreamProvider.getCoverSyncStatus()}\n${SerienstreamCoverStore.count()} Covers gespeichert"
                        syncBtn.text = "Covers sync"
                        coverControls.forEach { it.isEnabled = true }
                    }
                    wasRunning = running
                    syncStatusHandler.postDelayed(this, 500)
                }
            }

            val captchaBtn = Button(ctx).apply {
                id = View.generateViewId()
                text = "Captcha lösen"
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener {
                    val url = SerienstreamProvider.getCaptchaUrl()
                    if (url == null) {
                        Toast.makeText(ctx, "Kein Captcha erforderlich", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val cookieManager = android.webkit.CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    val cookies = SerienstreamProvider.retrieveSessionCookies()
                    if (cookies.isNotEmpty()) {
                        for (pair in cookies.split("; ")) {
                            val eq = pair.indexOf('=')
                            if (eq > 0) {
                                val name = pair.substring(0, eq)
                                val value = pair.substring(eq + 1)
                                cookieManager.setCookie("https://serienstream.to", "$name=$value; Domain=.serienstream.to; Path=/")
                            }
                        }
                        cookieManager.flush()
                    }

                    var webViewDestroyed = false

                    val webView = WebView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        isFocusable = true
                    }

                    webView.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onClick(x: Float, y: Float) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                val now = SystemClock.uptimeMillis()
                                val d = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                                d.setSource(InputDevice.SOURCE_MOUSE)
                                webView.dispatchTouchEvent(d)
                                d.recycle()
                                val u = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0)
                                u.setSource(InputDevice.SOURCE_MOUSE)
                                webView.dispatchTouchEvent(u)
                                u.recycle()
                            }
                        }
                        @JavascriptInterface
                        fun onCaptchaSolved() {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (webViewDestroyed) return@post
                                val scrapeJs = """
                                    JSON.stringify(Array.from(document.querySelectorAll('button.link-box[data-play-url]')).map(b => [
                                        b.getAttribute('data-play-url'),
                                        b.getAttribute('data-provider-name'),
                                        b.getAttribute('data-language-label')
                                    ]))
                                """.trimIndent()
                                webView.evaluateJavascript(scrapeJs) { json ->
                                    if (json != null && json != "null" && json != "[]") {
                                        try {
                                            val arr = org.json.JSONArray(if (json.startsWith("\"")) org.json.JSONArray(json.substring(1, json.length - 1)) else json)
                                            val hosters = mutableListOf<List<String>>()
                                            for (i in 0 until arr.length()) {
                                                val item = arr.getJSONArray(i)
                                                hosters.add(listOf(item.getString(0), item.getString(1), item.getString(2)))
                                            }
                                            SerienstreamProvider.setCachedHosters(hosters)
                                            Log.i("Serienstream", "Scraped ${hosters.size} hosters from WebView")
                                        } catch (_: Exception) {}
                                    }
                                    val updated = cookieManager.getCookie("https://serienstream.to")
                                    if (updated != null) SerienstreamProvider.updateSessionCookies(updated)
                                    SerienstreamProvider.clearCaptchaUrl()
                                    val count = SerienstreamProvider.getCachedHosters().size
                                    Toast.makeText(ctx, "$count Hoster gefunden – jetzt nochmal laden", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }, "Android")

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript("""
                                (function(){
                                    var c = document.createElement('div');
                                    c.id = '__mc__';
                                    c.style.cssText = 'position:fixed;z-index:99999;pointer-events:none;width:20px;height:20px;border:2px solid #ff4444;border-radius:50%;background:rgba(255,68,68,0.3);transform:translate(-50%,-50%);left:50%;top:50%';
                                    document.body.appendChild(c);
                                    var h = document.createElement('div');
                                    h.id = '__mc_hint__';
                                    h.style.cssText = 'position:fixed;bottom:10px;left:50%;transform:translateX(-50%);z-index:99999;background:rgba(0,0,0,0.8);color:#fff;padding:8px 16px;border-radius:8px;font-size:14px;text-align:center;pointer-events:none;font-family:sans-serif';
                                    h.textContent = '\u25B2\u25BC\u25C0\u25B6 = Cursor | OK = Klicken | BACK = Zur\u00fcck';
                                    document.body.appendChild(h);
                                    var cx = window.innerWidth / 2, cy = window.innerHeight / 2;
                                    function updateC() {
                                        var e = document.getElementById('__mc__');
                                        if (e) { e.style.left = cx + 'px'; e.style.top = cy + 'px'; }
                                    }
                                    document.addEventListener('keydown', function(e) {
                                        var k = e.keyCode || e.which, h2 = true;
                                        switch(k) {
                                            case 19: case 38: cy = Math.max(0, cy - 25); break;
                                            case 20: case 40: cy = Math.min(window.innerHeight, cy + 25); break;
                                            case 21: case 37: cx = Math.max(0, cx - 25); break;
                                            case 22: case 39: cx = Math.min(window.innerWidth, cx + 25); break;
                                            case 23: case 13: case 32:
                                                clickTurnstile();
                                                break;
                                            case 4: break;
                                            default: h2 = false;
                                        }
                                        if (h2) { e.preventDefault(); e.stopPropagation(); updateC(); }
                                    }, true);
                                    function clickTurnstile() {
                                        var frames = document.querySelectorAll('iframe');
                                        var ts = null;
                                        for (var i = 0; i < frames.length; i++) {
                                            if (frames[i].src && frames[i].src.indexOf('challenges.cloudflare.com') > -1) {
                                                ts = frames[i]; break;
                                            }
                                        }
                                        if (ts) {
                                            var r = ts.getBoundingClientRect();
                                            var spots = [
                                                {x: r.left + 40, y: r.top + 35},
                                                {x: r.left + 50, y: r.top + 45},
                                                {x: r.left + 30, y: r.top + 40},
                                                {x: r.left + r.width/2, y: r.top + r.height/2},
                                                {x: r.left + 60, y: r.top + 55},
                                                {x: r.left + 35, y: r.top + 30},
                                                {x: r.left + 45, y: r.top + 50},
                                            ];
                                            spots.forEach(function(s) { Android.onClick(s.x, s.y); });
                                            return true;
                                        }
                                        Android.onClick(cx, cy);
                                        var el = document.elementFromPoint(cx, cy);
                                        if (el) el.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy}));
                                        return false;
                                    }
                                    setInterval(function() {
                                        var frames = document.querySelectorAll('iframe');
                                        for (var i = 0; i < frames.length; i++) {
                                            if (frames[i].src && frames[i].src.indexOf('challenges.cloudflare.com') > -1) {
                                                var r = frames[i].getBoundingClientRect();
                                                if (r.width > 0 && r.height > 0) {
                                                    cx = r.left + 40; cy = r.top + 35;
                                                    updateC();
                                                }
                                                break;
                                            }
                                        }
                                    }, 1000);
                                    var observer = new MutationObserver(function() {
                                        var gate = document.querySelector('[data-redirect-gate-tier]');
                                        if (!gate || gate.offsetParent === null || gate.style.display === 'none') {
                                            observer.disconnect();
                                            Android.onCaptchaSolved();
                                        }
                                    });
                                    var gate = document.querySelector('[data-redirect-gate-tier]');
                                    if (gate) {
                                        observer.observe(gate.parentNode || document.body, {childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class']});
                                    } else {
                                        Android.onCaptchaSolved();
                                    }
                                    updateC();
                                })();
                            """.trimIndent(), null)
                        }
                    }

                    webView.loadUrl(url)

                    val dialog = AlertDialog.Builder(ctx)
                        .setView(webView)
                        .setCancelable(true)
                        .create()

                    dialog.setOnDismissListener {
                        val updated = cookieManager.getCookie("https://serienstream.to")
                        if (updated != null) SerienstreamProvider.updateSessionCookies(updated)
                        if (!webViewDestroyed) {
                            webViewDestroyed = true
                            webView.destroy()
                        }
                    }

                    dialog.show()
                    dialog.window?.let { w ->
                        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        w.setGravity(Gravity.CENTER)
                    }

                    webView.requestFocus()
                }
            }

            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), dp(8))
                addView(label)
                addView(emailInput)
                addView(passwordInput)
                listOf(syncBtn, clearBtn, captchaBtn).forEach { button ->
                    addView(button, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                }
            }

            val controls = listOf(emailInput, passwordInput, syncBtn, clearBtn, captchaBtn)
            controls.forEachIndexed { index, view ->
                if (index > 0) view.nextFocusUpId = controls[index - 1].id
                if (index < controls.lastIndex) view.nextFocusDownId = controls[index + 1].id
            }
            val scroll = object : ScrollView(ctx) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val maxHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
                    val available = View.MeasureSpec.getSize(heightMeasureSpec)
                    val height = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) {
                        maxHeight
                    } else minOf(maxHeight, available)
                    super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
                }
            }.apply {
                isFillViewport = true
                addView(layout)
            }
            val settingsDialog = AlertDialog.Builder(ctx)
                .setTitle("Serienstream Einstellungen")
                .setView(scroll)
                .setPositiveButton("Speichern") { _, _ ->
                    setKey(SerienstreamProvider.SETTING_EMAIL, emailInput.text.toString())
                    setKey(SerienstreamProvider.SETTING_PASSWORD, passwordInput.text.toString())
                }
                .setNegativeButton("Logout") { _, _ ->
                    setKey(SerienstreamProvider.SETTING_EMAIL, "")
                    setKey(SerienstreamProvider.SETTING_PASSWORD, "")
                }
                .setNeutralButton("Schließen", null)
                .create()
            settingsDialog.show()
            settingsDialog.setOnDismissListener { syncStatusHandler.removeCallbacks(syncStatusPoll) }
            syncStatusHandler.post(syncStatusPoll)
            settingsDialog.window?.apply {
                setLayout(minOf(dp(640), (ctx.resources.displayMetrics.widthPixels * 0.95).toInt()),
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            }
            val save = settingsDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.id = View.generateViewId()
            captchaBtn.nextFocusDownId = save.id
            save.nextFocusUpId = captchaBtn.id
            syncBtn.requestFocus()
        }
    }
}
