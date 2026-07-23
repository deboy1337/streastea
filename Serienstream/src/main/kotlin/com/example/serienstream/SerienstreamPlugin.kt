package com.example.serienstream

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
            val emailInput = EditText(ctx).apply {
                hint = "Email"
                setText(getKey<String>(SerienstreamProvider.SETTING_EMAIL) ?: "")
                setSingleLine()
            }
            val passwordInput = EditText(ctx).apply {
                hint = "Passwort"
                setText(getKey<String>(SerienstreamProvider.SETTING_PASSWORD) ?: "")
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

            val label = TextView(ctx).apply {
                text = "Serienstream.to Login"
                setPadding(48, 32, 48, 8)
            }

            val syncBtn = Button(ctx).apply {
                text = "Covers sync"
                setPadding(16, 8, 16, 8)
                setOnClickListener {
                    setKey(SerienstreamProvider.SETTING_EMAIL, emailInput.text.toString())
                    setKey(SerienstreamProvider.SETTING_PASSWORD, passwordInput.text.toString())
                    Thread {
                        SerienstreamProvider.syncGenrePosters()
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(ctx, "Covers Sync abgeschlossen!", Toast.LENGTH_LONG).show()
                        }
                    }.start()
                }
            }

            val clearBtn = Button(ctx).apply {
                text = "Covers leeren"
                setTextColor(Color.parseColor("#E53935"))
                setPadding(16, 8, 16, 8)
                setOnClickListener {
                    SerienstreamProvider.clearCovers()
                    Toast.makeText(ctx, "Covers gelöscht!", Toast.LENGTH_LONG).show()
                }
            }

            val captchaBtn = Button(ctx).apply {
                text = "Captcha lösen"
                setPadding(16, 8, 16, 8)
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
                                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                                webView.dispatchTouchEvent(down)
                                down.recycle()
                                val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
                                webView.dispatchTouchEvent(up)
                                up.recycle()
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
                                    var c = document.getElementById('__mc__');
                                    if (!c) {
                                        c = document.createElement('div');
                                        c.id = '__mc__';
                                        c.style.cssText = 'position:fixed;z-index:99999;pointer-events:none;width:20px;height:20px;border:2px solid #ff4444;border-radius:50%;background:rgba(255,68,68,0.3);transform:translate(-50%,-50%);left:50%;top:50%';
                                        document.body.appendChild(c);
                                    }
                                    var h = document.getElementById('__mc_hint__');
                                    if (!h) {
                                        h = document.createElement('div');
                                        h.id = '__mc_hint__';
                                        h.style.cssText = 'position:fixed;bottom:10px;left:50%;transform:translateX(-50%);z-index:99999;background:rgba(0,0,0,0.8);color:#fff;padding:8px 16px;border-radius:8px;font-size:14px;text-align:center;pointer-events:none;font-family:sans-serif';
                                        h.textContent = '\u25B2\u25BC\u25C0\u25B6 = Cursor | OK = Klicken | BACK = Zur\u00fcck';
                                        document.body.appendChild(h);
                                    }
                                    var cx = window.innerWidth / 2, cy = window.innerHeight / 2;
                                    function updatePos() {
                                        var el = document.getElementById('__mc__');
                                        if (el) { el.style.left = cx + 'px'; el.style.top = cy + 'px'; }
                                    }
                                    document.addEventListener('keydown', function(e) {
                                        var kc = e.keyCode || e.which;
                                        var handled = true;
                                        switch(kc) {
                                            case 19: case 38: cy = Math.max(0, cy - 25); break;
                                            case 20: case 40: cy = Math.min(window.innerHeight, cy + 25); break;
                                            case 21: case 37: cx = Math.max(0, cx - 25); break;
                                            case 22: case 39: cx = Math.min(window.innerWidth, cx + 25); break;
                                            case 23: case 13: case 32: Android.onClick(cx, cy); break;
                                            case 4: break;
                                            default: handled = false;
                                        }
                                        if (handled) { e.preventDefault(); e.stopPropagation(); updatePos(); }
                                    }, true);
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
                                    updatePos();
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

                    dialog.window?.let { w ->
                        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        w.setGravity(Gravity.CENTER)
                    }

                    dialog.show()
                }
            }

            val buttonRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(syncBtn)
                addView(clearBtn)
            }

            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 16, 48, 16)
                addView(label)
                addView(emailInput)
                addView(passwordInput)
                addView(buttonRow)
                addView(captchaBtn)
            }

            AlertDialog.Builder(ctx)
                .setTitle("Serienstream Einstellungen")
                .setView(layout)
                .setPositiveButton("Speichern") { _, _ ->
                    setKey(SerienstreamProvider.SETTING_EMAIL, emailInput.text.toString())
                    setKey(SerienstreamProvider.SETTING_PASSWORD, passwordInput.text.toString())
                }
                .setNegativeButton("Logout") { _, _ ->
                    setKey(SerienstreamProvider.SETTING_EMAIL, "")
                    setKey(SerienstreamProvider.SETTING_PASSWORD, "")
                }
                .show()
        }
    }
}
