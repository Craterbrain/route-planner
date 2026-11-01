package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class PruningTest {
    private val day = 86_400_000L
    private val now = 100 * day
    private val files = listOf(
        Pruning.FileInfo("/p/new.jpg", now - 2 * day, 1000),
        Pruning.FileInfo("/p/mid.jpg", now - 45 * day, 2000),
        Pruning.FileInfo("/p/old.jpg", now - 120 * day, 3000),
        Pruning.FileInfo("/p/old-sig.png", now - 200 * day, 400),
    )

    @Test fun olderThanADateKeepsRecentFiles() {
        assertEquals(listOf("/p/mid.jpg", "/p/old.jpg", "/p/old-sig.png"), Pruning.selectProof(files, now, 30, emptySet()).map { it.path })
        assertEquals(listOf("/p/old.jpg", "/p/old-sig.png"), Pruning.selectProof(files, now, 90, emptySet()).map { it.path })
        assertTrue(Pruning.selectProof(files, now, 365, emptySet()).isEmpty())
    }

    @Test fun nullMeansEverythingExceptWhatTheOpenRouteShows() {
        assertEquals(4, Pruning.selectProof(files, now, null, emptySet()).size)
        assertEquals(listOf("/p/new.jpg", "/p/old-sig.png"), Pruning.selectProof(files, now, null, setOf("/p/mid.jpg", "/p/old.jpg")).map { it.path })
        assertEquals(listOf("/p/old-sig.png"), Pruning.selectProof(files, now, 90, setOf("/p/old.jpg")).map { it.path })
    }

    @Test fun sizesAreReadable() {
        assertEquals("0 B", Pruning.size(0)); assertEquals("850 KB", Pruning.size(850_000)); assertEquals("12.3 MB", Pruning.size(12_300_000))
        assertEquals("1.5 GB", Pruning.size(1_500_000_000))
    }
}
