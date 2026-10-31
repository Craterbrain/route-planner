package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset

class DeliveryReportTest {
    private fun stop(id: String, n: Int, addr: String) = Stop(id, addr, LatLng(1.0, 2.0), optimizedIndex = n)

    @Test fun csvQuotingAndColumns() {
        assertEquals("plain", DeliveryReport.field("plain"))
        assertEquals("\"a,b\"", DeliveryReport.field("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", DeliveryReport.field("say \"hi\""))
    }

    @Test fun reportListsFinishedStopsOnlyWithProofFiles() {
        val s = RouteSession(
            listOf(stop("a", 1, "1 Main St, Crossville"), stop("b", 2, "2 Oak Ave"), stop("c", 3, "3 Elm Rd")),
            listOf(Pkg("P1", "a", "Shelf A"), Pkg("P2", "a", null)),
            now = { 1_700_000_000_000 },
        )
        s.mark("a", Status.COMPLETED, note = "Left, back porch", photo = "/data/proof/a-1.jpg", signature = "/data/proof/a-sig.png")
        s.mark("b", Status.FAILED, FailReason.ACCESS_CODE_NEEDED)
        val csv = DeliveryReport.csv(s.stops, s.packages, ZoneOffset.UTC)
        val lines = csv.trimEnd().split("\r\n")
        assertEquals(3, lines.size) // header + 2 finished stops, the pending one is left out
        assertEquals(DeliveryReport.COLUMNS.joinToString(","), lines[0])
        assertEquals("1,\"1 Main St, Crossville\",completed,2023-11-14 22:13,,P1; P2,\"Left, back porch\",a-1.jpg,a-sig.png,", lines[1])
        assertEquals("2,2 Oak Ave,failed,2023-11-14 22:13,Access code needed,,,,,", lines[2])
        assertEquals("1 delivered, 1 failed, 0 skipped, 1 pending", DeliveryReport.summary(s.stops))
    }

    @Test fun signatureSurvivesUndoReset() {
        val s = RouteSession(listOf(stop("a", 1, "x")), emptyList())
        s.mark("a", Status.COMPLETED, signature = "/s.png")
        assertEquals("/s.png", s.stops[0].proofSignature)
        s.undo("a")
        assertNull(s.stops[0].proofSignature)
    }
}
