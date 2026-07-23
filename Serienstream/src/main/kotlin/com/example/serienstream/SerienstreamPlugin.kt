package com.example.serienstream

import android.content.Context
import android.graphics.Color
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.DoodCxExtractor
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.DoodLiExtractor
import com.lagradost.cloudstream3.extractors.DoodPmExtractor
import com.lagradost.cloudstream3.extractors.DoodShExtractor
import com.lagradost.cloudstream3.extractors.DoodSoExtractor
import com.lagradost.cloudstream3.extractors.DoodToExtractor
import com.lagradost.cloudstream3.extractors.DoodWatchExtractor
import com.lagradost.cloudstream3.extractors.DoodWfExtractor
import com.lagradost.cloudstream3.extractors.DoodWsExtractor
import com.lagradost.cloudstream3.extractors.DoodYtExtractor
import com.lagradost.cloudstream3.extractors.DoodstreamCom
import com.lagradost.cloudstream3.extractors.Doodspro
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.Voe1
import android.app.AlertDialog
import android.widget.Toast

@CloudstreamPlugin
class SerienstreamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SerienstreamProvider())
        registerExtractorAPI(Voe1())
        registerExtractorAPI(DoodstreamCom())
        registerExtractorAPI(Doodspro())
        registerExtractorAPI(DoodCxExtractor())
        registerExtractorAPI(DoodLaExtractor())
        registerExtractorAPI(DoodLiExtractor())
        registerExtractorAPI(DoodPmExtractor())
        registerExtractorAPI(DoodShExtractor())
        registerExtractorAPI(DoodSoExtractor())
        registerExtractorAPI(DoodToExtractor())
        registerExtractorAPI(DoodWatchExtractor())
        registerExtractorAPI(DoodWfExtractor())
        registerExtractorAPI(DoodWsExtractor())
        registerExtractorAPI(DoodYtExtractor())
        registerExtractorAPI(StreamTape())

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

            val clearBtn = Button(ctx).apply {
                text = "Covers leeren"
                setTextColor(Color.parseColor("#E53935"))
                setPadding(16, 8, 16, 8)
                setOnClickListener {
                    SerienstreamProvider.clearCovers()
                    Toast.makeText(ctx, "Covers gelöscht!", Toast.LENGTH_LONG).show()
                }
            }

            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 16, 48, 16)
                addView(label)
                addView(emailInput)
                addView(passwordInput)
                addView(clearBtn)
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
                .setNeutralButton("Covers sync") { _, _ ->
                    setKey(SerienstreamProvider.SETTING_EMAIL, emailInput.text.toString())
                    setKey(SerienstreamProvider.SETTING_PASSWORD, passwordInput.text.toString())
                    Thread {
                        SerienstreamProvider.syncGenrePosters()
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(ctx, "Covers Sync abgeschlossen!", Toast.LENGTH_LONG).show()
                        }
                    }.start()
                }
                .show()
        }
    }
}
