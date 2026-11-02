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
        val crop = intent.getStringExtra("crop") == "1"                       // read only the label's box, not the whole picture
        val maxDim = intent.getStringExtra("maxdim")?.toIntOrNull() ?: 0      // downscale the read so its longer side is at most this
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
                val line = runCatching { one(f, text, barcodes, useRegion, crop, maxDim) }.getOrElse { JSONObject().put("file", f.name).put("error", it.toString()) }
                synchronized(writer) { writer.write(line.toString()); writer.newLine() }
            }
            pool.shutdown(); pool.awaitTermination(30, TimeUnit.MINUTES)
            writer.close()
            File(base, "done.txt").writeText("${files.size} pictures in ${System.currentTimeMillis() - t0} ms\n")
            Log.i("LabelBench", "done: ${files.size} pictures in ${System.currentTimeMillis() - t0} ms")
            runOnUiThread { finish() }
        }.start()
    }

    private fun one(f: File, text: com.google.mlkit.vision.text.TextRecognizer, codes: com.google.mlkit.vision.barcode.BarcodeScanner, useRegion: Boolean, crop: Boolean, maxDim: Int): JSONObject {
        val t0 = System.nanoTime()
        val bmp = BitmapFactory.decodeFile(f.path) ?: error("can't decode ${f.name}")
        val tDecode = System.nanoTime()
        val box = { l: com.google.mlkit.vision.text.Text.Line -> l.boundingBox?.let { LabelReading.Box(it.left, it.top, it.right, it.bottom) } }
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        val luma = { x: Int, y: Int -> val p = px[y * bmp.width + x]; ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000 }
        val region = if (useRegion) LabelReading.labelRegion(bmp.width, bmp.height, 0, luma) else null
        val tRegion = System.nanoTime()
        // What ML Kit reads: the whole picture, or just the label's bounding box (optionally shrunk).
        var ox = 0; var oy = 0; var scale = 1f
        var readBmp = bmp
        if (crop && region != null) {
            var l = bmp.width; var t = bmp.height; var r = 0; var b = 0
            for (cy in 0 until region.gh) for (cx in 0 until region.gw) if (region.mask[cy * region.gw + cx]) {
                l = minOf(l, cx * region.cell); t = minOf(t, cy * region.cell); r = maxOf(r, (cx + 1) * region.cell); b = maxOf(b, (cy + 1) * region.cell)
            }
            l = maxOf(0, l - region.cell); t = maxOf(0, t - region.cell); r = minOf(bmp.width, r + region.cell); b = minOf(bmp.height, b + region.cell)
            if (r - l > 64 && b - t > 64) { ox = l; oy = t; readBmp = android.graphics.Bitmap.createBitmap(bmp, l, t, r - l, b - t) }
        }
        if (maxDim > 0 && maxOf(readBmp.width, readBmp.height) > maxDim) {
            scale = maxDim.toFloat() / maxOf(readBmp.width, readBmp.height)
            readBmp = android.graphics.Bitmap.createScaledBitmap(readBmp, (readBmp.width * scale).toInt(), (readBmp.height * scale).toInt(), true)
        }
        val tPrep = System.nanoTime()
        val image = InputImage.fromBitmap(readBmp, 0)
        val textTask = text.process(image); val codeTask = codes.process(image)
        val t = Tasks.await(textTask); val tText = System.nanoTime()
        val c = Tasks.await(codeTask); val tCode = System.nanoTime()
        // Back to the picture's own coordinates, so the label region and the line order work as before.
        val raw = t.textBlocks.flatMap { it.lines }
        val back = { l: com.google.mlkit.vision.text.Text.Line -> l.boundingBox?.let { LabelReading.Box((it.left / scale).toInt() + ox, (it.top / scale).toInt() + oy, (it.right / scale).toInt() + ox, (it.bottom / scale).toInt() + oy) } }
        val kept = LabelReading.order(LabelReading.inside(raw, region, back), back)
        val lines = kept.map { LabelParser.OcrLine(it.text, (back(it)?.height ?: 0).toFloat()) }
        val result = LabelParser.parse(lines)
        val tEnd = System.nanoTime()
        val ms = { a: Long, b: Long -> (b - a) / 1_000_000 }
        fun boxes(ls: List<com.google.mlkit.vision.text.Text.Line>) = JSONArray(ls.mapNotNull { l -> back(l)?.let { JSONObject().put("t", l.text).put("l", it.left).put("tp", it.top).put("r", it.right).put("b", it.bottom) } })
        return JSONObject()
            .put("file", f.name)
            .put("ms", ms(t0, tEnd))
            .put("t", JSONObject().put("decode", ms(t0, tDecode)).put("region", ms(tDecode, tRegion)).put("prep", ms(tRegion, tPrep)).put("text", ms(tPrep, tText)).put("barcode", ms(tText, tCode)).put("parse", ms(tCode, tEnd)))
            .put("readPixels", readBmp.width * readBmp.height)
            .put("raw", boxes(raw))
            .put("kept", boxes(kept))
            .put("regionCells", region?.mask?.count { it } ?: JSONObject.NULL)
            .put("lines", JSONArray(lines.map { JSONObject().put("text", it.text).put("h", it.height.toInt()) }))
            .put("candidates", JSONArray(result.candidates.map { JSONObject().put("address", it.address).put("role", it.role.name).put("h", it.height.toInt()).put("name", it.name ?: JSONObject.NULL) }))
            .put("barcodes", JSONArray(c.map { JSONObject().put("format", it.format).put("value", it.rawValue ?: "") }))
    }
}
