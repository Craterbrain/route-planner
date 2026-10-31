package app.routeplanner.core.google

import app.routeplanner.core.HttpGet
import app.routeplanner.core.Json
import app.routeplanner.core.LatLng
import app.routeplanner.core.Suggestion
import app.routeplanner.core.SuggestSource
import app.routeplanner.core.SuggestionProvider
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google Maps' search-as-you-type suggestions, as the logged-out Maps web page
 * requests them. UNOFFICIAL: no API key, not a documented API, against Google's
 * terms, and it can change or stop at any time. The user enabled it knowingly.
 *
 * Every constant Google could break lives in [GoogleWeb], so a format change is
 * a one-file app update. Nothing here logs the typed text.
 */
object GoogleWeb {
    /** Autocomplete endpoint (verified by hand 2026-10-31: plain GET, no cookies or consent needed from a US IP). */
    const val SUGGEST_URL = "https://www.google.com/s?tbm=map&gs_ri=maps&suggest=p&authuser=0&hl=en&gl=us"

    /**
     * Viewport block the web page sends: span (m), center lng/lat, window size, then
     * the page's fixed flags. Window is a plain common desktop size, the same for every install.
     */
    const val SUGGEST_PB = "!2i5!4m12!1m3!1d{SPAN}!2d{LNG}!3d{LAT}!2m3!1f0!2f0!3f0!3m2!1i1920!2i945!4f13.1" +
        "!7i20!10b1!12m6!1m2!18b1!30b1!2m2!1i203!2i100!19m4!1m3!1i1!2i1!3i1!20m1!1e1"

    /** Bias window around the start; clamped so a long span doesn't drown nearby houses. */
    const val SUGGEST_SPAN_M = 40_000
    const val REFERER = "https://www.google.com/maps"

    /**
     * Chrome major claimed when the Chromium network stack (Cronet) is not available,
     * i.e. on the plain-HTTP fallback. With Cronet, the engine's own major is used so
     * the headers and the TLS/HTTP2 behavior agree. Bump this line on Chrome releases.
     */
    const val FALLBACK_CHROME_MAJOR = 141

    fun userAgent(major: Int) =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"

    /**
     * The Sec-CH-UA brand list Chrome sends for [major]: Chromium, Google Chrome and a
     * "GREASE" brand whose punctuation, version and position follow from the major
     * (Chromium's user_agent_utils algorithm).
     */
    fun secChUa(major: Int): String {
        val grease = listOf(" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_")
        val greaseVersions = listOf("8", "99", "24")
        val orders = listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))
        val greased = "\"Not${grease[major % 11]}A${grease[(major + 1) % 11]}Brand\";v=\"${greaseVersions[major % 3]}\""
        val order = orders[major % 6]
        val slots = arrayOfNulls<String>(3)
        slots[order[0]] = greased
        slots[order[1]] = "\"Chromium\";v=\"$major\""
        slots[order[2]] = "\"Google Chrome\";v=\"$major\""
        return slots.joinToString(", ")
    }

    /** Headers of a same-origin fetch from the Maps page in desktop Chrome on Windows. */
    fun headers(major: Int, acceptLanguage: String): Map<String, String> = linkedMapOf(
        "User-Agent" to userAgent(major),
        "Sec-CH-UA" to secChUa(major),
        "Sec-CH-UA-Mobile" to "?0",
        "Sec-CH-UA-Platform" to "\"Windows\"",
        "Accept" to "*/*",
        "Accept-Language" to acceptLanguage,
        "Referer" to REFERER,
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
    )

    /** "en-US,en;q=0.9" from a BCP-47 tag. */
    fun acceptLanguage(tag: String): String {
        val lang = tag.substringBefore('-')
        return if (lang == tag || lang.isEmpty()) "$tag,en;q=0.8" else "$tag,$lang;q=0.9"
    }

    /** Chrome major from a Cronet version string such as "Cronet/141.0.7340.3@abc" or "141.0.7340.3". */
    fun majorFrom(version: String?): Int? =
        version?.let { Regex("(\\d{2,3})\\.\\d+\\.\\d+").find(it)?.groupValues?.get(1)?.toIntOrNull() }
}

/** Thrown when the reply doesn't have the expected shape (counts toward the circuit breaker). */
class UnparseableReply(msg: String) : Exception(msg)

class GoogleSuggest(
    private val http: HttpGet,
    /** Chrome major to claim (Cronet's own, or [GoogleWeb.FALLBACK_CHROME_MAJOR]). */
    private val chromeMajor: () -> Int,
    private val acceptLanguage: String = "en-US,en;q=0.9",
) : SuggestionProvider {
    override val source = SuggestSource.GOOGLE

    /** Counts up per request, as the web page's `ech` does. */
    private val ech = AtomicInteger(0)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun url(query: String, near: LatLng): String {
        val pb = GoogleWeb.SUGGEST_PB.replace("{SPAN}", GoogleWeb.SUGGEST_SPAN_M.toString())
            .replace("{LNG}", near.lng.toString()).replace("{LAT}", near.lat.toString())
        return "${GoogleWeb.SUGGEST_URL}&pb=${enc(pb)}&q=${enc(query.trim())}&tch=1&ech=${ech.incrementAndGet()}"
    }

    override fun suggest(query: String, near: LatLng?, limit: Int): List<Suggestion> {
        // Without a bias point the answer is ranked for nowhere in particular; let the fallbacks answer.
        val at = near ?: return emptyList()
        val body = http.get(url(query, at), GoogleWeb.headers(chromeMajor(), acceptLanguage))
        return GoogleSuggestParser.parse(body).take(limit)
    }
}

/**
 * Reads the autocomplete reply: a `{"c":0,"d":"…"}` envelope (often followed by a
 * comment and a second envelope) whose `d` string is `)]}'` + newline + a nested
 * array. Rows are arrays of nulls with their content in one column; the parser
 * searches for that column and for the coordinate pair instead of trusting fixed
 * indexes, so a small shift degrades gracefully.
 */
object GoogleSuggestParser {
    /** End index (exclusive) of the first top-level JSON object in [s], skipping braces inside strings. */
    fun firstObjectEnd(s: String): Int {
        val start = s.indexOf('{')
        if (start < 0) return -1
        var depth = 0
        var inStr = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return -1
    }

    fun payload(body: String): List<*> {
        val end = firstObjectEnd(body)
        if (end < 0) throw UnparseableReply("no envelope")
        val env = runCatching { Json.parse(body.substring(body.indexOf('{'), end)) }.getOrNull() as? Map<*, *>
            ?: throw UnparseableReply("bad envelope")
        val d = env["d"] as? String ?: throw UnparseableReply("no d")
        val json = if (d.startsWith(")]}'")) d.substringAfter('\n') else d
        return runCatching { Json.parse(json) }.getOrNull() as? List<*> ?: throw UnparseableReply("bad payload")
    }

    /** The content column of a row: the first element that is a list whose first child is a list starting with a string. */
    private fun content(row: Any?): List<*>? = (row as? List<*>)?.firstOrNull { x ->
        val l = x as? List<*>
        l != null && l.isNotEmpty() && (l[0] as? List<*>)?.firstOrNull() is String
    } as? List<*>

    private fun isLatLng(x: Any?): LatLng? {
        val l = x as? List<*> ?: return null
        if (l.size < 4) return null
        val lat = l[2] as? Double ?: return null
        val lng = l[3] as? Double ?: return null
        return if (lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)) LatLng(lat, lng) else null
    }

    /** First `[null, null, lat, lng]` among the content's children, else one level deeper. */
    private fun findPoint(c: List<*>, depth: Int = 0): LatLng? {
        c.forEach { x -> isLatLng(x)?.let { return it } }
        if (depth < 3) c.forEach { x -> (x as? List<*>)?.let { l -> findPoint(l, depth + 1)?.let { return it } } }
        return null
    }

    /** The row list: a list where most elements have a content column (normally root[0][1]). */
    private fun findRows(root: List<*>, depth: Int = 0): List<*>? {
        val n = root.count { content(it) != null }
        if (n > 0 && n * 2 >= root.size) return root
        if (depth >= 3) return null
        root.forEach { x -> (x as? List<*>)?.let { findRows(it, depth + 1)?.let { r -> return r } } }
        return null
    }

    fun parse(body: String): List<Suggestion> {
        val root = payload(body)
        val rows = findRows(root) ?: return emptyList() // a valid reply with no rows
        return rows.mapNotNull { row ->
            val c = content(row) ?: return@mapNotNull null
            val full = (c[0] as List<*>)[0] as String
            val title = ((c.getOrNull(1) as? List<*>)?.firstOrNull() as? String)?.ifBlank { null } ?: full
            val subtitle = ((c.getOrNull(2) as? List<*>)?.firstOrNull() as? String).orEmpty()
            Suggestion(title, subtitle, findPoint(c), SuggestSource.GOOGLE)
        }
    }
}
