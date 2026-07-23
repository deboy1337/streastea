package com.example.serienstream

import android.content.Context
import android.graphics.Color
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.Voe1
import android.app.AlertDialog
import android.widget.Toast
import java.net.URL
import java.net.URLEncoder

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
                    Thread {
                        try {
                            val encoded = URLEncoder.encode(url, "UTF-8")
                            val qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=$encoded"
                            val bitmap = android.graphics.BitmapFactory.decodeStream(URL(qrUrl).openStream())
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                val hint = TextView(ctx).apply {
                                    text = "Scanne den QR-Code mit dem Smartphone oder öffne den Link im Browser. Löse dort das Captcha (Turnstile) und komme dann zurück."
                                    setPadding(16, 8, 16, 8)
                                    textSize = 14f
                                }
                                val imgView = ImageView(ctx).apply {
                                    setImageBitmap(bitmap)
                                    setPadding(16, 16, 16, 16)
                                }
                                val urlText = TextView(ctx).apply {
                                    text = url
                                    setPadding(16, 0, 16, 16)
                                    setTextIsSelectable(true)
                                }
                                val innerLayout = LinearLayout(ctx).apply {
                                    orientation = LinearLayout.VERTICAL
                                    addView(hint)
                                    addView(imgView)
                                    addView(urlText)
                                }
                                AlertDialog.Builder(ctx)
                                    .setTitle("Captcha lösen")
                                    .setView(innerLayout)
                                    .setPositiveButton("Erledigt") { _, _ ->
                                        SerienstreamProvider.clearCaptchaUrl()
                                        Toast.makeText(ctx, "Captcha-URL gelöscht – jetzt nochmal laden", Toast.LENGTH_LONG).show()
                                    }
                                    .show()
                            }
                        } catch (e: Exception) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(ctx, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
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
