package app.routeplanner.labelbench

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import app.routeplanner.core.LabelParser
import app.routeplanner.core.LabelReading
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs every picture in `<external files>/<dir>` (default "in") through the scanner's pipeline and writes one JSON line per
 * picture to `<external files>/out.jsonl`: the recognizer's raw lines with their boxes, the ordered lines the parser saw, the
 * addresses it offered, the barcodes read, and how long it took. Writes `done.txt` when finished. Extras: `dir`, `region`
 * (0 = read the whole picture; default 1 = only the bright label, as the live scanner does), `threads` (default 3).
 */
class BenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val base = getExternalFilesDir(null)!!
        val dir = File(base, intent.getStringExtra("dir") ?: "in")
        val useRegion = intent.getStringExtra("region") != "0"
        val threads = intent.getStringExtra("threads")?.toIntOrNull() ?: 3
        Thread {
            val t0 = System.currentTimeMillis()
            val files = dir.listFiles { f -> f.extension.lowercase() in setOf("png", "jpg", "jpeg") }?.sortedBy { it.name }.orEmpty()
            val out = File(base, "out.jsonl"); out.delete()
            File(base, "done.txt").delete()
            val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val barcodes = BarcodeScanning.getClient()
            val pool = Executors.newFixedThreadPool(threads)
            val writer = out.bufferedWriter()
            for (f in files) pool.execute {
                val line = runCatching { one(f, text, barcodes, useRegion) }.getOrElse { JSONObject().put("file", f.name).put("error", it.toString()) }
                synchronized(writer) { writer.write(line.toString()); writer.newLine() }
            }
            pool.shutdown(); pool.awaitTermination(30, TimeUnit.MINUTES)
            writer.close()
            File(base, "done.txt").writeText("${files.size} pictures in ${System.currentTimeMillis() - t0} ms\n")
            Log.i("LabelBench", "done: ${files.size} pictures in ${System.currentTimeMillis() - t0} ms")
            runOnUiThread { finish() }
        }.start()
    }

    private fun one(f: File, text: com.google.mlkit.vision.text.TextRecognizer, codes: com.google.mlkit.vision.barcode.BarcodeScanner, useRegion: Boolean): JSONObject {
        val t0 = System.nanoTime()
        val bmp = BitmapFactory.decodeFile(f.path) ?: error("can't decode ${f.name}")
        val c = Tasks.await(codes.process(InputImage.fromBitmap(bmp, 0)))
        val box = { l: com.google.mlkit.vision.text.Text.Line -> l.boundingBox?.let { LabelReading.Box(it.left, it.top, it.right, it.bottom) } }
        val luma = { x: Int, y: Int -> val p = bmp.getPixel(x, y); ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000 }
        // The same steps as the live scanner: find the bright label, keep its lines, order, parse.
        var best: Triple<Int, LabelParser.Result, Pair<List<com.google.mlkit.vision.text.Text.Line>, Int>>? = null
        var rawAll: List<com.google.mlkit.vision.text.Text.Line> = emptyList()
        var regionCells: Int? = null
        for (rot in intArrayOf(0)) {          // upright only: labels are held upright
            val t = Tasks.await(text.process(InputImage.fromBitmap(bmp, rot)))
            val raw = t.textBlocks.flatMap { it.lines }
            val region = if (useRegion) LabelReading.labelRegion(bmp.width, bmp.height, rot, luma) else null
            val kept = LabelReading.order(LabelReading.inside(raw, region, box), box)
            val result = LabelParser.parse(kept.map { LabelParser.OcrLine(it.text, (it.boundingBox?.height() ?: 0).toFloat()) })
            val delivery = result.candidates.any { it.role != LabelParser.Role.RETURN }
            val score = (if (delivery) 100 else 0) + result.candidates.size
            if (best == null || score > best.first) { best = Triple(score, result, kept to rot); rawAll = raw; regionCells = region?.mask?.count { it } }
            if (delivery) break
        }
        val (_, result, keptRot) = best!!
        val (kept, rot) = keptRot
        val lines = kept.map { LabelParser.OcrLine(it.text, (it.boundingBox?.height() ?: 0).toFloat()) }
        return JSONObject()
            .put("file", f.name)
            .put("ms", (System.nanoTime() - t0) / 1_000_000)
            .put("rot", rot)
            .put("raw", JSONArray(rawAll.mapNotNull { l -> l.boundingBox?.let { JSONObject().put("t", l.text).put("l", it.left).put("tp", it.top).put("r", it.right).put("b", it.bottom) } }))
            .put("kept", JSONArray(kept.mapNotNull { l -> l.boundingBox?.let { JSONObject().put("t", l.text).put("l", it.left).put("tp", it.top).put("r", it.right).put("b", it.bottom) } }))
            .put("regionCells", regionCells ?: JSONObject.NULL)
            .put("lines", JSONArray(lines.map { JSONObject().put("text", it.text).put("h", it.height.toInt()) }))
            .put("candidates", JSONArray(result.candidates.map { JSONObject().put("address", it.address).put("role", it.role.name).put("h", it.height.toInt()).put("name", it.name ?: JSONObject.NULL) }))
            .put("barcodes", JSONArray(c.map { JSONObject().put("format", it.format).put("value", it.rawValue ?: "") }))
    }
}
