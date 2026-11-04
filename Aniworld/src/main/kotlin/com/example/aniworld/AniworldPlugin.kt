package com.example.aniworld

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
class AniworldPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AniworldProvider())
        registerExtractorAPI(Voe1())

        openSettings = { ctx ->
            fun dp(value: Int) = (value * ctx.resources.displayMetrics.density).toInt()
            val email = EditText(ctx).apply {
                id = View.generateViewId()
                hint = "Email"
                isFocusable = true
                isFocusableInTouchMode = true
                setText(getKey<String>(AniworldProvider.SETTING_EMAIL) ?: "")
                setSingleLine()
            }
            val password = EditText(ctx).apply {
                id = View.generateViewId()
                hint = "Passwort"
                isFocusable = true
                isFocusableInTouchMode = true
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                transformationMethod = PasswordTransformationMethod.getInstance()
                setText(getKey<String>(AniworldProvider.SETTING_PASSWORD) ?: "")
                setSingleLine()
            }
            val label = TextView(ctx).apply {
                text = "AniWorld.to\n${AniworldCoverStore.count()} Covers gespeichert\nLogin nur für Streams nötig"
                setPadding(0, dp(8), 0, dp(8))
            }
            val controls = mutableListOf<Button>()
            val sync = Button(ctx).apply {
                id = View.generateViewId()
                text = "Covers sync"
                setOnClickListener {
                    if (AniworldProvider.isCoverSyncRunning()) {
                        label.text = AniworldProvider.getCoverSyncStatus()
                        return@setOnClickListener
                    }
                    controls.forEach { it.isEnabled = false }
                    text = "Cover-Sync läuft …"
                    val handler = Handler(Looper.getMainLooper())
                    Thread {
                        val result = runCatching {
                            AniworldProvider.syncCovers { progress -> handler.post { label.text = progress } }
                        }
                        handler.post {
                            controls.forEach { it.isEnabled = true }
                            text = "Covers sync"
                            label.text = result.fold(
                                onSuccess = { "$it Covers gespeichert\nStartseite erneut öffnen." },
                                onFailure = { "Cover-Sync fehlgeschlagen: ${it.message}\n${AniworldCoverStore.count()} Covers gespeichert" }
                            )
                        }
                    }.start()
                }
            }
            val clear = Button(ctx).apply {
                id = View.generateViewId()
                text = "Covers leeren"
                setTextColor(Color.parseColor("#E53935"))
                setOnClickListener {
                    runCatching { AniworldProvider.clearCovers() }.fold(
                        onSuccess = { label.text = "Cover-Speicher geleert" },
                        onFailure = { Toast.makeText(ctx, it.message, Toast.LENGTH_LONG).show() }
                    )
                }
            }
            controls.addAll(listOf(sync, clear))
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), dp(8))
                addView(label)
                addView(email)
                addView(password)
                listOf(sync, clear).forEach {
                    addView(it, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                }
            }
            val fields = listOf(email, password, sync, clear)
            fields.forEachIndexed { index, view ->
                if (index > 0) view.nextFocusUpId = fields[index - 1].id
                if (index < fields.lastIndex) view.nextFocusDownId = fields[index + 1].id
            }
            val scroll = ScrollView(ctx).apply {
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                addView(layout)
            }
            val dialog = AlertDialog.Builder(ctx)
                .setTitle("AniWorld Einstellungen")
                .setView(scroll)
                .setPositiveButton("Speichern") { _, _ ->
                    setKey(AniworldProvider.SETTING_EMAIL, email.text.toString())
                    setKey(AniworldProvider.SETTING_PASSWORD, password.text.toString())
                }
                .setNegativeButton("Logout") { _, _ ->
                    setKey(AniworldProvider.SETTING_EMAIL, "")
                    setKey(AniworldProvider.SETTING_PASSWORD, "")
                }
                .setNeutralButton("Schließen", null)
                .create()
            dialog.show()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.id = View.generateViewId()
            clear.nextFocusDownId = save.id
            save.nextFocusUpId = clear.id
            email.nextFocusDownId = password.id
            password.nextFocusUpId = email.id
            password.nextFocusDownId = sync.id
            password.requestFocus()
        }
    }
}
