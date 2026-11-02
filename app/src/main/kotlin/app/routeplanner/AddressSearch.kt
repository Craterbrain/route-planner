package app.routeplanner

import android.content.Context
import android.location.Geocoder as AndroidGeocoder
import android.util.Log
import app.routeplanner.core.*
import app.routeplanner.core.address.OfflineAddressProvider
import app.routeplanner.core.google.GoogleSuggest
import app.routeplanner.core.google.GoogleWeb
import java.util.Locale

/**
 * Address lookup for the search bar, CSV import and long-press.
 *
 * Suggestions while typing (first provider with rows wins; local rows are shown
 * before any of these; a house the offline pack knows exactly near the start comes first of all): Google (unofficial, if enabled and its breaker is closed)
 * → offline address pack → the phone's geocoder → Photon (OSM).
 *
 * Resolving a full address (CSV rows): offline pack when it has the exact house
 * (or an interpolation) → phone geocoder → Google's top row → Photon → the pack's
 * estimated position. Remembered drop-off pins are applied on top by the caller.
 *
 * All calls block; run them off the UI thread.
 */
class AddressSearch(ctx: Context, private val prefs: Prefs, private val bias: () -> LatLng? = { null }) {
    companion object {
        /** One breaker per process: 3 failures in a row → Google skipped for 10 minutes. */
        val googleBreaker = CircuitBreaker(3, 10 * 60_000L)
        const val GOOGLE_DOWN = "Google suggestions unavailable, using offline/OSM"
    }

    private val app = ctx.applicationContext
    private val platform = PlatformGeocoder(app)
    private val geocoderBase = prefs.geocoderUrl
    val photon = PhotonGeocoder(prefs.geocoderUrl, http = TimedGet(6_000, 8_000), userAgent = NetStatus.userAgent(), bias = bias)

    private val google: GoogleSuggest? = if (!prefs.googleSuggest) null else GoogleSuggest(
        GoogleTransport.http(app, cancelPrevious = true),
        { GoogleTransport.chromeMajor },
        GoogleWeb.acceptLanguage(Locale.getDefault().toLanguageTag()),
    )
    private val offline = OfflineAddressProvider { AddressPack.search(app) }
    private val device = object : SuggestionProvider {
        override val source = SuggestSource.DEVICE
        override fun suggest(query: String, near: LatLng?, limit: Int): List<Suggestion> =
            if (!AndroidGeocoder.isPresent()) emptyList() else platform.candidates(query, limit, near)
    }

    sealed class Outcome {
        class Found(val items: List<Suggestion>, val source: SuggestSource, val note: String?) : Outcome()
        class Empty(val note: String?) : Outcome()
        class Failed(val message: String) : Outcome()
    }

    /** Instant, no network: recent places, this route's stops and remembered drop-off pins, on word prefixes. */
    fun local(query: String, limit: Int = 4): List<Suggestion> {
        val stops = SessionStore.session?.stops.orEmpty().map { s ->
            val (l1, l2) = splitAddress(s.address)
            Suggestion(l1, listOf(l2, "on this route").filter { it.isNotEmpty() }.joinToString(" · "), s.target, SuggestSource.LOCAL)
        }
        val pins = prefs.rememberedPins().map { (addr, p) -> Suggestion(addr, "Saved drop-off", p, SuggestSource.LOCAL) }
        return LocalSuggestions(prefs.recents + stops + pins).suggest(query, bias(), limit)
    }

    /** Network/offline suggestions through the provider chain. */
    fun suggest(query: String, limit: Int = 8): Outcome {
        val chain = SuggestChain(listOfNotNull(google, offline, device, photon), mapOf(SuggestSource.GOOGLE to googleBreaker), trusted = offline)
        val r = chain.suggest(query, bias(), limit)
        val googleTrouble = google != null && (SuggestSource.GOOGLE in r.unhealthy || r.failures.any { it.first == SuggestSource.GOOGLE })
        val note = if (googleTrouble) GOOGLE_DOWN else null
        r.failures.forEach { (src, e) -> AppLog.w("suggest: ${src.caption} failed: ${e.javaClass.simpleName}") } // never the query
        return when {
            r.items.isNotEmpty() -> Outcome.Found(r.items, r.answeredBy!!, note)
            r.failures.any { it.first == SuggestSource.OSM } -> Outcome.Failed(failureMessage(r.failures.last().second))
            else -> Outcome.Empty(note)
        }
    }

    private fun failureMessage(e: Throwable): String = when {
        NetStatus.systemSaysOffline(app) -> "No network access: address search needs the internet (or an imported address pack)."
        e is HttpStatusException -> "Address search error (HTTP ${e.code})."
        else -> "Couldn't reach address search."
    }

    /** Spreadsheet import resolver (see class doc), biased to the start. */
    fun geocoder(): DetailedGeocoder = DetailedGeocoder { address ->
        val near = bias()
        val off = runCatching { offline.suggest(address, near, 1).firstOrNull() }.getOrNull()
        if (off != null && !off.approximate) return@DetailedGeocoder off
        if (AndroidGeocoder.isPresent()) runCatching { platform.candidates(address, 1, near).firstOrNull() }.getOrNull()?.let {
            return@DetailedGeocoder it.copy(approximate = AddressText.approximateFor(address, it.title))
        }
        val g0 = google
        if (g0 != null && !googleBreaker.isOpen()) {
            val g = runCatching { g0.suggest(address, near, 5).also { googleBreaker.success() } }
                .onFailure { googleBreaker.failure() }.getOrNull()
            Thread.sleep(250) // gentle pacing across many rows
            SuggestRanker.rank(address, g.orEmpty(), near).firstOrNull { it.point != null }?.let { return@DetailedGeocoder it }
        }
        runCatching { SuggestRanker.rank(address, photon.search(address, 3, near), near).firstOrNull() }.getOrNull()?.let { return@DetailedGeocoder it }
        off
    }

    /**
     * Confirms a full address (a scanned label, say) before it is added without asking: Google's rows first,
     * then the phone's geocoder (Google-backed with Play services), then the offline pack's exact hit. A result
     * counts only if [AddressMatch] says it is the same house number and street (and ZIP, when both have one).
     * Null when nothing agrees, so the caller can hand the text to the search bar instead. Blocking.
     */
    fun validate(address: String): Suggestion? {
        val near = bias()
        val g0 = google
        if (g0 != null && !googleBreaker.isOpen()) {
            val rows = runCatching { g0.suggest(address, near, 5).also { googleBreaker.success() } }
                .onFailure { googleBreaker.failure() }.getOrNull().orEmpty()
            SuggestRanker.rank(address, rows, near).firstOrNull { AddressMatch.matches(address, it) }?.let { return it }
        }
        if (AndroidGeocoder.isPresent()) {
            runCatching { platform.candidates(address, 3, near) }.getOrNull().orEmpty()
                .firstOrNull { AddressMatch.matches(address, it) }
                ?.let { return it.copy(approximate = AddressText.approximateFor(address, it.title)) }
        }
        return runCatching { offline.suggest(address, near, 3) }.getOrNull().orEmpty()
            .firstOrNull { !it.approximate && AddressMatch.matches(address, it) }
    }

    /** Address at [p] for long-press, or null. */
    fun reverse(p: LatLng): String? =
        platform.reverse(p) ?: runCatching { photon.reverse(p)?.label }.getOrNull()

    /** Kick a shared reachability check (map chip / permissions sheet use the result). */
    fun recheckNetwork() = NetStatus.check(app, geocoderBase)
}
