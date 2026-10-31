package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class LabelParserTest {
    private val label = """
        FROM: ACME SUPPLY CO
        100 INDUSTRIAL WAY
        NASHVILLE TN 37201
        SHIP TO:
        JANE DOE
        8681 HWY 70 E
        APT 4
        CROSSVILLE TN 38555
        (931) 555-0142
        UPS GROUND
        1Z999AA10123456784
    """.trimIndent()

    @Test fun findsBothAddressesAndTheUpsNumber() {
        val r = LabelParser.parse(label)
        assertEquals(listOf("100 Industrial Way, Nashville TN 37201", "8681 Hwy 70 E Apt 4, Crossville TN 38555"), r.addresses)
        assertEquals(listOf("1Z999AA10123456784"), r.tracking)
    }

    @Test fun oneLineAddressIsKeptAsTyped() {
        val r = LabelParser.parse("Deliver to 22 Oak Ave\n22 Oak Ave, Crossville TN 38555")
        assertEquals(listOf("22 Oak Ave, Crossville TN 38555"), r.addresses)
    }

    @Test fun phoneNumbersAndNonStatesAreNotAddresses() {
        val r = LabelParser.parse("555 123 4567\nFOO XX 12345\n12 MAIN ST\nSPRINGFIELD ZZ 11111")
        assertTrue(r.addresses.isEmpty())
    }

    @Test fun trackingFormats() {
        assertEquals(listOf("794644790138"), LabelParser.tracking("FedEx 7946 4479 0138"))
        assertEquals(listOf("9400100000000000000000"), LabelParser.tracking("USPS 9400 1000 0000 0000 0000 00"))
        assertTrue(LabelParser.tracking("Order 12345678").isEmpty())
        assertTrue(LabelParser.tracking("Phone 931 555 0142").isEmpty())
    }

    @Test fun packageLookupByCode() {
        val s = RouteSession(
            listOf(Stop("a", "1 Main St", LatLng(1.0, 2.0), optimizedIndex = 1)),
            listOf(Pkg("1Z999AA10123456784", "a", "Shelf A")),
        )
        val (p, st) = s.findPackage("1z999aa1 0123456784")!!
        assertEquals("Shelf A", p.truckZone); assertEquals("a", st.id)
        assertNull(s.findPackage("nope"))
        s.addPackage(Pkg("P2", "a")); s.addPackage(Pkg("P2", "a"))
        assertEquals(2, s.packages.size)
        assertEquals("a", s.pendingStopAt("  1  main ST ")?.id)
    }
}
