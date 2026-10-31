package app.routeplanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.routeplanner.core.Barcodes
import app.routeplanner.core.LabelParser
import app.routeplanner.core.Status
import app.routeplanner.ui.addFull
import app.routeplanner.ui.button
import app.routeplanner.ui.compact
import app.routeplanner.ui.dp
import app.routeplanner.ui.label
import app.routeplanner.ui.roundRect
import app.routeplanner.ui.vertical
import com.google.android.material.R as M
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

/**
 * Camera screen for two jobs, chosen by [EXTRA_MODE]:
 *  - [MODE_LABEL]: read a shipping label. The text gives the delivery address (the bigger print, or the one after
 *    "SHIP TO") first and a return address second; barcodes play no part in the address. Whatever barcodes the
 *    camera sees meanwhile are collected and returned with the address, to be saved on that package.
 *  - [MODE_FIND]: check a parcel in your hand. Keeps scanning; every barcode on the parcel is compared with the
 *    codes saved on the route's packages and the screen says whether it is on this route, and which stop/ID it
 *    belongs to, or that it isn't. Tap the stop to open it, or just scan the next parcel.
 * The ML Kit models are the bundled ones, so scanning works offline.
 */
class ScanActivity : AppCompatActivity() {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    // Each recognizer is created only when a mode needs it.
    private val barcodes by lazy { BarcodeScanning.getClient() }
    private val text by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private lateinit var results: LinearLayout
    private lateinit var hint: android.widget.TextView
    private lateinit var mode: String

    // The ranked addresses from the latest useful frame, and when they were seen (so the list doesn't flicker).
    private var ranked: List<LabelParser.Candidate> = emptyList()
    private var rankedAt = 0L
    private var lastRun = 0L
    private var shownKey = ""
    private var finished = false

    // Barcode values seen recently (value -> last seen ms). A held parcel gives a stable set; old ones age out.
    private val codeSeen = LinkedHashMap<String, Long>()
    private var check: Check? = null

    /** Outcome of the latest parcel check (MODE_FIND). */
    private sealed class Check {
        class Hit(val match: Barcodes.Match) : Check()
        class Miss(val codes: Int) : Check()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LABEL
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = FrameLayout(this)
        val preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        val top = button("Close", M.attr.materialButtonOutlinedStyle) { setResult(RESULT_CANCELED); finish() }
        hint = label(14f, M.attr.colorOnPrimaryContainer).apply {
            text = if (mode == MODE_FIND) "Check a parcel: scan a barcode on it" else "Point at the shipping label"
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundRect(M.attr.colorPrimaryContainer, 16)
        }
        val topRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(12), 0) }
        topRow.addView(top)
        topRow.addView(hint, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        root.addView(topRow, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        results = vertical(padH = 12, padV = 12).apply { background = roundRect(M.attr.colorSurface, 24) }
        root.addView(results, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(8)) })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topRow.setPadding(dp(12), bars.top + dp(12), dp(12), 0)
            (results.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + dp(8)
            results.requestLayout()
            WindowInsetsCompat.CONSUMED
        }
        renderResults()

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera(preview)
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, perms, grants)
        if (code == REQ_CAMERA && grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera(findViewById<ViewGroup>(android.R.id.content).getChildAt(0).let { (it as FrameLayout).getChildAt(0) as PreviewView })
        } else if (code == REQ_CAMERA) {
            Toast.makeText(this, "Camera permission is needed to scan", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED); finish()
        }
    }

    private fun startCamera(preview: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrElse {
                Toast.makeText(this, "Camera unavailable", Toast.LENGTH_LONG).show(); finish(); return@addListener
            }
            val use = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                // Labels are small text: ask for a higher resolution than CameraX's 640x480 default.
                .setResolutionSelector(androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(
                        android.util.Size(1920, 1080), androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(analysisExecutor, ::analyze) }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, use, analysis)
            }.onFailure { Log.w(NetStatus.TAG, "scan: camera bind failed: $it"); Toast.makeText(this, "Camera unavailable", Toast.LENGTH_LONG).show(); finish() }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyze(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val media = proxy.image
        // ~3 frames a second is plenty for a held label and keeps the phone cool.
        if (media == null || finished || now - lastRun < 350) { proxy.close(); return }
        lastRun = now
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        val wantText = mode == MODE_LABEL
        val pending = java.util.concurrent.atomic.AtomicInteger(if (wantText) 2 else 1)
        fun done() { if (pending.decrementAndGet() == 0) proxy.close() }
        barcodes.process(image)
            .addOnSuccessListener { list ->
                val values = list.mapNotNull { it.rawValue?.trim()?.takeIf { v -> v.isNotEmpty() && v.length <= 300 } }
                if (values.isNotEmpty()) runOnUiThread { onCodes(values) }
            }
            .addOnCompleteListener { done() }
        if (wantText) {
            text.process(image)
                .addOnSuccessListener { t ->
                    // Lines in reading order, each with its printed height (the bigger address is the recipient's).
                    val lines = t.textBlocks.sortedBy { it.boundingBox?.top ?: 0 }.flatMap { it.lines }
                        .map { LabelParser.OcrLine(it.text, (it.boundingBox?.height() ?: 0).toFloat()) }
                    if (lines.isNotEmpty()) runOnUiThread { onLines(lines) }
                }
                .addOnCompleteListener { done() }
        }
    }

    /** Barcode values from the last [windowMs]. */
    private fun recentCodes(windowMs: Long): List<String> {
        val now = System.currentTimeMillis()
        codeSeen.entries.removeAll { now - it.value > 8000 }
        return codeSeen.filterValues { now - it <= windowMs }.keys.toList()
    }

    private fun onCodes(values: List<String>) {
        if (finished) return
        val now = System.currentTimeMillis()
        values.forEach { codeSeen[it] = now }
        if (mode == MODE_FIND) {
            // Compare everything on the parcel together: a long code in common identifies it.
            val recent = recentCodes(2500)
            val session = SessionStore.session
            val match = session?.scanMatch(recent)
            check = if (match != null) Check.Hit(match) else Check.Miss(recent.size)
        }
        renderResults()
    }

    private fun onLines(lines: List<LabelParser.OcrLine>) {
        if (finished) return
        val found = LabelParser.parse(lines).candidates
        val now = System.currentTimeMillis()
        // A frame that sees fewer addresses than we already hold (say, only the return address) doesn't
        // replace the better-informed list until that list has gone stale.
        if (found.isNotEmpty() && (found.size >= ranked.size || now - rankedAt > 2000)) { ranked = found; rankedAt = now }
        renderResults()
    }

    private fun renderResults() = if (mode == MODE_FIND) renderCheck() else renderLabel()

    private fun renderLabel() {
        val now = System.currentTimeMillis()
        if (now - rankedAt > 6000) ranked = emptyList()
        val codes = recentCodes(6000)
        val key = ranked.joinToString("|") { it.address + it.role } + "#" + codes.size
        if (key == shownKey && results.childCount > 0) return
        shownKey = key
        results.removeAllViews()
        if (ranked.isEmpty()) {
            results.addFull(label(14f, M.attr.colorOnSurfaceVariant).apply {
                text = "Hold the label steady in view. The delivery address appears here." +
                    if (codes.isNotEmpty()) "\nBarcodes read: ${codes.size}" else ""
            })
            return
        }
        val primary = ranked.first()
        results.addFull(label(12f, M.attr.colorPrimary, bold = true).apply {
            text = (if (primary.isReturn) "ONLY A RETURN ADDRESS SEEN" else "DELIVERY ADDRESS") +
                if (codes.isNotEmpty()) " · ${codes.size} barcode" + (if (codes.size > 1) "s" else "") + " read" else ""
        })
        results.addFull(compact(button("Add: ${primary.address}") { finishWith(address = primary.address, name = primary.name, codes = recentCodes(6000)) }), topDp = 6)
        for (c in ranked.drop(1).take(2)) {
            val tag = if (c.isReturn) "Return address" else "Other address"
            results.addFull(compact(button("$tag: ${c.address}", M.attr.materialButtonOutlinedStyle) { finishWith(address = c.address, name = c.name, codes = recentCodes(6000)) }), topDp = 6)
        }
    }

    /** The parcel check card: on the route (which stop), maybe (shared code), or not on the route. */
    private fun renderCheck() {
        val c = check
        val key = when (c) {
            null -> "wait"
            is Check.Miss -> "miss${c.codes}"
            is Check.Hit -> "hit" + c.match.packages.joinToString { it.id } + c.match.certain
        }
        if (key == shownKey && results.childCount > 0) return
        shownKey = key
        results.removeAllViews()
        val session = SessionStore.session
        when (c) {
            null -> results.addFull(label(14f, M.attr.colorOnSurfaceVariant).apply { text = "Point at a barcode on the parcel in your hand…" })
            is Check.Miss -> {
                results.addFull(label(18f, M.attr.colorError, bold = true).apply { text = "✗ Not on this route" })
                results.addFull(label(13f, M.attr.colorOnSurfaceVariant).apply {
                    text = "${c.codes} barcode" + (if (c.codes > 1) "s" else "") + " read and none matches a package you added. Try another barcode on the label."
                }, topDp = 4)
            }
            is Check.Hit -> {
                val m = c.match
                results.addFull(label(18f, M.attr.colorPrimary, bold = true).apply {
                    text = if (m.certain) "✓ On this route" else "Could be one of ${m.packages.size} packages (shared code)"
                })
                for (p in m.packages.take(4)) {
                    val st = session?.stopOf(p) ?: continue
                    val zone = p.truckZone ?: "location not set"
                    val state = when (st.status) { Status.COMPLETED -> " · already delivered"; Status.FAILED -> " · failed earlier"; else -> "" }
                    results.addFull(compact(button("Stop ${st.optimizedIndex} · ID ${st.entryNo} · package ${p.id}\n${st.address.substringBefore(',')} · $zone$state") {
                        finishWith(stopId = st.id)
                    }), topDp = 6)
                }
            }
        }
        results.addFull(compact(button("Scan next parcel", M.attr.materialButtonOutlinedStyle) {
            codeSeen.clear(); check = null; shownKey = ""; renderCheck()
        }), topDp = 8)
    }

    private fun finishWith(address: String? = null, name: String? = null, codes: List<String> = emptyList(), stopId: String? = null) {
        if (finished) return
        finished = true
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ADDRESS, address).putExtra(EXTRA_NAME, name)
            .putStringArrayListExtra(EXTRA_CODES, ArrayList(codes.take(8))).putExtra(EXTRA_STOP_ID, stopId))
        finish()
    }

    override fun onDestroy() {
        runCatching { barcodes.close() }
        runCatching { if (mode == MODE_LABEL) text.close() }
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_LABEL = "label"
        const val MODE_FIND = "find"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_CODES = "codes"
        const val EXTRA_STOP_ID = "stopId"
        const val EXTRA_NAME = "name"
        private const val REQ_CAMERA = 31
    }
}
