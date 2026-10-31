package app.routeplanner.core

import kotlin.math.roundToLong

/** Where a suggestion came from (shown as a small caption). */
enum class SuggestSource(val caption: String) {
    LOCAL("Recent"), GOOGLE("Google"), OFFLINE("Offline"), DEVICE("Phone"), OSM("OSM"),
}

/**
 * A search-bar row. [point] is null for a plain query row (Google's "Kroger · See
 * locations"): picking it fills the text in instead of adding a stop. [approximate]
 * means the position is street- or area-level rather than the typed house.
 */
data class Suggestion(
    val title: String,
    val subtitle: String = "",
    val point: LatLng? = null,
    val source: SuggestSource = SuggestSource.OSM,
    val approximate: Boolean = false,
) {
    val label: String get() = listOf(title, subtitle).filter { it.isNotEmpty() }.joinToString(", ")
    val line1: String get() = title
    val line2: String get() = subtitle
}

/** One source of suggestions. Blocking; throws on network/parse failure, returns empty for "no matches". */
interface SuggestionProvider {
    val source: SuggestSource
    /** True when the provider's own order is authoritative (the chain only de-duplicates). */
    val ranksItself: Boolean get() = false
    fun suggest(query: String, near: LatLng?, limit: Int): List<Suggestion>
}

/** The offline address-pack slot (Tennessee pack, next round). Answers nothing until a pack is imported. */
object NoOfflineAddresses : SuggestionProvider {
    override val source = SuggestSource.OFFLINE
    override fun suggest(query: String, near: LatLng?, limit: Int) = emptyList<Suggestion>()
}

/** Address words: directions and street types normalized so "W Covell Blvd" ~ "West Covell Boulevard". */
object AddressText {
    private val DIRS = mapOf(
        "n" to "n", "north" to "n", "s" to "s", "south" to "s", "e" to "e", "east" to "e", "w" to "w", "west" to "w",
        "ne" to "ne", "northeast" to "ne", "nw" to "nw", "northwest" to "nw", "se" to "se", "southeast" to "se", "sw" to "sw", "southwest" to "sw",
    )
    private val TYPES = mapOf(
        "st" to "st", "street" to "st", "ave" to "ave", "av" to "ave", "avenue" to "ave", "rd" to "rd", "road" to "rd",
        "dr" to "dr", "drive" to "dr", "ln" to "ln", "lane" to "ln", "blvd" to "blvd", "boulevard" to "blvd",
        "ct" to "ct", "court" to "ct", "cir" to "cir", "circle" to "cir", "pl" to "pl", "place" to "pl",
        "pkwy" to "pkwy", "parkway" to "pkwy", "hwy" to "hwy", "highway" to "hwy", "pike" to "pike", "pk" to "pike",
        "way" to "way", "ter" to "ter", "terrace" to "ter", "trl" to "trl", "trail" to "trl", "loop" to "loop",
    )
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
    private val HOUSE = Regex("^\\s*(\\d+[a-zA-Z]?)\\s+(.+)$")
    private val DIGITS = Regex("^\\s*\\d+\\s*$")

    fun words(s: String): List<String> = s.lowercase().split(SPLIT).filter { it.isNotEmpty() }
    fun dir(w: String) = DIRS[w]
    fun type(w: String) = TYPES[w]

    fun isDigitsOnly(q: String) = DIGITS.matches(q)

    /** "1223 w main st" → number 1223, distinctive words [main], dir w, type st. Null when no leading house number. */
    class Parsed(val number: String, val streetWords: List<String>, val dir: String?, val type: String?)

    fun parse(q: String): Parsed? {
        val m = HOUSE.matchEntire(q) ?: return null
        val ws = words(m.groupValues[2])
        if (ws.isEmpty()) return null
        return Parsed(
            m.groupValues[1].lowercase(),
            ws.filter { dir(it) == null && type(it) == null },
            ws.firstNotNullOfOrNull { dir(it) },
            ws.firstNotNullOfOrNull { type(it) },
        )
    }

    /**
     * Street agreement of [title] with a typed address: a point per typed street word
     * found (as a word prefix, since the driver may still be typing), a point for the
     * house number, minus one for a different direction or street type.
     */
    fun score(p: Parsed, title: String): Int {
        val ws = words(title)
        var s = 0
        if (ws.firstOrNull() == p.number) s++
        s += p.streetWords.count { typed -> ws.any { it.startsWith(typed) } }
        val dirs = ws.mapNotNull { dir(it) }
        val types = ws.mapNotNull { type(it) }
        if (p.dir != null && dirs.isNotEmpty() && p.dir !in dirs) s--
        if (p.type != null && types.isNotEmpty() && p.type !in types) s--
        return s
    }

    /** A result for a typed house address that doesn't carry the house number is approximate. */
    fun approximateFor(query: String, title: String): Boolean {
        val p = parse(query) ?: return false
        return words(title).none { it == p.number }
    }
}

object SuggestRanker {
    /** Digits-only input keeps results this close to the start (Google mixes the same number in other states). */
    const val DIGITS_RADIUS_M = 80_000.0

    /** Same name within ~110 m is one place. */
    fun key(s: Suggestion): String {
        val name = AddressText.words(s.title).joinToString(" ")
        val p = s.point ?: return name
        return "$name|${(p.lat * 1000).roundToLong()}|${(p.lng * 1000).roundToLong()}"
    }

    fun dedupe(items: List<Suggestion>): List<Suggestion> {
        val seen = HashSet<String>()
        return items.filter { seen.add(key(it)) }
    }

    /**
     * Orders one provider's answer: de-duplicate; digits-only text keeps nearby rows;
     * a typed house address ranks by street agreement; then nearest to [near]; rows
     * without a position last. Marks rows approximate when they lack the typed number.
     */
    fun rank(query: String, items: List<Suggestion>, near: LatLng?): List<Suggestion> {
        var list = dedupe(items)
        if (AddressText.isDigitsOnly(query) && near != null)
            list = list.filter { s -> s.point != null && haversine(s.point, near) <= DIGITS_RADIUS_M }
        val parsed = AddressText.parse(query)
        if (parsed != null) list = list.map { if (it.point != null && !it.approximate && AddressText.approximateFor(query, it.title)) it.copy(approximate = true) else it }
        return list.withIndex().sortedWith(
            compareByDescending<IndexedValue<Suggestion>> { if (parsed != null) AddressText.score(parsed, it.value.title) else 0 }
                .thenBy { it.value.point == null }
                .thenBy { v -> v.value.point?.let { p -> near?.let { haversine(p, it) } } ?: Double.MAX_VALUE }
                .thenBy { it.index }
        ).map { it.value }
    }

    /** Local rows first, then network rows that don't repeat them. */
    fun merge(local: List<Suggestion>, network: List<Suggestion>, max: Int = 8): List<Suggestion> {
        val keys = local.map { key(it) }.toHashSet()
        return (local + network.filter { key(it) !in keys }).take(max)
    }
}

/**
 * Opens after [threshold] consecutive failures and stays open for [openMs]; then
 * one request is let through, and a success closes it.
 */
class CircuitBreaker(private val threshold: Int = 3, private val openMs: Long = 10 * 60_000L, private val clock: () -> Long = System::currentTimeMillis) {
    private var failures = 0
    private var openedAt: Long? = null

    @Synchronized fun isOpen(): Boolean {
        val t = openedAt ?: return false
        if (clock() - t >= openMs) { openedAt = null; failures = threshold - 1; return false } // half-open: one try
        return true
    }
    @Synchronized fun success() { failures = 0; openedAt = null }
    @Synchronized fun failure() { failures++; if (failures >= threshold) openedAt = clock() }
}

/** Matches recents, route stops and remembered pins on word prefixes; instant, no network. */
class LocalSuggestions(private val entries: List<Suggestion>) : SuggestionProvider {
    override val source = SuggestSource.LOCAL
    override fun suggest(query: String, near: LatLng?, limit: Int): List<Suggestion> {
        val typed = AddressText.words(query)
        if (typed.isEmpty()) return emptyList()
        return SuggestRanker.dedupe(entries.filter { e ->
            val ws = AddressText.words(e.title + " " + e.subtitle)
            typed.all { t -> ws.any { it.startsWith(t) } }
        }).take(limit)
    }
}

/**
 * Provider chain: the first provider (in order) that answers with rows wins.
 * A provider with a [CircuitBreaker] is skipped while it is open; its failures
 * (exceptions) count toward opening it.
 */
class SuggestChain(private val providers: List<SuggestionProvider>, private val breakers: Map<SuggestSource, CircuitBreaker> = emptyMap()) {
    class Result(
        val items: List<Suggestion>,
        val answeredBy: SuggestSource?,
        /** Providers skipped because their breaker is open (e.g. Google). */
        val unhealthy: List<SuggestSource>,
        val failures: List<Pair<SuggestSource, Throwable>>,
    )

    fun suggest(query: String, near: LatLng?, limit: Int = 8): Result {
        val skipped = ArrayList<SuggestSource>()
        val failed = ArrayList<Pair<SuggestSource, Throwable>>()
        for (p in providers) {
            val b = breakers[p.source]
            if (b?.isOpen() == true) { skipped += p.source; continue }
            val raw = try { p.suggest(query, near, limit) } catch (e: Exception) { b?.failure(); failed += p.source to e; continue }
            b?.success()
            val ranked = (if (p.ranksItself) SuggestRanker.dedupe(raw) else SuggestRanker.rank(query, raw, near)).take(limit)
            if (ranked.isNotEmpty()) return Result(ranked, p.source, skipped, failed)
        }
        return Result(emptyList(), null, skipped, failed)
    }

    /** Single best position for an exact address (spreadsheet import). */
    fun geocode(address: String, near: LatLng?): Suggestion? =
        suggest(address, near, 5).items.firstOrNull { it.point != null }
}
