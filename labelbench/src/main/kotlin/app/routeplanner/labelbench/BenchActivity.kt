package app.routeplanner.labelbench

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
 * (0 = read the whole picture; default 1 = only the bright label, as the live scanner does), `threads` (default 3), `crop`, `maxdim`.
 * Opened by hand (no `dir` extra) it takes a zip of pictures instead (options.json inside holds the same settings) and offers
 * a results zip to share, so it can run with no cable: tools/label-lab/bench.py --send, then --score.
 */
class BenchActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var pick: Button
    private lateinit var share: Button
    private var results: Uri? = null
    @Volatile private var listenGen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val base = getExternalFilesDir(null)!!
        if (intent.hasExtra("dir")) {                       // started from a PC over adb
            val opts = Options(
                useRegion = intent.getStringExtra("region") != "0",
                threads = intent.getStringExtra("threads")?.toIntOrNull() ?: 3,
                crop = intent.getStringExtra("crop") == "1",           // read only the label's box, not the whole picture
                maxDim = intent.getStringExtra("maxdim")?.toIntOrNull() ?: 0)   // downscale the read so its longer side is at most this
            Thread { run(File(base, intent.getStringExtra("dir")!!), opts) {}; runOnUiThread { finish() } }.start()
            return
        }
        // Opened by hand: pick a zip of pictures (plus options.json), run it, then share the results zip (for example to AVS).
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { text = "Pick a bench zip: pictures plus an optional options.json."; textSize = 16f }
        pick = Button(this).apply { text = "Pick bench zip"; setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 1) } }
        share = Button(this).apply { text = "Share results"; isEnabled = false; setOnClickListener { shareResults() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 3, pad, pad)
            addView(status); addView(pick); addView(share)
            val host = android.widget.EditText(this@BenchActivity).apply {
                hint = "PC address, e.g. 192.168.1.251:8799"
                setText(getSharedPreferences("bench", MODE_PRIVATE).getString("host", "192.168.1.251:8799"))
            }
            addView(host)
            addView(Button(this@BenchActivity).apply { text = "Listen to PC"; setOnClickListener { listen(host.text.toString().trim()) } })
        })
        // Opened with host given (or once set before): start listening without a tap.
        intent.getStringExtra("host")?.let { listen(it) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data ?: return
        pick.isEnabled = false; share.isEnabled = false
        Thread {
            runCatching {
                val zip = process(contentResolver.openInputStream(uri)!!)
                saveToDownloads(zip)
            }.onFailure { e -> runOnUiThread { status.text = "Failed: $e" } }
            runOnUiThread { pick.isEnabled = true; share.isEnabled = results != null }
        }.start()
    }

    /** Unpacks a job zip, runs it and returns the results zip. */
    private fun process(input: java.io.InputStream): File {
        val base = getExternalFilesDir(null)!!
        val dir = File(base, "zip-in"); dir.deleteRecursively(); dir.mkdirs()
        var opts = Options()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (e.isDirectory || name.isEmpty()) continue
                if (name == "options.json") opts = Options.from(JSONObject(z.readBytes().decodeToString()))
                else File(dir, name).outputStream().use { z.copyTo(it) }
            }
        }
        run(dir, opts) { msg -> runOnUiThread { status.text = msg } }
        val zip = File(base, "results.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for (n in listOf("out.jsonl", "done.txt")) { z.putNextEntry(ZipEntry(n)); File(base, n).inputStream().use { it.copyTo(z) }; z.closeEntry() }
            z.putNextEntry(ZipEntry("options.json")); z.write(opts.toJson().toString().toByteArray()); z.closeEntry()
        }
        return zip
    }

    /** Polls the PC's job server (tools/label-lab/benchserver.py) until stopped: fetch a job, run it, post the results. */
    private fun listen(host: String) {
        val prefs = getSharedPreferences("bench", MODE_PRIVATE)
        prefs.edit().putString("host", host).apply()
        val gen = ++listenGen
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            while (gen == listenGen) {
                try {
                    val c = java.net.URL("http://$host/job").openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 4000; c.readTimeout = 4000
                    if (c.responseCode == 200) {
                        val job = JSONObject(c.inputStream.readBytes().decodeToString())
                        runOnUiThread { status.text = "Job ${job.getString("id")}: downloading" }
                        val zc = java.net.URL("http://$host${job.getString("zip")}").openConnection() as java.net.HttpURLConnection
                        val zip = process(zc.inputStream)
                        val up = java.net.URL("http://$host/result/${job.getString("id")}").openConnection() as java.net.HttpURLConnection
                        up.requestMethod = "POST"; up.doOutput = true; up.setFixedLengthStreamingMode(zip.length())
                        zip.inputStream().use { i -> up.outputStream.use { i.copyTo(it) } }
                        up.responseCode
                        runOnUiThread { status.text = "Job ${job.getString("id")} sent. Listening to $host" }
                    } else runOnUiThread { if (gen == listenGen && !status.text.startsWith("Job") && !status.text.contains("pictures")) status.text = "Listening to $host" }
                    c.disconnect()
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Can't reach $host (${e.javaClass.simpleName}); retrying" }
                }
                Thread.sleep(2000)
            }
        }.start()
    }

    private fun saveToDownloads(zip: File) {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "bench-results-${System.currentTimeMillis() / 1000}.zip")
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return
        contentResolver.openOutputStream(uri)!!.use { o -> zip.inputStream().use { it.copyTo(o) } }
        results = uri
        runOnUiThread { status.text = status.text.toString() + "\nSaved to Downloads; sending to AVS."; shareResults() }
    }

    private fun shareResults() {
        val uri = results ?: return
        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Straight to the AVS app, which uploads it to the PC's inbox; any other app if AVS isn't installed.
        try { startActivity(Intent(send).setPackage("app.avs")) } catch (e: ActivityNotFoundException) { startActivity(Intent.createChooser(send, "Send results")) }
    }

    private class Options(val useRegion: Boolean = true, val threads: Int = 3, val crop: Boolean = false, val maxDim: Int = 0) {
        fun toJson() = JSONObject().put("region", if (useRegion) "1" else "0").put("threads", threads.toString()).put("crop", if (crop) "1" else "0").put("maxdim", maxDim.toString())
        companion object {
            fun from(j: JSONObject) = Options(j.optString("region", "1") != "0", j.optString("threads", "3").toIntOrNull() ?: 3, j.optString("crop", "0") == "1", j.optString("maxdim", "0").toIntOrNull() ?: 0)
        }
    }

    /** Reads every picture in [dir] and writes out.jsonl and done.txt beside it (in the app's external files folder). */
    private fun run(dir: File, o: Options, progress: (String) -> Unit) {
        val base = getExternalFilesDir(null)!!
        val t0 = System.currentTimeMillis()
        val files = dir.listFiles { f -> f.extension.lowercase() in setOf("png", "jpg", "jpeg") }?.sortedBy { it.name }.orEmpty()
        val out = File(base, "out.jsonl"); out.delete()
        File(base, "done.txt").delete()
        val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val barcodes = BarcodeScanning.getClient()
        val pool = Executors.newFixedThreadPool(o.threads)
        val writer = out.bufferedWriter()
        val finished = AtomicInteger()
        for (f in files) pool.execute {
            val line = runCatching { one(f, text, barcodes, o.useRegion, o.crop, o.maxDim) }.getOrElse { JSONObject().put("file", f.name).put("error", it.toString()) }
            synchronized(writer) { writer.write(line.toString()); writer.newLine() }
            val n = finished.incrementAndGet()
            if (n % 10 == 0 || n == files.size) progress("$n / ${files.size} pictures")
        }
        pool.shutdown(); pool.awaitTermination(30, TimeUnit.MINUTES)
        writer.close()
        val summary = "${files.size} pictures in ${System.currentTimeMillis() - t0} ms"
        File(base, "done.txt").writeText("$summary\n")
        Log.i("LabelBench", "done: $summary")
        progress("Done: $summary")
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
