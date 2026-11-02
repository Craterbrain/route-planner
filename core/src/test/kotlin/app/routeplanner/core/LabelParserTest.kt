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

    @Test fun zipPlusFourSurvivesOcrMangledDashes() {
        // The recipient's "38555 – 6923" is where OCR goes wrong; losing it left only the return address.
        for (tail in listOf("38555 – 6923", "38555-6923", "38555 6923", "38555.6923", "38555_6923", "38555~6923", "38555—6923", "38555 - 69Z3", "38555 -6923 |", "38555 – 6923 ©", "38555 6923 4", "38555- 692")) {
            val r = LabelParser.parse(listOf(
                "CARRIER - LEAVE IF NO RESPONSE" to 26f, "USPS PARCEL SELECT" to 48f,
                "AMAZON.COM.INDC LLC" to 22f, "900 PATROL RD" to 22f, "JEFFERSONVILLE IN 47130" to 22f, "R012" to 34f,
                "ADDRESS SERVICE REQUESTED" to 20f,
                "KIMBERLY BRUBAKER" to 36f, "8681 HIGHWAY 70 E" to 36f, "CROSSVILLE TN $tail" to 36f,
                "USPS TRACKING # eVS" to 26f,
            ).map { LabelParser.OcrLine(it.first, it.second) })
            assertEquals(tail, listOf("8681 Highway 70 E, Crossville TN 38555", "900 Patrol Rd, Jeffersonville IN 47130"), r.addresses)
            assertTrue(tail, r.candidates[1].isReturn)
        }
    }

    @Test fun aStreetLineWithAFiveDigitNumberAndNoZipIsNotACityLine() {
        assertNull(LabelParser.parse("12345 6789\n8681 HWY 70 E").primary)
    }

    @Test fun tracksTheLineOrderStreetThenCityWhenBothAreAdjacent() {
        // From a real scan log: ML Kit gave name, CITY line, street line (wrong order), which found nothing at all.
        // The scanner now orders lines by position itself; the parser still needs street directly above city.
        val r = LabelParser.parse(listOf(
            "KIMBERLY BRUBAKER" to 43f, "8681 HIGHWAY 70E" to 43f, "CROSSVILLE TN 38555-6923" to 43f,
        ).map { LabelParser.OcrLine(it.first, it.second) })
        assertEquals("8681 Highway 70e, Crossville TN 38555", r.primary!!.address)
    }

    @Test fun strayLinesBetweenTheStreetAndCityDoNotHideTheAddress() {
        val r = LabelParser.parse(listOf("DONALD NGUYEN", "1407 KAWA CT", "S.", "Start of my test", "APT 11", "CROSSVILLE TN 38572-1287")
            .map { LabelParser.OcrLine(it, 40f) })
        assertEquals("1407 Kawa Ct Apt 11, Crossville TN 38572", r.primary!!.address)
        // ... but a second street line is never swallowed as junk.
        val two = LabelParser.parse(listOf("1 A ST", "2 B ST", "TOWN TN 11111").map { LabelParser.OcrLine(it, 40f) })
        assertEquals(listOf("2 B St, Town TN 11111"), two.addresses)
    }

    @Test fun endorsementAboveTheAddressMakesItTheRecipientEvenAtEqualPrintSize() {
        val r = LabelParser.parse(listOf("RO68" to 23f, "9691 SAMPLE WAY" to 23f, "NASHVILLE TN 37201" to 21f, "ADDRESS SERVICE REQUESTED" to 14f,
            "CHARLES MITCHELL" to 20f, "289 NEWTON LN" to 20f, "LOT 33" to 19f, "CROSSVILLE TN 38572-8497" to 19f).map { LabelParser.OcrLine(it.first, it.second) })
        assertEquals("289 Newton Ln Lot 33, Crossville TN 38572", r.primary!!.address)
        assertEquals(LabelParser.Role.DELIVERY, r.primary!!.role)
    }

    @Test fun ocrNoiseInTheStreetLineIsCleaned() {
        assertEquals("17 Killearn Ct, Fairfield Glade TN 38558", LabelParser.parse("E 17 KILLEARN CT\nFAIRFIELD GLADE TN 38558-7656").primary!!.address)
        assertEquals("1035 Oceola Cir, Crossville TN 38572", LabelParser.parse("1035 0CEOLA CIR\nCROSSVILLE TN 38572").primary!!.address)
        assertEquals("510 10th St, Crossville TN 38555", LabelParser.parse("510 10TH ST\nCROSSVILLE TN 38555").primary!!.address)
    }

    @Test fun aStreetTypeRunIntoTheNameIsSplitButOrdinaryWordsAreNot() {
        assertEquals("32 Dawn Ct, Crossville TN 38555", LabelParser.parse("32 DAWNCT\nCROSSVILLE TN 38555-1259").primary!!.address)
        assertEquals("9 Old Mill Ln, Crossville TN 38555", LabelParser.parse("9 OLD MILLLN\nCROSSVILLE TN 38555").primary!!.address)
        assertEquals("700 Forest Dr, Crossville TN 38555", LabelParser.parse("700 FOREST DR\nCROSSVILLE TN 38555").primary!!.address)
        assertEquals("12 Howard Rd, Crossville TN 38555", LabelParser.parse("12 HOWARD RD\nCROSSVILLE TN 38555").primary!!.address)
        assertEquals("5 Direct Ct, Crossville TN 38555", LabelParser.parse("5 DIRECT CT\nCROSSVILLE TN 38555").primary!!.address)
    }

    @Test fun aCityLineBrokenInTwoIsPutBackTogether() {
        val r = LabelParser.parse(listOf("PAMELA EDWARDS", "295 TIMOTHY DRIVE", "CROSSVILLE TN", "38572-7882").map { LabelParser.OcrLine(it, 50f) })
        assertEquals("295 Timothy Drive, Crossville TN 38572", r.primary!!.address)
    }

    @Test fun zipPlusFourRunTogetherWithoutADashIsStillAZip() {
        assertEquals("214 Lollar Pl Apt 2, Monterey TN 38574", LabelParser.parse("214 LOLLAR PL\nAPT 2\nMONTEREY TN 385744076").primary!!.address)
        assertEquals("214 Lollar Pl, Monterey TN 38574", LabelParser.parse("214 LOLLAR PL\nMONTEREY TN\n385744076").primary!!.address)
    }

    @Test fun anAddressJustAboveTheEndorsementIsTheReturnBlock() {
        val r = LabelParser.parse(listOf("SAMPLE FULFILLMENT CO", "6276 SAMPLE WAY", "NASHVILLE TN 37201", "RO31", "ADDRESS SERVICE REQUESTED").map { LabelParser.OcrLine(it, 40f) })
        assertEquals(LabelParser.Role.RETURN, r.primary!!.role)
        // the recipient below it is still the delivery address and comes first
        val both = LabelParser.parse(listOf("6276 SAMPLE WAY", "NASHVILLE TN 37201", "ADDRESS SERVICE REQUESTED", "96 MEADOWVIEW LN", "CROSSVILLE TN 38555").map { LabelParser.OcrLine(it, 40f) })
        assertEquals("96 Meadowview Ln, Crossville TN 38555", both.primary!!.address)
        assertEquals(LabelParser.Role.RETURN, both.candidates[1].role)
    }

    @Test fun aMisreadZipDigitStillGivesTheAddress() {
        assertEquals("96 Meadowview Ln, Crossville TN", LabelParser.parse("96 MEADOWVIEW LN\nCROSSVILLE TN ${'$'}8555-7116").primary!!.address)
        assertEquals("96 Meadowview Ln, Crossville TN 38555", LabelParser.parse("96 MEADOWVIEW LN\nCROSSVILLE TN 3B555").primary!!.address)
    }
}
