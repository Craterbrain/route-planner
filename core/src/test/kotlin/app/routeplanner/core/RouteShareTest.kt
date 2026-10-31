package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class RouteShareTest {
    private val eight = 8 * 3600L // route clock zero = 08:00 UTC in these tests
    private val startMs = eight * 1000

    private fun route(): RouteSession {
        val s = RouteSession(emptyList(), emptyList())
        val a = s.addStop(Stop("a", "469 Fair Oaks Rd, Crossville TN 38572", LatLng(36.0, -85.0), access = LatLng(36.0001, -85.0002),
            notes = "Gate code \"1234\", ring twice", placement = Placement.FIRST, window = 3600L..7200L, serviceSec = 300), codes = listOf("420385729361289724269887364749"))
        s.setZone("1-1", "Shelf A")
        s.addPackageTo(a.id, listOf("9400100000000000000009", "FSD1"))
        s.addStop(Stop("b", "22 Oak Ave", LatLng(36.1, -85.1), approximate = true))
        s.addStop(Stop("c", "9 Elm Rd", LatLng(36.2, -85.2)))
        s.mark("c", Status.COMPLETED)
        return s
    }

    private fun roundTrip(s: RouteSession, finished: Boolean = false) =
        RouteShare.decode(RouteShare.encode(s.stops, s.packages, startMs, finished, ZoneOffset.UTC), dayStartSec = eight)

    @Test fun roundTripKeepsEverythingThatShouldTravel() {
        val shared = roundTrip(route())
        assertEquals(2, shared.stops.size) // the delivered stop stays home
        val a = shared.stops[0]
        assertEquals("469 Fair Oaks Rd, Crossville TN 38572", a.address)
        assertEquals(LatLng(36.0, -85.0), a.target); assertEquals(LatLng(36.0001, -85.0002), a.access)
        assertEquals("Gate code \"1234\", ring twice", a.notes)
        assertEquals(Placement.FIRST, a.placement); assertEquals(3600L..7200L, a.window); assertEquals(300, a.serviceSec)
        assertEquals(1, a.entryNo); assertEquals(Status.PENDING, a.status)
        assertTrue(shared.stops[1].approximate)
        assertEquals(LatLng(36.1, -85.1), shared.stops[1].access)
        val pk = shared.packages.filter { it.stopId == a.id }
        assertEquals(listOf("1-1", "1-2"), pk.map { it.id })
        assertEquals("Shelf A", pk[0].truckZone)
        assertEquals(listOf("420385729361289724269887364749"), pk[0].codes)
        assertEquals(listOf("9400100000000000000009", "FSD1"), pk[1].codes)
        assertEquals(3, roundTrip(route(), finished = true).stops.size)
    }

    @Test fun theFileIsAnOrdinaryCsvWithOneRowPerPackage() {
        val s = route()
        val csv = RouteShare.encode(s.stops, s.packages, startMs, zone = ZoneOffset.UTC)
        val rows = parseCsv(csv)
        assertEquals(RouteShare.COLUMNS, rows[0])
        assertEquals(1 + 2 + 1, rows.size) // header, two packages for the first stop, one for the second (the third is delivered)
        assertEquals("09:00", rows[1][RouteShare.COLUMNS.indexOf("window_start")])
        assertEquals("first", rows[1][RouteShare.COLUMNS.indexOf("priority")])
        // The unchanged importer reads it too (ignoring the extra columns it doesn't know it can use).
        assertEquals(2, importManifest(csv, eight).stops.size)
    }

    @Test fun plainAddressListsAreNotSharedRoutes() {
        assertFalse(RouteShare.looksShared("address,package\n1 Main St,P1\n"))
        assertTrue(RouteShare.looksShared("Address,Lat,Lng\n1 Main St,1,2\n"))
        assertTrue(runCatching { RouteShare.decode("address,package\n1 Main St,P1\n") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun badFilesGiveReadableErrors() {
        for (bad in listOf("", "just text", "address,lat,lng\n", "address,lat,lng\nx,200x,5\n")) {
            val e = runCatching { RouteShare.decode(bad) }.exceptionOrNull()
            assertTrue("<$bad>", e is IllegalArgumentException && !e.message.isNullOrBlank())
        }
        val partly = RouteShare.decode("address,lat,lng\n1 A St,1,2\n2 B St,,\n")
        assertEquals(1, partly.stops.size); assertEquals(1, partly.skipped)
    }

    @Test fun csvIdColumnIsUsedWhenFreeAndFileOrderOtherwise() {
        val m = importManifest("address,lat,lng,id\nA 1,1,1,7\nB 2,2,2,7\nC 3,3,3,\nD 4,4,4,2\n")
        // Row 2 repeats 7, so it takes its file position (2); row 3 has no id (3); row 4 asks for 2, which is taken (4).
        assertEquals(listOf(7, 2, 3, 4), m.stops.map { it.entryNo })
    }

    @Test fun importIntoAnEmptyRouteKeepsIdsAndPackages() {
        val shared = roundTrip(route())
        val mine = RouteSession(emptyList(), emptyList())
        val r = mine.importShared(shared)
        assertEquals(2, r.added); assertEquals(0, r.merged)
        assertEquals(listOf(1, 2), mine.stops.map { it.entryNo })
        assertEquals(listOf("1-1", "1-2", "2-1"), mine.packages.map { it.id })
        assertEquals("Shelf A", mine.packages.first { it.id == "1-1" }.truckZone)
        assertTrue(mine.dirty)
    }

    @Test fun importIntoABusyRouteRenumbersMergesAndDoesNotDuplicateParcels() {
        val mine = RouteSession(emptyList(), emptyList())
        mine.addStop(Stop("m1", "100 My St", LatLng(35.0, -84.0)))                     // ID 1 is taken here
        val mineSame = mine.addStop(Stop("m2", "22 Oak Avenue", LatLng(36.1, -85.1)))   // same address as the sender's stop 2
        val r = mine.importShared(roundTrip(route()))
        assertEquals(1, r.added); assertEquals(1, r.merged)
        val newStop = mine.stops.first { it.address.startsWith("469") }
        assertEquals(3, newStop.entryNo) // sender's ID 1 was taken, so the next free one
        assertEquals(listOf("3-1", "3-2"), mine.packages.filter { it.stopId == newStop.id }.map { it.id })
        assertEquals(2, mine.packages.count { it.stopId == mineSame.id }) // gained the sender's package for 22 Oak Ave
        // Importing the same file again adds nothing new for parcels with codes.
        val again = mine.importShared(roundTrip(route()))
        assertEquals(0, again.added)
        assertEquals(1, mine.packages.count { p -> p.codes.any { it.startsWith("42038572") } })
    }

    @Test fun customPackageIdsSurviveWhenFree() {
        val s = RouteSession(listOf(Stop("x", "1 Main St", LatLng(1.0, 2.0))), listOf(Pkg("1Z999AA10123456784", "x", "Back")))
        val got = RouteSession(emptyList(), emptyList())
        got.importShared(RouteShare.decode(RouteShare.encode(s.stops, s.packages, startMs, zone = ZoneOffset.UTC)))
        assertEquals(listOf("1Z999AA10123456784"), got.packages.map { it.id })
        assertEquals("Back", got.packages[0].truckZone)
    }

    @Test fun summaryListsPendingStops() {
        val s = route()
        val t = RouteShare.summaryText(s.stops, s.packages)
        assertTrue(t.contains("469 Fair Oaks Rd") && t.contains("22 Oak Ave") && !t.contains("9 Elm Rd"))
    }
}
