package app.routeplanner.core.address

import app.routeplanner.core.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AddrNormTest {
    private val cases: List<Pair<String, String>> by lazy {
        val path = System.getProperty("addressCases") ?: "../tools/address-normalize-cases.json"
        (Json.parse(File(path).readText()) as List<*>).map { c ->
            c as Map<*, *>
            (c["input"] as String) to (c["norm"] as String)
        }
    }

    @Test fun sharedVectorsAllPass() {
        assertEquals(124, cases.size)
        val failures = cases.filter { (input, norm) -> AddrNorm.normalizeStreet(input) != norm }
            .map { (input, norm) -> "'$input' -> '${AddrNorm.normalizeStreet(input)}' (want '$norm')" }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun idempotent() {
        for ((_, norm) in cases) assertEquals(norm, AddrNorm.normalizeStreet(norm))
    }

    @Test fun numberPlaceUnit() {
        assertEquals("8681", AddrNorm.normalizeNumber("8681"))
        assertEquals("12b", AddrNorm.normalizeNumber("12-B"))
        assertEquals("121/2", AddrNorm.normalizeNumber(" 12 1/2 "))
        assertEquals(listOf("mt", "juliet"), AddrNorm.normalizePlace("Mount Juliet"))
        assertEquals(listOf("crab", "orchard"), AddrNorm.normalizePlace("CRAB ORCHARD"))
        assertEquals("4b", AddrNorm.normalizeUnit("Apt. 4-B"))
        assertEquals("12", AddrNorm.normalizeUnit("#12"))
    }

    @Test fun splitHouseNumberDocExamples() {
        assertEquals(AddrNorm.House("8681", 8681, ""), AddrNorm.splitHouseNumber("8681", ""))
        assertEquals(AddrNorm.House("509", 509, "a"), AddrNorm.splitHouseNumber("509 A", ""))
        assertEquals(AddrNorm.House("112", 112, "b"), AddrNorm.splitHouseNumber("112B", ""))
        assertEquals(AddrNorm.House("101", 101, "1/2"), AddrNorm.splitHouseNumber("101 1/2", ""))
        assertEquals(AddrNorm.House("225", 225, "13"), AddrNorm.splitHouseNumber("225 LOT", "LOT 13"))
        assertEquals(AddrNorm.House("509", 509, "b102"), AddrNorm.splitHouseNumber("509 B", "102"))
        assertEquals(-1, AddrNorm.splitHouseNumber("A12", "").num)
    }
}
