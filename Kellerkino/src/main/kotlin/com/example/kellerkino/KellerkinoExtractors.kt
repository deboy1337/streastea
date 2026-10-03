package com.example.kellerkino

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.LuluStream
import com.lagradost.cloudstream3.extractors.Vidara
import com.lagradost.cloudstream3.extractors.Voe
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class KaufmanVidara : Vidara() {
    override val mainUrl = "https://ano.cx"
}

class KaufmanVoe : Voe() {
    override val mainUrl = "https://jeremyparticipantanything.com"
}

class KaufmanLuluStream : ExtractorApi() {
    override val name = "LuluStream"
    override val mainUrl = "https://lulust.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer ?: mainUrl
        var html = app.get(url, referer = ref).text
        if (!html.contains("eval(function")) {
            app.get(mainUrl, referer = ref)
            html = app.get(url, referer = ref).text
        }
        val unpacked = unpackPacked(html) ?: return
        val m3u8 = m3u8Regex.find(unpacked)?.value ?: return
        M3u8Helper.generateM3u8(
            source = name,
            streamUrl = m3u8,
            referer = mainUrl,
            quality = null,
            headers = mapOf(),
            name = name
        ).forEach { callback.invoke(it) }
    }

    private fun unpackPacked(html: String?): String? {
        if (html == null) return null
        val open = packedOpen.find(html) ?: return null
        val rest = html.substring(open.range.last + 1)
        val args = packedArgs.find(rest) ?: return null
        val body = unescapeJs(args.groupValues[1])
        val base = args.groupValues[2].toIntOrNull() ?: 16
        val words = args.groupValues[4].split("|")
        var out = body
        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        for (idx in words.indices.reversed()) {
            val word = words[idx]
            if (word.isEmpty()) continue
            var n = idx
            val key = if (n == 0) "0" else {
                val sb = StringBuilder()
                while (n > 0) {
                    sb.insert(0, digits[n % base])
                    n /= base
                }
                sb.toString()
            }
            out = Regex("(?<![0-9a-zA-Z])" + Regex.escape(key) + "(?![0-9a-zA-Z])").replace(out) { word }
        }
        return out
    }

    private fun unescapeJs(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    'x' -> {
                        if (i + 3 < s.length) {
                            append(s.substring(i + 2, i + 4).toIntOrNull(16)?.toChar() ?: next)
                            i += 4
                        } else i += 2
                    }
                    'u' -> {
                        if (i + 5 < s.length) {
                            append(s.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: next)
                            i += 6
                        } else i += 2
                    }
                    'n' -> { append('\n'); i += 2 }
                    't' -> { append('\t'); i += 2 }
                    'r' -> { append('\r'); i += 2 }
                    '0' -> { append('\u0000'); i += 2 }
                    else -> { append(next); i += 2 }
                }
            } else {
                append(c)
                i += 1
            }
        }
    }

    private companion object {
        val packedOpen = Regex("""eval\(function\(p,a,c,k,e,d\).*?\}\('""", RegexOption.DOT_MATCHES_ALL)
        val packedArgs = Regex("""((?:\\.|[^'\\])*)','?(\d+)'?,(\d+),'((?:\\.|[^'\\])*)'\.split\('\|'\)""")
        val m3u8Regex = Regex("""https?[^"'\s<>,]+\.m3u8[^"'\s<>,]*""")
    }
}