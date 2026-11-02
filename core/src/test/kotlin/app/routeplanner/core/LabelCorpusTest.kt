package app.routeplanner.core

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Replays recognizer output saved by tools/label-lab/bench.py --save-failures: each fixture holds the lines ML Kit read off a
 * rendered label (already inside the label and in reading order) plus the address that was printed on it. The parser must
 * offer that address first, or one the geocoder's fuzzy search would resolve to it (right number, a close street word, right city).
 */
class LabelCorpusTest {
    private fun norm(s: String) = s.uppercase().replace(Regex("[^A-Z0-9 ]"), "").trim()

    private fun close(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return 1.0 - d[a.length][b.length].toDouble() / maxOf(a.length, b.length) >= 0.7
    }

    /** -PlabelCorpus=DIR (from bench.py --dump-all): reports the parser's pass rate over a whole bench run; never fails. */
    @Test fun wholeBenchRunPassRate() {
        val dir = File(System.getProperty("labelCorpus") ?: return)
        val (total, failures) = replay(dir)
        println("LABEL CORPUS: ${total - failures.size}/$total parsed to the true address")
        File(dir, "failures.txt").writeText(failures.joinToString("\n"))
    }

    @Test fun savedLabelsParseToTheirTrueAddress() {
        val (total, failures) = replay(File(javaClass.getResource("/labelcorpus")?.toURI() ?: return))
        if (failures.isNotEmpty()) fail("${failures.size} of $total saved labels misparsed:\n" + failures.joinToString("\n"))
    }

    private fun replay(dir: File): Pair<Int, List<String>> {
        val failures = ArrayList<String>()
        var total = 0
        for (f in dir.listFiles { x -> x.extension == "json" }.orEmpty().sortedBy { it.name }) {
            total++
            val j = Json.parse(f.readText()) as Map<*, *>
            val truth = j["truth"] as Map<*, *>
            val lines = (j["lines"] as List<*>).map { it as Map<*, *> }
                .map { LabelParser.OcrLine(it["t"] as String, ((it["b"] as Number).toInt() - (it["tp"] as Number).toInt()).toFloat()) }
            val c = LabelParser.parse(lines).primary
            val parts = norm(truth["street"] as String).split(" ")
            val words = c?.let { norm(it.address).split(" ") }.orEmpty()
            val ok = c != null && c.role != LabelParser.Role.RETURN && parts[0] in words && (truth["zip5"] as String) in words &&
                (parts[1] in words || words.any { close(parts[1], it) }) && norm((truth["city"] as String).split(" ")[0]).take(4) in norm(c.address)
            if (!ok) failures += "${f.name}: want ${truth["street"]} ${truth["unit"]}, ${truth["city"]} ${truth["zip5"]}; got ${c?.address} (${c?.role}); lines ${lines.map { it.text }}"
        }
        return total to failures
    }
}
