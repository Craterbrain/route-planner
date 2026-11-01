package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class StopRoutingTest {
    @Test fun stoppingReturnsToPlanningAndKeepsProgress() {
        val s = RouteSession(emptyList(), emptyList())
        val a = s.addStop(Stop("a", "1 A St", LatLng(1.0, 1.0)))
        val b = s.addStop(Stop("b", "2 B St", LatLng(2.0, 2.0)))
        s.start()
        assertTrue(s.started)
        s.mark(a.id, Status.COMPLETED, note = "left it", photo = "/p.jpg")
        val cursor = s.cursor
        s.stop()
        assertFalse(s.started)
        assertEquals(Status.COMPLETED, s.stops.first { it.id == a.id }.status)
        assertEquals("left it", s.stops.first { it.id == a.id }.proofNote)
        assertEquals(Status.PENDING, s.stops.first { it.id == b.id }.status)
        assertEquals(cursor, s.cursor)
        s.started = true // "Resume" from the snackbar
        assertEquals(b.id, s.current!!.id)
    }
}
