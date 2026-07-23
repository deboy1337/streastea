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

                    var captchaDone = false
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
                        requestFocus()
                    }

                    var cursorX = 0f
                    var cursorY = 0f
                    val STEP = 25f

                    webView.post {
                        cursorX = webView.width / 2f
                        cursorY = webView.height / 2f
                    }

                    webView.setOnKeyListener { v, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP -> cursorY = (cursorY - STEP).coerceAtLeast(0f)
                                KeyEvent.KEYCODE_DPAD_DOWN -> cursorY = (cursorY + STEP).coerceAtMost(v.height.toFloat())
                                KeyEvent.KEYCODE_DPAD_LEFT -> cursorX = (cursorX - STEP).coerceAtLeast(0f)
                                KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX = (cursorX + STEP).coerceAtMost(v.width.toFloat())
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                    val now = SystemClock.uptimeMillis()
                                    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
                                    v.dispatchTouchEvent(down)
                                    down.recycle()
                                    val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
                                    v.dispatchTouchEvent(up)
                                    up.recycle()
                                    return@setOnKeyListener true
                                }
                                else -> return@setOnKeyListener false
                            }
                            val js = "document.getElementById('__mc__').style.left = '${cursorX}px'; document.getElementById('__mc__').style.top = '${cursorY}px'"
                            webView.evaluateJavascript(js, null)
                            return@setOnKeyListener true
                        }
                        false
                    }

                    webView.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onCaptchaSolved() {
                            captchaDone = true
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
                                })();
                            """.trimIndent(), null)
                        }
                    }

                    webView.loadUrl(url)

                    val dialog = AlertDialog.Builder(ctx)
                        .setTitle("Captcha lösen (Turnstile)")
                        .setView(webView)
                        .setNegativeButton("Abbrechen") { _, _ ->
                            webViewDestroyed = true
                            webView.destroy()
                        }
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
