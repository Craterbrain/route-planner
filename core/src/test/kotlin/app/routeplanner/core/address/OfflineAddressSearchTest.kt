package app.routeplanner.core.address

import app.routeplanner.core.Json
import app.routeplanner.core.LatLng
import app.routeplanner.core.SuggestSource
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** In-memory AddressStore with the same query semantics as the SQLite schema. */
class FakeAddressStore(streets: List<StreetRow>, private val addr: List<Pair<Long, AddrPoint>>) : AddressStore {
    private val sorted = streets.sortedWith(compareBy({ it.norm }, { it.id }))
    private val byStreet = addr.groupBy({ it.first }, { it.second })

    override fun streetsInRange(from: String, to: String, postcode: String?, limit: Int) =
        sorted.filter { it.norm >= from && it.norm < to && (postcode == null || it.postcode == postcode) }.take(limit)

    override fun exact(streetId: Long, num: Int, number: String) =
        byStreet[streetId].orEmpty().filter { it.num == num && it.number == number }

    override fun below(streetId: Long, num: Int, parity: Int?) =
        byStreet[streetId].orEmpty().filter { it.num in 0 until num && (parity == null || it.num % 2 == parity) }.maxByOrNull { it.num }

    override fun above(streetId: Long, num: Int, parity: Int?) =
        byStreet[streetId].orEmpty().filter { it.num > num && (parity == null || it.num % 2 == parity) }.minByOrNull { it.num }
}

class OfflineAddressSearchTest {
    private class Fixture(val store: FakeAddressStore, val cases: List<Map<*, *>>)

    private fun load(input: InputStream): Fixture {
        val root = Json.parse(GZIPInputStream(input).bufferedReader().readText()) as Map<*, *>
        fun s(x: Any?) = x as String?
        fun i(x: Any?) = (x as Double).toInt()
        val streets = (root["streets"] as List<*>).map { r ->
            r as List<*>
            StreetRow(i(r[0]).toLong(), s(r[1])!!, s(r[2])!!, s(r[3]), s(r[4]), s(r[5]), i(r[6]), i(r[7]))
        }
        val addr = (root["addr"] as List<*>).map { r ->
            r as List<*>
            i(r[0]).toLong() to AddrPoint(i(r[1]), s(r[2])!!, s(r[3])!!, i(r[4]), i(r[5]))
        }
        @Suppress("UNCHECKED_CAST")
        return Fixture(FakeAddressStore(streets, addr), root["cases"] as List<Map<*, *>>)
    }

    private fun replay(f: Fixture) {
        val search = OfflineAddressSearch(f.store)
        for (c in f.cases) {
            val text = c["text"] as String
            val near = (c["near"] as List<*>?)?.let { LatLng(it[0] as Double, it[1] as Double) }
            val got = search.search(text, near, 5)
            val want = c["expected"] as List<*>
            assertEquals("count for '$text'", want.size, got.size)
            want.forEachIndexed { k, w ->
                w as Map<*, *>
                val g = got[k]
                val ctx = "'$text' #$k"
                assertEquals("$ctx type", w["type"], g.type.label)
                assertEquals("$ctx label", w["label"], g.label)
                assertEquals("$ctx detail", w["detail"], g.detail)
                assertEquals("$ctx street", (w["street_id"] as Double).toLong(), g.streetId)
                assertEquals("$ctx lat", w["lat"] as Double, g.lat, 2e-6)
                assertEquals("$ctx lon", w["lon"] as Double, g.lon, 2e-6)
            }
        }
    }

    /** Same answers as tools/query-address.py on the Tennessee pack (rows exported by tools/export-address-fixture.py). */
    @Test fun matchesPythonReference() = replay(load(javaClass.getResourceAsStream("/address-fixture.json.gz")!!))

    /** Optional: -PaddressFixture=... replays a fixture kept outside the repo. */
    @Test fun matchesExtraFixtureWhenGiven() {
        val path = System.getProperty("addressFixture") ?: return
        val f = load(File(path).inputStream())
        replay(f)
        // The reported case: an exact house ~13 km from the start must beat a nearer estimate.
        f.cases.firstOrNull { it["text"] == "8681 US-70" && it["near"] != null }?.let { c ->
            val near = (c["near"] as List<*>).let { LatLng(it[0] as Double, it[1] as Double) }
            assertEquals(MatchType.EXACT, OfflineAddressSearch(f.store).search("8681 US-70", near)[0].type)
        }
    }

    /** Exact far house beats a nearby estimate; a typed town that contradicts it flips that. */
    @Test fun exactBeatsNearbyEstimateInRealData() {
        val f = load(javaClass.getResourceAsStream("/address-fixture.json.gz")!!)
        val s = OfflineAddressSearch(f.store)
        val knoxville = LatLng(35.9606, -83.9207)
        val far = s.search("2500 west end ave", knoxville)
        assertEquals(MatchType.EXACT, far[0].type)
        assertEquals("Nashville", far[0].city)
        assertTrue((far[0].distM ?: 0) > 200_000)
        assertEquals(MatchType.NEAREST, far[1].type)
        assertTrue((far[1].distM ?: Long.MAX_VALUE) < 25_000)
        val typed = s.search("2500 west end ave, farragut", knoxville)
        assertEquals(MatchType.NEAREST, typed[0].type)
        assertEquals("Farragut", typed[0].city)
    }

    // ---- Small hand-made store: parsing, layers, ranking ----

    private fun street(id: Long, name: String, city: String, zip: String, lat: Double, lon: Double) =
        StreetRow(id, name, AddrNorm.normalizeStreet(name), city, city, zip, (lat * 1e6).toInt(), (lon * 1e6).toInt())
    private fun pt(sid: Long, n: Int, lat: Double, lon: Double, unit: String = "") =
        sid to AddrPoint(n, n.toString(), unit, (lat * 1e6).toInt(), (lon * 1e6).toInt())

    private val tiny = FakeAddressStore(
        listOf(
            street(1, "Main Street", "Springfield", "11111", 36.0, -86.0),
            street(2, "Main Street", "Shelbyville", "22222", 35.0, -86.5),
            street(3, "Oak Avenue", "Springfield", "11111", 36.01, -86.01),
        ),
        listOf(
            pt(1, 100, 36.0000, -86.0000), pt(1, 110, 36.0010, -86.0000), pt(1, 120, 36.0020, -86.0000),
            pt(1, 200, 36.0100, -86.0000, "a"), pt(1, 200, 36.0102, -86.0000, "b"),
            pt(2, 100, 35.0000, -86.5000),
            pt(3, 10, 36.0100, -86.0100),
        ),
    )
    private val s = OfflineAddressSearch(tiny)

    @Test fun parseQuerySplitsHouseUnitLocalityZip() {
        val p = s.parseQuery("112B Main St apt 4, Springfield TN 11111")
        assertEquals("112", p.house.number)
        assertEquals("b4", p.house.unit)
        assertEquals(listOf("Main", "St"), p.words)
        assertEquals(listOf("Springfield"), p.locWords)
        assertEquals("11111", p.postcode)
    }

    @Test fun exactInterpolatedNearestStreet() {
        assertEquals(MatchType.EXACT, s.search("110 main st, springfield")[0].type)
        val interp = s.search("114 main st, springfield")[0]
        assertEquals(MatchType.INTERPOLATED, interp.type)
        assertEquals(36.0010 + 0.4 * 0.0010, interp.lat, 1e-6) // between 110 and 120, same parity
        assertEquals(MatchType.NEAREST, s.search("20 oak ave, springfield")[0].type) // numbers on one side only
        assertEquals(MatchType.STREET, s.search("oak ave, springfield")[0].type)
    }

    @Test fun unitCentroidWhenNoBuildingRow() {
        val h = s.search("200 main st, springfield")[0]
        assertEquals(MatchType.EXACT, h.type)
        assertEquals(36.0101, h.lat, 1e-6)
        assertEquals(36.0102, s.search("200 main st unit b, springfield")[0].lat, 1e-6)
    }

    @Test fun localityAndDistanceRanking() {
        assertEquals("Shelbyville", s.search("100 main st, shelbyville")[0].city)
        assertEquals("Shelbyville", s.search("100 main st shelbyv")[0].city) // dropped word as half-typed locality
        assertEquals("Shelbyville", s.search("100 main st", LatLng(35.0, -86.5))[0].city)
        assertEquals("Springfield", s.search("100 main st", LatLng(36.0, -86.0))[0].city)
    }

    @Test fun rankingTiersThenNearBandThenDistance() {
        val st = FakeAddressStore(
            listOf(
                street(10, "Pine Road", "Near Town", "33333", 36.00, -86.00),   // estimate only, at the start
                street(11, "Pine Road", "Mid Town", "44444", 36.15, -86.00),    // exact, ~17 km
                street(12, "Pine Road", "Far Town", "55555", 36.90, -86.00),    // exact, ~100 km
            ),
            listOf(
                pt(10, 40, 36.000, -86.000), pt(10, 60, 36.001, -86.000),       // 50 interpolated here
                pt(11, 50, 36.150, -86.000),
                pt(12, 50, 36.900, -86.000),
            ),
        )
        val r = OfflineAddressSearch(st).search("50 pine rd", LatLng(36.0, -86.0))
        assertEquals(listOf(11L, 12L, 10L), r.map { it.streetId })   // exact near band, exact far, then estimate
        assertEquals(listOf(MatchType.EXACT, MatchType.EXACT, MatchType.INTERPOLATED), r.map { it.type })
        // A typed town that only the estimate matches wins: the exact houses contradict it.
        assertEquals(10L, OfflineAddressSearch(st).search("50 pine rd, near town", LatLng(36.0, -86.0))[0].streetId)
    }

    @Test fun neverInterpolatesAcrossGroups() {
        // Shelbyville's Main St has only 100: asking 150 there must not use Springfield's numbers.
        val h = s.search("150 main st, shelbyville")[0]
        assertEquals(2L, h.streetId)
        assertEquals(MatchType.NEAREST, h.type)
    }

    @Test fun halfTypedLastWord() {
        assertEquals("Oak Avenue", s.search("10 oa")[0].streetName)
    }

    @Test fun providerFlagsApproximate() {
        val p = OfflineAddressProvider { s }
        val exact = p.suggest("110 main st, springfield", null, 3)[0]
        assertFalse(exact.approximate)
        assertEquals(SuggestSource.OFFLINE, exact.source)
        assertEquals("110 Main Street", exact.title)
        val approx = p.suggest("114 main st, springfield", null, 3)[0]
        assertTrue(approx.approximate)
        assertTrue(approx.subtitle.contains("Estimated position"))
    }
}
