package com.example.kellerkino

import android.content.Context
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class KellerkinoPlugin : Plugin() {
    override fun load(context: Context) {
        val provider = KellerkinoProvider()
        registerMainAPI(provider)
        registerExtractorAPI(KaufmanVidara())
        registerExtractorAPI(KaufmanVoe())
        registerExtractorAPI(KaufmanLuluStream())

        openSettings = { ctx ->
            val status = TextView(ctx).apply {
                text = "${KellerkinoCoverStore.count()} Covers auf diesem Gerät gespeichert"
            }
            val sync = Button(ctx).apply { text = "Covers sync" }
            val clear = Button(ctx).apply { text = "Covers leeren" }
            val update = Button(ctx).apply { text = "Katalog aktualisieren" }
            update.setOnClickListener {
                update.isEnabled = false
                sync.isEnabled = false
                clear.isEnabled = false
                val handler = Handler(Looper.getMainLooper())
                Thread {
                    val result = runCatching {
                        runBlocking {
                            provider.updateCatalog { progress ->
                                handler.post { status.text = progress }
                            }
                        }
                    }
                    handler.post {
                        update.isEnabled = true
                        sync.isEnabled = true
                        clear.isEnabled = true
                        status.text = result.fold(
                            onSuccess = { "$it Filme gespeichert. Kellerkino-Startseite erneut öffnen." },
                            onFailure = { "Aktualisierung fehlgeschlagen: ${it.message}" }
                        )
                    }
                }.start()
            }
            sync.setOnClickListener {
                update.isEnabled = false
                sync.isEnabled = false
                clear.isEnabled = false
                status.text = "Covers werden über alle Archivseiten synchronisiert …"
                Thread {
                    val result = runCatching { runBlocking { provider.syncCovers() } }
                    Handler(Looper.getMainLooper()).post {
                        update.isEnabled = true
                        sync.isEnabled = true
                        clear.isEnabled = true
                        status.text = "${KellerkinoCoverStore.count()} Covers auf diesem Gerät gespeichert"
                        Toast.makeText(ctx, result.fold(
                            onSuccess = { "$it Covers gespeichert" },
                            onFailure = { "Cover-Sync fehlgeschlagen: ${it.message}" }
                        ), Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            clear.setOnClickListener {
                provider.clearCovers()
                status.text = "Cover-Speicher geleert"
            }
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 24, 48, 24)
                addView(status)
                addView(update)
                addView(sync)
                addView(clear)
            }
            AlertDialog.Builder(ctx)
                .setTitle("Kellerkino Einstellungen")
                .setView(layout)
                .setPositiveButton("Schließen", null)
                .show()
        }
    }
}
