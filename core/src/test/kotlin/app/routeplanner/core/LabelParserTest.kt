package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class LabelParserTest {
    private val label = """
        FROM: ACME SUPPLY CO
        100 INDUSTRIAL WAY
        NASHVILLE TN 37201
        SHIP TO
        JANE DOE
        8681 HWY 70 E
        APT 4
        CROSSVILLE TN 38555
        (931) 555-0142
        UPS GROUND
        1Z999AA10123456784
    """.trimIndent()

    @Test fun cueWordsMakeTheFromAddressTheReturnAddress() {
        val r = LabelParser.parse(label)
        // FROM block comes first on the label, but SHIP TO is the delivery address.
        assertEquals(listOf("8681 Hwy 70 E Apt 4, Crossville TN 38555", "100 Industrial Way, Nashville TN 37201"), r.addresses)
        assertEquals(LabelParser.Role.DELIVERY, r.primary!!.role)
        assertTrue(r.candidates[1].isReturn)
    }

    @Test fun biggerPrintIsTheDeliveryAddressWhenThereAreNoCues() {
        val lines = listOf(
            LabelParser.OcrLine("ACME SUPPLY", 22f), LabelParser.OcrLine("100 INDUSTRIAL WAY", 22f), LabelParser.OcrLine("NASHVILLE TN 37201", 22f),
            LabelParser.OcrLine("JANE DOE", 60f), LabelParser.OcrLine("8681 HWY 70 E", 60f), LabelParser.OcrLine("CROSSVILLE TN 38555", 58f),
        )
        val r = LabelParser.parse(lines)
        assertEquals("8681 Hwy 70 E, Crossville TN 38555", r.primary!!.address)
        assertEquals("100 Industrial Way, Nashville TN 37201", r.candidates[1].address)
        assertTrue(r.candidates[1].isReturn)
        assertFalse(r.primary!!.isReturn)
    }

    @Test fun similarSizesKeepReadingOrderAndAreNotCalledReturn() {
        val r = LabelParser.parse(listOf(
            LabelParser.OcrLine("1 A ST", 40f), LabelParser.OcrLine("TOWN TN 11111", 40f),
            LabelParser.OcrLine("2 B ST", 38f), LabelParser.OcrLine("CITY TN 22222", 38f),
        ))
        assertEquals(listOf("1 A St, Town TN 11111", "2 B St, City TN 22222"), r.addresses)
        assertTrue(r.candidates.none { it.isReturn })
    }

    @Test fun aLoneReturnAddressIsStillOfferedAndFlagged() {
        val r = LabelParser.parse("RETURN ADDRESS\n5 Elm Rd\nCrossville TN 38555")
        assertEquals(1, r.candidates.size)
        assertTrue(r.primary!!.isReturn)
    }

    @Test fun oneLineAddressIsKeptAsTyped() {
        val r = LabelParser.parse("Deliver to 22 Oak Ave\n22 Oak Ave, Crossville TN 38555")
        assertEquals(listOf("22 Oak Ave, Crossville TN 38555"), r.addresses)
    }

    @Test fun phoneNumbersTrackingNumbersAndNonStatesAreIgnored() {
        val r = LabelParser.parse("555 123 4567\nFOO XX 12345\n12 MAIN ST\nSPRINGFIELD ZZ 11111\n1Z999AA10123456784\n794644790138")
        assertTrue(r.addresses.isEmpty())
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
