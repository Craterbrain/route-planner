package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class AddressIdTest {
    private fun stop(id: String, addr: String) = Stop(id, addr, LatLng(1.0, 2.0))

    @Test fun idsAreSequentialInTheOrderAddressesAreAdded() {
        val s = RouteSession(emptyList(), emptyList())
        val a = s.addStop(stop("a", "1 Main St"))
        val b = s.addStop(stop("b", "2 Main St"))
        val c = s.addStop(stop("c", "3 Main St"))
        assertEquals(listOf(1, 2, 3), listOf(a, b, c).map { it.entryNo })
        // Reordering the route changes the stop number, never the ID.
        s.moveStop("c", 0)
        assertEquals(listOf("c", "a", "b"), s.stops.map { it.id })
        assertEquals(listOf(3, 1, 2), s.stops.map { it.entryNo })
        assertEquals(listOf(1, 2, 3), s.stops.map { it.optimizedIndex })
    }

    @Test fun idsAreNotReusedAfterARemoval() {
        val s = RouteSession(emptyList(), emptyList())
        s.addStop(stop("a", "1 Main St")); s.addStop(stop("b", "2 Main St"))
        s.removeStop("b")
        assertEquals(3, s.addStop(stop("c", "3 Main St")).entryNo)
    }

    @Test fun oldRoutesWithoutIdsGetThemInOrder() {
        val s = RouteSession(listOf(stop("a", "1 A"), stop("b", "2 B")), emptyList())
        assertEquals(listOf(1, 2), s.stops.map { it.entryNo })
        assertEquals(3, s.addStop(stop("c", "3 C")).entryNo)
    }

    @Test fun csvRowsAreNumberedInFileOrder() {
        val m = importManifest("address,lat,lng\nA St 1,1,1\nB St 2,2,2\nA St 1,1,1\nC St 3,3,3\n")
        assertEquals(listOf(1, 2, 3), m.stops.map { it.entryNo }) // the repeated address keeps its first ID
    }

    @Test fun anAddedAddressStartsWithOnePackageAndAnotherGetsTheNextNumber() {
        val s = RouteSession(emptyList(), emptyList())
        val a = s.addStop(stop("a", "469 Fair Oaks Rd"))
        assertEquals(listOf("1-1"), s.packages.map { it.id })
        assertEquals("1-2", s.addPackageTo(a.id)!!.id)
        assertEquals("1-3", s.addPackageTo(a.id)!!.id)
        assertEquals(3, s.finder(a).size)
        assertNull(s.addPackageTo("nope"))
    }

    @Test fun sameAddressIsRecognizedAcrossFormats() {
        assertTrue(AddressMatch.same("469 Fair Oaks Rd, Crossville TN 38572", "469 FAIR OAKS ROAD, Crossville, TN 38572, USA"))
        assertTrue(AddressMatch.same("8681 US-70", "8681 Highway 70 East, Crab Orchard"))
        assertFalse(AddressMatch.same("469 Fair Oaks Rd", "496 Fair Oaks Rd"))
        assertFalse(AddressMatch.same("469 Fair Oaks Rd, Town TN 38572", "469 Fair Oaks Rd, Town TN 37201"))
        assertFalse(AddressMatch.same("10 Elm St Apt 4", "10 Elm St Apt 5"))
        assertTrue(AddressMatch.same("10 Elm St Apt 4", "10 Elm St"))
    }

    @Test fun pendingStopLookupUsesTheSmarterMatch() {
        val s = RouteSession(emptyList(), emptyList())
        s.addStop(stop("a", "469 Fair Oaks Road, Crossville, TN 38572, USA"))
        assertEquals("a", s.pendingStopAt("469 Fair Oaks Rd, Crossville TN 38572")?.id)
        s.mark("a", Status.COMPLETED)
        assertNull(s.pendingStopAt("469 Fair Oaks Rd, Crossville TN 38572")) // a finished stop is not "on the route" any more
    }
}
