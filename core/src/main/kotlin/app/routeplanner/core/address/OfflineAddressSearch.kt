package app.routeplanner.core.address

import app.routeplanner.core.LatLng
import app.routeplanner.core.Suggestion
import app.routeplanner.core.SuggestSource
import app.routeplanner.core.SuggestionProvider
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** A street group row (one per norm + city + postcode). Coordinates in microdegrees. */
data class StreetRow(
    val id: Long, val name: String, val norm: String,
    val city: String?, val pcity: String?, val postcode: String?,
    val lat: Int, val lon: Int,
)

/** An address point: leading-digit number, full number key, unit key, microdegrees. */
data class AddrPoint(val num: Int, val number: String, val unit: String, val lat: Int, val lon: Int)

/**
 * The queries the search needs from an address pack (docs/address-pack.md, schema 1).
 * Android implements it over read-only SQLite; tests use an in-memory fake.
 */
interface AddressStore {
    /** `norm >= from AND norm < to [AND postcode = postcode]`, in index (norm) order, at most [limit]. */
    fun streetsInRange(from: String, to: String, postcode: String?, limit: Int): List<StreetRow>
    /** Rows with this exact number on the street. */
    fun exact(streetId: Long, num: Int, number: String): List<AddrPoint>
    /** Highest num in [0, num) (same [parity] when given). */
    fun below(streetId: Long, num: Int, parity: Int?): AddrPoint?
    /** Lowest num above [num] (same [parity] when given). */
    fun above(streetId: Long, num: Int, parity: Int?): AddrPoint?
}

enum class MatchType(val rank: Int, val label: String) {
    EXACT(0, "exact"), INTERPOLATED(1, "interpolated"), NEAREST(2, "nearest-house"), STREET(3, "street"),
}

data class AddressHit(
    val type: MatchType,
    val lat: Double, val lon: Double,
    val label: String,
    val streetName: String,
    val number: String,
    val city: String?, val pcity: String?, val postcode: String?,
    val detail: String,
    val streetId: Long,
    val norm: String,
    val distM: Long?,
    internal val key: List<Double>,
)

/**
 * The offline address search, ported step for step from tools/query-address.py:
 * parse, word tries (dropping trailing words into locality), half-typed last
 * word, comma text as city/ZIP filter, exact / interpolated / nearest-house /
 * street layers inside one street group, and the ranking key.
 */
class OfflineAddressSearch(private val store: AddressStore) {
    companion object {
        const val MAX_CANDIDATE_STREETS = 300
        /** Within a match tier, results this close to the start rank first. */
        const val NEAR_BAND_M = 25_000.0
        private val UNIT_WORDS = setOf("apt", "apartment", "unit", "ste", "suite", "#", "lot", "rm", "room", "bldg", "trlr")
        private val STATE_WORDS = setOf("tn", "tennessee", "usa", "us")

        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * asin(sqrt(a))
        }

        fun nextPrefix(s: String) = s.substring(0, s.length - 1) + (s.last() + 1)

        private fun splitWs(s: String) = s.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
    }

    class Parsed(val house: AddrNorm.House, val words: List<String>, val locWords: List<String>, val postcode: String)

    fun parseQuery(text: String): Parsed {
        val comma = text.indexOf(',')
        val streetPart = if (comma < 0) text else text.substring(0, comma)
        val localityPart = if (comma < 0) "" else text.substring(comma + 1)
        var words = splitWs(streetPart.replace("#", " # ")).toMutableList()
        val loc = splitWs(localityPart.replace(",", " ")).toMutableList()

        var numberRaw = ""
        if (words.isNotEmpty() && words[0].first().isDigit()) { numberRaw = words[0]; words = words.drop(1).toMutableList() }

        var unitRaw = ""
        for (i in words.indices) {
            if (i > 0 && words[i].lowercase().trim('.') in UNIT_WORDS) {
                unitRaw = words.subList(i + 1, minOf(i + 2, words.size)).joinToString(" ")
                words = (words.subList(0, i) + words.subList(minOf(i + 2, words.size), words.size)).toMutableList()
                break
            }
        }
        val house = if (numberRaw.isNotEmpty()) AddrNorm.splitHouseNumber(numberRaw, unitRaw) else AddrNorm.House("", -1, "")

        var postcode = ""
        for (lst in listOf(loc, words)) {
            while (lst.isNotEmpty()) {
                val last = lst.last().lowercase().trim('.')
                if ((last.length == 5 || last.length == 10) && last.take(5).all { it.isDigit() } && postcode.isEmpty()) {
                    postcode = last.take(5); lst.removeAt(lst.size - 1)
                } else if (last in STATE_WORDS && (lst === loc || lst.size > 1)) {
                    lst.removeAt(lst.size - 1)
                } else break
            }
        }
        return Parsed(house, words, loc, postcode)
    }

    /** 2 = all query tokens whole, from the start; 1 = last one half-typed; 0 = no match. */
    fun wordMatch(q: List<String>, norm: List<String>): Int {
        if (q.size > norm.size) return 0
        for (i in q.indices) {
            if (norm[i] == q[i]) continue
            if (i == q.size - 1 && norm[i].startsWith(q[i])) return 1
            return 0
        }
        return 2
    }

    private fun localityOk(loc: List<String>, s: StreetRow): Boolean {
        if (loc.isEmpty()) return true
        val have = HashSet<String>()
        have += AddrNorm.normalizePlace(s.city ?: "")
        have += AddrNorm.normalizePlace(s.pcity ?: "")
        if (!s.postcode.isNullOrEmpty()) have += s.postcode
        for ((i, t) in loc.withIndex()) {
            if (t in have) continue
            if (i == loc.size - 1 && have.any { it.startsWith(t) }) continue
            return false
        }
        return true
    }

    private class Info(val q: String, val dir: String, val loc: List<String>, val localityOk: Boolean)

    private fun findStreets(words: List<String>, locWords: List<String>, postcode: String): Pair<List<StreetRow>, Info?> {
        val locTyped = AddrNorm.normalizePlace(locWords.joinToString(" "))
        var fallback: Pair<List<StreetRow>, Info>? = null
        for (k in words.size downTo 1) {
            val q = AddrNorm.normalizeStreet(words.subList(0, k).joinToString(" "))
            if (q.isEmpty()) continue
            var qt = q.split(' ')
            var wantDir = ""
            if (qt.size > 1 && qt.last() in AddrNorm.DIRS) { wantDir = qt.last(); qt = qt.dropLast(1) }
            val qs = qt.joinToString(" ")
            val rows = store.streetsInRange(qs, nextPrefix(qs), postcode.ifEmpty { null }, 5000)
                .filter { wordMatch(qt, it.norm.split(' ')) > 0 }
            if (rows.isEmpty()) continue
            val loc = locTyped + AddrNorm.normalizePlace(words.subList(k, words.size).joinToString(" "))
            val good = rows.filter { localityOk(loc, it) }
            if (good.isNotEmpty()) return good to Info(qs, wantDir, loc, true)
            if (fallback == null) fallback = rows to Info(qs, wantDir, loc, loc.isEmpty())
        }
        return fallback ?: (emptyList<StreetRow>() to null)
    }

    private class NumberHit(val type: MatchType, val lat: Double, val lon: Double, val detail: String)

    /** Layers 1-3 inside ONE street group: exact, interpolated (same parity first), nearest mapped house. */
    private fun lookupNumber(streetId: Long, h: AddrNorm.House): NumberHit? {
        if (h.num < 0) return null
        val rows = store.exact(streetId, h.num, h.number)
        if (rows.isNotEmpty()) {
            for (want in (if (h.unit.isNotEmpty()) listOf(h.unit) else emptyList()) + "") {
                rows.firstOrNull { it.unit == want }?.let { r ->
                    return NumberHit(MatchType.EXACT, r.lat / 1e6, r.lon / 1e6, h.number + if (r.unit.isNotEmpty()) " #" + r.unit else "")
                }
            }
            val lat = rows.sumOf { it.lat.toLong() }.toDouble() / rows.size
            val lon = rows.sumOf { it.lon.toLong() }.toDouble() / rows.size
            return NumberHit(MatchType.EXACT, lat / 1e6, lon / 1e6, "${h.number}, centroid of ${rows.size} units")
        }
        var lo = store.below(streetId, h.num, h.num % 2)
        var hi = store.above(streetId, h.num, h.num % 2)
        if (lo == null || hi == null) {
            lo = lo ?: store.below(streetId, h.num, null)
            hi = hi ?: store.above(streetId, h.num, null)
        }
        if (lo != null && hi != null) {
            val f = (h.num - lo.num).toDouble() / (hi.num - lo.num)
            val lat = lo.lat + f * (hi.lat - lo.lat)
            val lon = lo.lon + f * (hi.lon - lo.lon)
            return NumberHit(MatchType.INTERPOLATED, lat / 1e6, lon / 1e6, "between ${lo.num} and ${hi.num}")
        }
        val one = lo ?: hi ?: return null
        return NumberHit(MatchType.NEAREST, one.lat / 1e6, one.lon / 1e6, "nearest mapped number ${one.num}")
    }

    private fun round6(v: Double) = (v * 1e6).roundToLong() / 1e6

    fun search(text: String, near: LatLng? = null, limit: Int = 5): List<AddressHit> {
        val p = parseQuery(text)
        val results = ArrayList(searchParsed(p.house, p.words, p.locWords, p.postcode, near))
        val w0 = p.words.firstOrNull()
        if (p.house.number.isNotEmpty() && p.words.size > 1 && w0 != null &&
            ((w0.length == 1 && w0[0].isLetter() && w0.lowercase() !in setOf("n", "s", "e", "w")) || '/' in w0)
        ) {
            val alt = AddrNorm.splitHouseNumber(p.house.number + " " + w0, p.house.unit)
            results += searchParsed(alt, p.words.drop(1), p.locWords, p.postcode, near)
        }
        val sorted = results.sortedWith { a, b -> compareKeys(a.key, b.key) }
        val seen = HashSet<Triple<Long, Double, Double>>()
        return sorted.filter { seen.add(Triple(it.streetId, it.lat, it.lon)) }.take(limit)
    }

    private fun compareKeys(a: List<Double>, b: List<Double>): Int {
        for (i in a.indices) { val c = a[i].compareTo(b[i]); if (c != 0) return c }
        return 0
    }

    private fun searchParsed(house: AddrNorm.House, words: List<String>, locWords: List<String>, postcode: String, near: LatLng?): List<AddressHit> {
        var (streets, info) = findStreets(words, locWords, postcode)
        if (streets.isEmpty() && postcode.isNotEmpty()) {
            val r = findStreets(words, locWords + postcode, "")
            streets = r.first; info = r.second
        }
        if (streets.isEmpty() || info == null) return emptyList()

        fun dist(lat: Double, lon: Double) = if (near != null) haversineM(near.lat, near.lng, lat, lon) else 0.0
        val qt = info.q.split(' ')
        val capped = (if (near != null) streets.sortedBy { dist(it.lat / 1e6, it.lon / 1e6) }
        else streets.sortedBy { -wordMatch(qt, it.norm.split(' ')) }).take(MAX_CANDIDATE_STREETS)

        return capped.map { s ->
            val hit = (if (house.number.isNotEmpty()) lookupNumber(s.id, house) else null)
                ?: NumberHit(MatchType.STREET, s.lat / 1e6, s.lon / 1e6, "street centroid")
            val st = s.norm.split(' ')
            val dirOk = info.dir.isEmpty() || info.dir in st
            val d = dist(hit.lat, hit.lon)
            // Match quality first: an exact house never loses to a nearby estimate. Within a
            // tier, results within NEAR_BAND_M of the start come first (same street and number
            // in another town), then street fit, then distance. A contradicting typed city/ZIP
            // (locality mismatch) ranks last.
            val band = if (d < NEAR_BAND_M) 0 else 1
            val key = listOf(
                if (info.localityOk) 0.0 else 1.0,
                hit.type.rank.toDouble(),
                band.toDouble(),
                if (dirOk) 0.0 else 1.0,
                (2 - wordMatch(qt, st)).toDouble(),
                st.size.toDouble(),
                d,
            )
            val label = ("${if (house.number.isNotEmpty()) house.number + " " else ""}${s.name}, " +
                "${s.city ?: ""} ${s.postcode ?: ""}").trim()
            AddressHit(hit.type, round6(hit.lat), round6(hit.lon), label, s.name, house.number,
                s.city, s.pcity, s.postcode, hit.detail, s.id, s.norm,
                if (near != null) Math.round(d) else null, key)
        }
    }
}

/**
 * The offline pack as a suggestion source. Ranks itself (the pack's own key), so
 * the chain doesn't re-sort it. Non-exact positions are flagged approximate.
 */
class OfflineAddressProvider(private val search: () -> OfflineAddressSearch?) : SuggestionProvider {
    override val source = SuggestSource.OFFLINE
    override val ranksItself = true

    override fun suggest(query: String, near: LatLng?, limit: Int): List<Suggestion> {
        val s = search() ?: return emptyList()
        return s.search(query, near, limit).map { toSuggestion(it) }
    }

    companion object {
        fun matchNote(h: AddressHit): String = when (h.type) {
            MatchType.EXACT -> ""
            MatchType.INTERPOLATED -> "Estimated position (${h.detail})"
            MatchType.NEAREST, MatchType.STREET -> "Estimated position, adjust pin"
        }

        fun toSuggestion(h: AddressHit): Suggestion {
            val title = (if (h.number.isNotEmpty()) h.number + " " else "") + h.streetName
            val place = listOfNotNull(h.city?.ifEmpty { null }, h.postcode?.ifEmpty { null }).joinToString(" ")
            val subtitle = listOf(place, matchNote(h)).filter { it.isNotEmpty() }.joinToString(" · ")
            return Suggestion(title, subtitle, LatLng(h.lat, h.lon), SuggestSource.OFFLINE, approximate = h.type != MatchType.EXACT)
        }
    }
}
