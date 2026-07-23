package com.example.serienstream

import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.Voe1
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.runBlocking

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
            val tmdbInput = EditText(ctx).apply {
                hint = "TMDB API Key"
                setText(getKey<String>(SerienstreamProvider.SETTING_TMDB_KEY) ?: "")
                setSingleLine()
            }

            val label = TextView(ctx).apply {
                text = "Serienstream.to Login"
                setPadding(48, 32, 48, 8)
            }

            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 16, 48, 16)
                addView(label)
                addView(emailInput)
                addView(passwordInput)
                addView(tmdbInput)
            }

            AlertDialog.Builder(ctx)
                .setTitle("Serienstream Einstellungen")
                .setView(layout)
                .setPositiveButton("Speichern") { _, _ ->
                    setKey(SerienstreamProvider.SETTING_EMAIL, emailInput.text.toString())
                    setKey(SerienstreamProvider.SETTING_PASSWORD, passwordInput.text.toString())
                    setKey(SerienstreamProvider.SETTING_TMDB_KEY, tmdbInput.text.toString())
                }
                .setNegativeButton("Logout") { _, _ ->
                    setKey(SerienstreamProvider.SETTING_EMAIL, "")
                    setKey(SerienstreamProvider.SETTING_PASSWORD, "")
                }
                .setNeutralButton("Covers sync") { _, _ ->
                    val key = tmdbInput.text.toString().trim()
                    if (key.isBlank()) {
                        Toast.makeText(ctx, "Bitte TMDB API-Key eingeben", Toast.LENGTH_LONG).show()
                        return@setNeutralButton
                    }
                    setKey(SerienstreamProvider.SETTING_EMAIL, emailInput.text.toString())
                    setKey(SerienstreamProvider.SETTING_PASSWORD, passwordInput.text.toString())
                    setKey(SerienstreamProvider.SETTING_TMDB_KEY, key)
                    Thread {
                        runBlocking {
                            val ok = SerienstreamProvider().testTmdbKey(key)
                            Handler(Looper.getMainLooper()).post {
                                if (ok) {
                                    setKey(SerienstreamProvider.SETTING_SYNC_REQUESTED, "true")
                                    Toast.makeText(ctx, "TMDB-Key gültig! Sync startet beim nächsten Laden", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(ctx, "TMDB-Key ungültig!", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }.start()
                }
                .show()
        }
    }
}
