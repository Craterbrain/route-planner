package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class BarcodesTest {
    private val tracking = "9361289724269887364749"   // USPS tracking number, as printed under the barcode
    private val code128 = "420385729361289724269887364749" // what the barcode itself holds: "420" + ZIP + tracking

    @Test fun formattingAndPrefixesDoNotMatter() {
        assertTrue(Barcodes.same("9361 2897 2426 9887 3647 49", tracking))
        assertTrue(Barcodes.same(code128, "9361 2897 2426 9887 3647 49")) // long value inside a longer one
        assertFalse(Barcodes.same("FSD1", "FSD1X"))
        assertTrue(Barcodes.same("fsd1", "FSD-1")) // short codes only match when equal after cleaning
        assertFalse(Barcodes.same("", ""))
        assertFalse(Barcodes.same("ABCDE", "ABCDEFGHIJKL")) // a short code never matches by containment
    }

    private val a = Pkg("1-1", "A", codes = listOf(code128, "FSD1", "PXL43K2ZH"))
    private val b = Pkg("2-1", "B", codes = listOf("9400100000000000000001", "FSD1"))

    @Test fun aLongCodeIdentifiesTheParcel() {
        val m = Barcodes.find(listOf(a, b), listOf("FSD1", tracking))!!
        assertTrue(m.certain); assertEquals(listOf("1-1"), m.packages.map { it.id })
    }

    @Test fun onlyAShortSharedCodeIsAmbiguous() {
        val m = Barcodes.find(listOf(a, b), listOf("FSD1"))!!
        assertFalse(m.certain); assertEquals(listOf("1-1", "2-1"), m.packages.map { it.id })
    }

    @Test fun unknownCodesDoNotMatch() {
        assertNull(Barcodes.find(listOf(a, b), listOf("ZZZ999999999")))
        assertNull(Barcodes.find(listOf(a, b), emptyList()))
    }

    @Test fun anImportedPackageIdCountsAsACode() {
        val imported = Pkg("1Z999AA10123456784", "C")
        assertEquals("C", Barcodes.find(listOf(imported), listOf("1z999aa1 0123456784"))!!.packages.single().stopId)
    }

    @Test fun sessionRemembersCodesAndFindsThemAgain() {
        val s = RouteSession(emptyList(), emptyList())
        val st = s.addStop(Stop("x", "469 Fair Oaks Rd", LatLng(1.0, 2.0)), codes = listOf(code128))
        val second = s.addPackageTo(st.id, listOf("9400100000000000000009"))!!
        assertEquals("1-2", second.id)
        assertEquals("1-1", s.scanMatch(listOf(tracking))!!.packages.single().id)
        assertEquals("1-2", s.findPackage("9400 1000 0000 0000 0000 09")!!.first.id)
        assertEquals(st.id, s.stopOf(second)!!.id)
    }

    @Test fun longCodesAreCutAtTheFrontAndTheLastFourAreMarked() {
        val long = Barcodes.shown("420385729361289724269887364749")
        assertEquals("…269887364749", long.text) // "…" + the last 12 characters
        assertEquals("4749", long.text.substring(long.highlightStart))
        val short = Barcodes.shown("794644790138")                 // 12 characters: shown whole
        assertEquals("794644790138", short.text); assertEquals("0138", short.text.substring(short.highlightStart))
        val tiny = Barcodes.shown("FD1")
        assertEquals("FD1", tiny.text); assertEquals(0, tiny.highlightStart)
        assertEquals("…ABCDEFGHIJKL", Barcodes.shown("  1234567ABCDEFGHIJKL ").text)
    }
}
