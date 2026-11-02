package app.routeplanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import app.routeplanner.core.LabelParser
import app.routeplanner.core.RouteSession
import app.routeplanner.core.Stop
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Debug builds only. Runs a picture through the same text recognition, line ordering and label parser as the live
 * scanner and writes everything it saw, in full, to `<path>.json` (and to logcat under "RPDebug"). Lets a test harness
 * feed rendered labels in over adb instead of pointing a camera at a screen.
 */
class DebugOcr : BroadcastReceiver() {
    /** Route changes notify the screen, so they run on the main thread; this waits for them. */
    private fun onMain(block: () -> Unit) {
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post { try { block() } finally { latch.countDown() } }
        latch.await()
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        // The app creates its own external files dir (so it owns it); the harness pushes pictures there and reads results back.
        val dir = ctx.getExternalFilesDir(null) ?: return
        val name = intent.getStringExtra("name") ?: return        // "init" just makes the folder
        val path = File(dir, name).path
        val reset = intent.getBooleanExtra("reset", false)
        val pending = goAsync()
        Thread {
            try {
                if (reset) onMain { SessionStore.set(ctx, null) }   // new route
                val bmp = BitmapFactory.decodeFile(path) ?: error("can't decode $path")
                val image = InputImage.fromBitmap(bmp, 0)
                val text = Tasks.await(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image))
                val codes = Tasks.await(BarcodeScanning.getClient().process(image))
                val raw = text.textBlocks.flatMap { it.lines }
                val lines = LabelReading.order(raw).map { LabelParser.OcrLine(it.text, (it.boundingBox?.height() ?: 0).toFloat()) }
                val result = LabelParser.parse(lines)
                val out = JSONObject()
                    .put("lines", JSONArray(lines.map { JSONObject().put("text", it.text).put("h", it.height.toInt()) }))
                    .put("blockOrderLines", JSONArray(text.textBlocks.flatMap { it.lines }.map { it.text }))
                    .put("candidates", JSONArray(result.candidates.map {
                        JSONObject().put("address", it.address).put("role", it.role.name).put("h", it.height.toInt()).put("name", it.name ?: JSONObject.NULL)
                    }))
                    .put("barcodes", JSONArray(codes.map { JSONObject().put("format", it.format).put("value", it.rawValue ?: "") }))
                // The rest of the real flow: look the address up the way the app does and add it to the route.
                result.primary?.let { c ->
                    val prefs = Prefs(ctx)
                    val hit = runCatching { AddressSearch(ctx, prefs).geocoder().lookup(c.address) }.getOrNull()
                    val point = hit?.point
                    if (hit != null && point != null) onMain {
                        val session = SessionStore.session ?: RouteSession(emptyList(), emptyList(), prefs.navApp, prefs.autoNavigate, startMs = System.currentTimeMillis())
                            .also { SessionStore.set(ctx, it) }
                        session.addStop(Stop(UUID.randomUUID().toString(), c.address, point, point, approximate = hit.approximate), emptyList(), emptyList())
                        SessionStore.commit(ctx)
                    }
                    out.put("geocoded", JSONObject().put("title", hit?.title ?: JSONObject.NULL).put("lat", point?.lat ?: JSONObject.NULL)
                        .put("lon", point?.lng ?: JSONObject.NULL).put("approximate", hit?.approximate ?: JSONObject.NULL).put("source", hit?.source?.name ?: JSONObject.NULL))
                }
                var n = 0; onMain { n = SessionStore.session?.stops?.size ?: 0 }; out.put("routeStops", n)
                File("$path.json").writeText(out.toString(2))
                Log.i("RPDebug", "ocr $path: ${lines.size} lines, ${result.candidates.size} addresses, ${codes.size} barcodes")
            } catch (e: Exception) {
                File("$path.json").writeText(JSONObject().put("error", e.toString()).toString())
                Log.w("RPDebug", "ocr failed", e)
            } finally { pending.finish() }
        }.start()
    }
}
