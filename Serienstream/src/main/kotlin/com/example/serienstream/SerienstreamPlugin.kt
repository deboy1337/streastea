package com.example.serienstream

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.Voe1
import android.app.AlertDialog
import android.widget.Toast

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
                    val heightPx = (500 * ctx.resources.displayMetrics.density).toInt()
                    val webView = android.webkit.WebView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            heightPx
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    webView.loadUrl(url)

                    val dialog = AlertDialog.Builder(ctx)
                        .setTitle("Captcha lösen (Turnstile)")
                        .setView(webView)
                        .setPositiveButton("Erledigt", null)
                        .setNegativeButton("Abbrechen") { _, _ ->
                            webViewDestroyed = true
                            webView.destroy()
                        }
                        .create()

                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val js = """
                                JSON.stringify(Array.from(document.querySelectorAll('button.link-box[data-play-url]')).map(b => [
                                    b.getAttribute('data-play-url'),
                                    b.getAttribute('data-provider-name'),
                                    b.getAttribute('data-language-label')
                                ]))
                            """.trimIndent()
                            webView.evaluateJavascript(js) { json ->
                                if (json != null && json != "null" && json != "[]" && json != "\"[]\"") {
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
                                if (updated != null) {
                                    SerienstreamProvider.updateSessionCookies(updated)
                                }
                                SerienstreamProvider.clearCaptchaUrl()
                                dialog.dismiss()
                                webViewDestroyed = true
                                webView.destroy()
                                val count = SerienstreamProvider.getCachedHosters().size
                                Toast.makeText(ctx, "$count Hoster gefunden – jetzt nochmal laden", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    dialog.setOnDismissListener {
                        val updated = cookieManager.getCookie("https://serienstream.to")
                        if (updated != null) {
                            SerienstreamProvider.updateSessionCookies(updated)
                        }
                        if (!webViewDestroyed) {
                            webView.destroy()
                        }
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
