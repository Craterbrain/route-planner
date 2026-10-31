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

    @Test fun similarSizesPutTheLowerAddressFirstAndNeitherIsCalledReturn() {
        val r = LabelParser.parse(listOf(
            LabelParser.OcrLine("1 A ST", 40f), LabelParser.OcrLine("TOWN TN 11111", 40f),
            LabelParser.OcrLine("2 B ST", 38f), LabelParser.OcrLine("CITY TN 22222", 38f),
        ))
        assertEquals(listOf("2 B St, City TN 22222", "1 A St, Town TN 11111"), r.addresses)
        assertTrue(r.candidates.none { it.isReturn })
    }

    // Lines as ML Kit would give them for a real USPS Parcel Select label (heights in pixels, from the photo).
    private val usps = listOf(
        "CARRIER - LEAVE IF NO RESPONSE" to 26f, "U.S. POSTAGE PAID" to 20f, "AFS" to 18f, "eVS" to 16f,
        "USPS PARCEL SELECT" to 48f,
        "FSD1" to 19f, "6700 N MARION RD" to 19f, "SIOUX FALLS SD 57107 - 6490" to 19f, "R049" to 34f,
        "ADDRESS SERVICE REQUESTED" to 22f,
        "JAMES VIDUSKI" to 27f, "469 FAIR OAKS RD" to 27f, "CROSSVILLE TN 38572-3338" to 27f,
        "USPS TRACKING # eVS" to 26f, "9361 2897 2426 9887 3647 49" to 22f,
        "FSD1" to 44f, "MDW5" to 44f, "BNA7" to 44f, "TYS5" to 44f, "D 001" to 40f, "B 001" to 40f,
    ).map { LabelParser.OcrLine(it.first, it.second) }

    @Test fun realUspsLabelRecipientIsPrimaryAndTheWarehouseIsTheReturnAddress() {
        val r = LabelParser.parse(usps)
        assertEquals(listOf("469 Fair Oaks Rd, Crossville TN 38572", "6700 N Marion Rd, Sioux Falls SD 57107"), r.addresses)
        assertFalse(r.candidates[0].isReturn)
        assertTrue(r.candidates[1].isReturn)
    }

    @Test fun recipientNameIsCapturedAndWarehouseCodesAreNot() {
        val r = LabelParser.parse(usps)
        assertEquals("James Viduski", r.candidates[0].name)
        assertNull(r.candidates[1].name) // "FSD1" has a digit, so it is not a name
        assertNull(LabelParser.parse("ADDRESS SERVICE REQUESTED\n5 ELM RD\nCROSSVILLE TN 38555").primary!!.name)
    }

    @Test fun uspsLabelStillWorksWhenOcrDropsTheSizes() {
        // No heights at all: the lower address is the recipient's.
        val r = LabelParser.parse(usps.map { LabelParser.OcrLine(it.text) })
        assertEquals("469 Fair Oaks Rd, Crossville TN 38572", r.primary!!.address)
    }

    @Test fun mailerEndorsementsAreNotReturnCues() {
        val r = LabelParser.parse("RETURN SERVICE REQUESTED\nJANE DOE\n22 OAK AVE\nCROSSVILLE TN 38555")
        assertEquals(LabelParser.Role.UNKNOWN, r.primary!!.role)
        assertFalse(r.primary!!.isReturn)
    }

    @Test fun zipPlusFourWithAnyDashIsRecognizedAndTrimmed() {
        for (zip in listOf("38572-3338", "38572 - 3338", "38572\u20133338", "38572 \u2014 3338", "38572")) {
            val r = LabelParser.parse("469 FAIR OAKS RD\nCROSSVILLE TN $zip")
            assertEquals(zip, listOf("469 Fair Oaks Rd, Crossville TN 38572"), r.addresses)
        }
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
