package com.example.kellerkino

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KellerkinoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KellerkinoProvider())
        registerExtractorAPI(KaufmanVidara())
        registerExtractorAPI(KaufmanVoe())
        registerExtractorAPI(KaufmanLuluStream())
    }
}