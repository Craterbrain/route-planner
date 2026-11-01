package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class AutoSaveHookTest {
    private fun session(): Pair<RouteSession, IntArray> {
        val s = RouteSession(emptyList(), emptyList())
        val n = IntArray(1)
        s.onChanged = { n[0]++ }
        return s to n
    }

    @Test fun everyKindOfChangeNotifies() {
        val (s, n) = session()
        val a = s.addStop(Stop("a", "1 A St", LatLng(1.0, 1.0))); assertTrue("addStop", n[0] > 0)
        s.addStop(Stop("b", "2 B St", LatLng(2.0, 2.0)))
        fun changes(what: String, f: () -> Unit) { val before = n[0]; f(); assertTrue(what, n[0] > before) }
        changes("mark delivered") { s.mark(a.id, Status.COMPLETED, note = "x") }
        changes("undo") { s.undo(a.id) }
        changes("start") { s.start() }
        changes("stop") { s.stop() }
        changes("move") { s.moveStop(a.id, 1) }
        changes("edit notes") { s.edit(a.id) { it.copy(notes = "gate") } }
        changes("zone") { s.setZone("${s.stops.first { it.id == a.id }.entryNo}-1", "Shelf A") }
        changes("add package") { s.addPackageTo(a.id) }
        changes("remove") { s.removeStop(a.id) }
        changes("end") { s.end = RouteEnd(EndMode.ROUND_TRIP) }
        changes("start point") { s.start = RouteStart(LatLng(1.0, 2.0), "x") }
        changes("nav app") { s.navApp = NavApp.WAZE }
        changes("auto nav") { s.autoNavigate = true }
        changes("name") { s.name = "Monday" }
        changes("saved id") { s.savedId = "abc" }
    }

    @Test fun settingTheSameValueIsNotAChange() {
        val (s, n) = session()
        s.name = "x"; s.started = true; s.navApp = NavApp.WAZE
        val before = n[0]
        s.name = "x"; s.started = true; s.navApp = NavApp.WAZE; s.dirty = false
        assertEquals(before, n[0])
    }

    @Test fun readingNeverNotifiesAndNoListenerIsFine() {
        val (s, n) = session()
        s.addStop(Stop("a", "1 A St", LatLng(1.0, 1.0)))
        val before = n[0]
        s.current; s.stops; s.packages; s.summary(); s.finder(s.stops[0]); s.endPoint()
        assertEquals(before, n[0])
        s.onChanged = null
        s.addStop(Stop("b", "2 B St", LatLng(2.0, 2.0))) // must not throw
    }
}
