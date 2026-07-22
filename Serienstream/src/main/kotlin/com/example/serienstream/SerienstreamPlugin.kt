package com.example.serienstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.Voe1

@CloudstreamPlugin
class SerienstreamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SerienstreamProvider())
        registerExtractorAPI(Voe1())
    }
}
