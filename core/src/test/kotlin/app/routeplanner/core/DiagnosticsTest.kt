package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    private fun thrower(): Nothing = throw IllegalStateException("Couldn't find \"469 Fair Oaks Rd, Crossville\" https://photon.example/api?q=469+Fair+Oaks")

    @Test fun reportKeepsClassesAndLocationsButNeverTheMessage() {
        val e = runCatching { thrower() }.exceptionOrNull()!!
        val wrapped = RuntimeException("while adding 22 Oak Ave", e)
        val text = Diagnostics.describe(wrapped)
        assertTrue(text.contains("java.lang.RuntimeException [message omitted]"))
        assertTrue(text.contains("Caused by: java.lang.IllegalStateException [message omitted]"))
        assertTrue(text.contains("DiagnosticsTest.thrower(DiagnosticsTest.kt:"))
        assertFalse(text.contains("Fair Oaks")); assertFalse(text.contains("Oak Ave")); assertFalse(text.contains("photon"))
    }

    @Test fun longTracesAreCutAndCycleSafe() {
        val e = RuntimeException("x")
        e.stackTrace = Array(100) { StackTraceElement("C", "m$it", "C.kt", it) }
        assertTrue(Diagnostics.describe(e, maxFrames = 10).contains("... 90 more"))
        val a = RuntimeException("a"); val b = RuntimeException("b", a); a.initCause(b)
        assertNotNull(Diagnostics.describe(b)) // a cause cycle must not loop forever
    }
}
