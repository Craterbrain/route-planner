package app.routeplanner.core

import app.routeplanner.core.google.GoogleSuggest
import app.routeplanner.core.google.GoogleSuggestParser
import app.routeplanner.core.google.GoogleWeb
import app.routeplanner.core.google.UnparseableReply
import org.junit.Assert.*
import org.junit.Test

class SuggestTest {
    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()
    private val nashville = LatLng(36.1627, -86.7816)

    // ---- Google reply parsing (fixtures recorded 2026-10-31 from the live endpoint) ----

    @Test fun parsesAddressReply() {
        val r = GoogleSuggestParser.parse(res("google-suggest-address.txt"))
        assertEquals(5, r.size)
        assertEquals("1000 Broadway", r[0].title)
        assertEquals("Nashville, TN", r[0].subtitle)
        assertEquals(36.1580962, r[0].point!!.lat, 1e-6)
        assertEquals(-86.7849611, r[0].point!!.lng, 1e-6)
        assertEquals(SuggestSource.GOOGLE, r[0].source)
        assertNull("query row has no position", r[2].point)
        assertEquals("1000 broadway", r[2].title)
    }

    @Test fun parsesDigitsReply() {
        val r = GoogleSuggestParser.parse(res("google-suggest-digits.txt"))
        assertEquals("1223 Dickerson Pike", r[0].title)
        assertTrue(r.all { it.point != null })
    }

    @Test fun envelopeWithBracesInStringsAndShiftedColumn() {
        // Echoed URL with braces, and the content column moved from index 22 to 3.
        val inner = """[["q",[[null,null,null,[["Main St, Town",null],["Main St"],["Town, TN"],null,null,null,null,null,null,null,null,[null,null,36.5,-86.5]]]]]]"""
        val body = "{\"c\":0,\"d\":" + Json.quote(")]}'\n" + inner) + ",\"u\":\"https://x/?a={b}\"}/*\"\"*/{\"c\":0,\"d\":\"\"}"
        val r = GoogleSuggestParser.parse(body)
        assertEquals(1, r.size)
        assertEquals("Main St", r[0].title)
        assertEquals(LatLng(36.5, -86.5), r[0].point)
    }

    @Test fun unparseableThrowsButEmptyRowsIsValid() {
        assertThrows(UnparseableReply::class.java) { GoogleSuggestParser.parse("<html>consent</html>") }
        val empty = "{\"c\":0,\"d\":" + Json.quote(")]}'\n[[\"zzz\",[]]]") + "}"
        assertTrue(GoogleSuggestParser.parse(empty).isEmpty())
    }

    @Test fun requestUrlAndHeaders() {
        var url = ""
        var headers = emptyMap<String, String>()
        val g = GoogleSuggest({ u, h -> url = u; headers = h; res("google-suggest-address.txt") }, { 141 }, "en-US,en;q=0.9")
        val r = g.suggest("1000 broadway", nashville, 8)
        assertEquals(5, r.size)
        assertTrue(url.startsWith(GoogleWeb.SUGGEST_URL + "&pb="))
        assertTrue(url.contains("%212d-86.7816%213d36.1627"))
        assertTrue(url.contains("&q=1000%20broadway&tch=1&ech=1"))
        assertTrue(headers.getValue("User-Agent").contains("Chrome/141.0.0.0"))
        assertEquals("\"Windows\"", headers["Sec-CH-UA-Platform"])
        assertEquals("same-origin", headers["Sec-Fetch-Site"])
        g.suggest("x y z", nashville, 8)
        assertTrue(url.endsWith("ech=2"))
        assertTrue("no bias point: let the fallbacks answer", g.suggest("abc", null, 8).isEmpty())
    }

    @Test fun secChUaMatchesRealChrome() {
        // Values Chrome itself sent for these majors.
        assertEquals("\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"", GoogleWeb.secChUa(120))
        assertEquals("\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"", GoogleWeb.secChUa(124))
        assertEquals(141, GoogleWeb.majorFrom("Cronet/141.0.7340.3@abcdef"))
        assertEquals(157, GoogleWeb.majorFrom("157.0.8103.27"))
        assertEquals("en-US,en;q=0.9", GoogleWeb.acceptLanguage("en-US"))
    }

    // ---- Ranking ----

    private fun sug(t: String, sub: String, lat: Double?, lng: Double?, src: SuggestSource = SuggestSource.GOOGLE) =
        Suggestion(t, sub, if (lat != null && lng != null) LatLng(lat, lng) else null, src)

    @Test fun houseNumberPrefersMatchingStreetThenNearest() {
        val items = listOf(
            sug("1000 Broadway", "Cincinnati, OH", 39.10, -84.50),
            sug("1000 Broadway Street", "Dayton, TN", 35.50, -85.00),
            sug("1000 Broadway", "Nashville, TN", 36.158, -86.785),
            sug("1000 Main Street", "Nashville, TN", 36.16, -86.78),
        )
        val r = SuggestRanker.rank("1000 broadway", items, nashville)
        assertEquals("Nashville, TN", r[0].subtitle)
        assertEquals("1000 Main Street", r.last().title)
    }

    @Test fun differentDirectionOrTypeScoresLower() {
        val p = AddressText.parse("100 w main st")!!
        assertTrue(AddressText.score(p, "100 W Main St") > AddressText.score(p, "100 E Main St"))
        assertTrue(AddressText.score(p, "100 W Main St") > AddressText.score(p, "100 W Main Ave"))
    }

    @Test fun digitsOnlyKeepsNearby() {
        val items = listOf(sug("1223 Broadway", "Nashville", 36.155, -86.788), sug("1223 Main St", "Atlanta, GA", 33.75, -84.39))
        val r = SuggestRanker.rank("1223", items, nashville)
        assertEquals(listOf("1223 Broadway"), r.map { it.title })
    }

    @Test fun approximateWhenNumberMissing() {
        val r = SuggestRanker.rank("55 broadway", listOf(sug("Broadway", "Nashville, TN", 36.16, -86.78)), nashville)
        assertTrue(r[0].approximate)
    }

    @Test fun dedupeAndMergeLocalFirst() {
        val a = sug("1000 Broadway", "Nashville, TN", 36.15810, -86.78496)
        val dup = sug("1000 broadway", "Nashville", 36.15812, -86.78499, SuggestSource.OSM)
        assertEquals(1, SuggestRanker.dedupe(listOf(a, dup)).size)
        val local = listOf(dup.copy(source = SuggestSource.LOCAL))
        val merged = SuggestRanker.merge(local, listOf(a, sug("1002 Broadway", "", 36.1582, -86.7852)))
        assertEquals(listOf(SuggestSource.LOCAL, SuggestSource.GOOGLE), merged.map { it.source })
    }

    @Test fun localMatchesWordPrefixes() {
        val local = LocalSuggestions(listOf(
            sug("1000 Broadway", "Nashville, TN", 36.158, -86.785, SuggestSource.LOCAL),
            sug("22 Oak Ave", "Springfield", 1.0, 1.0, SuggestSource.LOCAL),
        ))
        assertEquals("1000 Broadway", local.suggest("1000 bro", null, 5).single().title)
        assertEquals("22 Oak Ave", local.suggest("oak spr", null, 5).single().title)
        assertTrue(local.suggest("ak", null, 5).isEmpty())
    }

    // ---- Chain and circuit breaker ----

    private class Fake(override val source: SuggestSource, var answer: () -> List<Suggestion>) : SuggestionProvider {
        var calls = 0
        override fun suggest(query: String, near: LatLng?, limit: Int): List<Suggestion> { calls++; return answer() }
    }

    @Test fun chainFallsBackAndBreakerOpensAfterThreeFailures() {
        var now = 0L
        val breaker = CircuitBreaker(3, 600_000L) { now }
        val google = Fake(SuggestSource.GOOGLE) { throw java.io.IOException("blocked") }
        val osm = Fake(SuggestSource.OSM) { listOf(sug("1000 Broadway", "Nashville", 36.158, -86.785, SuggestSource.OSM)) }
        val chain = SuggestChain(listOf(google, osm), mapOf(SuggestSource.GOOGLE to breaker))
        repeat(3) {
            val r = chain.suggest("1000 broadway", nashville)
            assertEquals(SuggestSource.OSM, r.answeredBy)
            assertEquals(1, r.failures.size)
        }
        assertEquals(3, google.calls)
        val r = chain.suggest("1000 broadway", nashville)
        assertEquals(listOf(SuggestSource.GOOGLE), r.unhealthy)
        assertEquals("open breaker skips Google", 3, google.calls)
        now = 600_001L
        google.answer = { listOf(sug("1000 Broadway", "Nashville", 36.158, -86.785)) }
        assertEquals(SuggestSource.GOOGLE, chain.suggest("1000 broadway", nashville).answeredBy)
        assertTrue(chain.suggest("1000 broadway", nashville).unhealthy.isEmpty())
    }

    @Test fun emptyAnswerFallsThroughWithoutCountingAsFailure() {
        val breaker = CircuitBreaker(1, 600_000L) { 0L }
        val google = Fake(SuggestSource.GOOGLE) { emptyList() }
        val offline = Fake(SuggestSource.OFFLINE) { listOf(sug("8 Main St", "", 36.0, -86.0, SuggestSource.OFFLINE)) }
        val chain = SuggestChain(listOf(google, offline), mapOf(SuggestSource.GOOGLE to breaker))
        assertEquals(SuggestSource.OFFLINE, chain.suggest("8 main", nashville).answeredBy)
        assertFalse(breaker.isOpen())
        assertEquals("8 Main St", chain.geocode("8 main st", nashville)!!.title)
    }

    @Test fun exactOfflineHouseNearTheStartBeatsAnotherProvidersNamesake() {
        // Typing "14 East Street" with no city: Google answers with a far-away namesake, the pack knows the house nearby.
        val crossville = LatLng(35.95, -85.03)
        val google = Fake(SuggestSource.GOOGLE) { listOf(sug("14 E Street Rd", "West Chester, PA", 39.96, -75.6)) }
        val offline = Fake(SuggestSource.OFFLINE) { listOf(sug("14 East Street", "Crossville 38555", 35.948, -85.025, SuggestSource.OFFLINE)) }
        val chain = SuggestChain(listOf(google, offline), trusted = offline)
        val r = chain.suggest("14 east street", crossville)
        assertEquals(SuggestSource.OFFLINE, r.answeredBy)
        assertEquals("14 East Street", r.items.first().title)
        assertEquals("Google is not even asked", 0, google.calls)
    }

    @Test fun offlineEstimatesAndFarMatchesDoNotOverrideTheChain() {
        val crossville = LatLng(35.95, -85.03)
        val google = Fake(SuggestSource.GOOGLE) { listOf(sug("14 East St", "Crossville, TN", 35.949, -85.026)) }
        val estimate = Suggestion("14 East Street", "Estimated position", LatLng(35.9, -85.0), SuggestSource.OFFLINE, approximate = true)
        val farExact = Fake(SuggestSource.OFFLINE) { listOf(sug("14 East Street", "Bluff City", 36.47, -82.25, SuggestSource.OFFLINE)) }
        assertEquals(SuggestSource.GOOGLE, SuggestChain(listOf(google), trusted = Fake(SuggestSource.OFFLINE) { listOf(estimate) }).suggest("14 east street", crossville).answeredBy)
        assertEquals(SuggestSource.GOOGLE, SuggestChain(listOf(google), trusted = farExact).suggest("14 east street", crossville).answeredBy)
        // No start to measure from: an exact house in the pack still leads.
        assertEquals(SuggestSource.OFFLINE, SuggestChain(listOf(google), trusted = farExact).suggest("14 east street", null).answeredBy)
    }

    @Test fun offlineSlotStubAnswersNothing() {
        assertTrue(NoOfflineAddresses.suggest("1 main st", nashville, 5).isEmpty())
    }
}

class ManifestApproxTest {
    @Test fun detailedGeocoderFlagsApproximateRows() {
        val g = DetailedGeocoder { a ->
            if (a.startsWith("9")) Suggestion("Main St", "Town", LatLng(1.0, 1.0), SuggestSource.OFFLINE, approximate = true)
            else Suggestion(a, "", LatLng(2.0, 2.0), SuggestSource.GOOGLE)
        }
        val m = importManifest("address,package\n\"9 Main St\",P1\n\"10 Oak Ave\",P2\n", geocoder = g)
        assertTrue(m.stops[0].approximate)
        assertFalse(m.stops[1].approximate)
        assertEquals(1, m.warnings.size)
        assertTrue(m.errors.isEmpty())
    }
}
