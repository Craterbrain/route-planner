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
import app.routeplanner.core.LabelParser
import app.routeplanner.ui.addFull
import app.routeplanner.ui.button
import app.routeplanner.ui.compact
import app.routeplanner.ui.dp
import app.routeplanner.ui.label
import app.routeplanner.ui.roundRect
import app.routeplanner.ui.tonal
import app.routeplanner.ui.vertical
import com.google.android.material.R as M
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

/**
 * Camera screen for two jobs, chosen by [EXTRA_MODE]:
 *  - [MODE_LABEL]: read a shipping label. Address(es) and tracking numbers found in the live view are
 *    listed; tap one to return it (an address to add as a stop, with any tracking number as its package).
 *  - [MODE_FIND]: scan a package barcode and return it at once (to look up its stop and truck location).
 * Both ML Kit recognizers are the bundled models, so scanning works offline.
 */
class ScanActivity : AppCompatActivity() {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val barcodes = BarcodeScanning.getClient()
    private val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var results: LinearLayout
    private lateinit var hint: android.widget.TextView
    private lateinit var mode: String

    // What the last few seconds of frames contained (key -> last seen ms), so the list doesn't flicker.
    private val addresses = LinkedHashMap<String, Long>()
    private val codes = LinkedHashMap<String, Long>()
    private var lastRun = 0L
    private var shownKey = ""
    private var finished = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LABEL
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = FrameLayout(this)
        val preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        val top = button("Close", M.attr.materialButtonOutlinedStyle) { setResult(RESULT_CANCELED); finish() }
        hint = label(14f, M.attr.colorOnPrimaryContainer).apply {
            text = if (mode == MODE_FIND) "Point at a package barcode" else "Point at the shipping label"
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
        val pending = java.util.concurrent.atomic.AtomicInteger(if (mode == MODE_FIND) 1 else 2)
        fun done() { if (pending.decrementAndGet() == 0) proxy.close() }
        barcodes.process(image)
            .addOnSuccessListener { list ->
                val found = list.mapNotNull { it.rawValue?.trim()?.takeIf { v -> v.isNotEmpty() } }
                if (found.isNotEmpty()) runOnUiThread { onCodes(found) }
            }
            .addOnCompleteListener { done() }
        if (mode == MODE_LABEL) {
            text.process(image)
                .addOnSuccessListener { t -> if (t.text.isNotBlank()) runOnUiThread { onText(t.text) } }
                .addOnCompleteListener { done() }
        }
    }

    private fun onCodes(found: List<String>) {
        if (finished) return
        if (mode == MODE_FIND) { finishWith(code = found.first()); return }
        val now = System.currentTimeMillis()
        found.forEach { codes[it] = now }
        renderResults()
    }

    private fun onText(raw: String) {
        if (finished) return
        val now = System.currentTimeMillis()
        val parsed = LabelParser.parse(raw)
        parsed.addresses.forEach { addresses[it] = now }
        // A tracking number in the text counts like a scanned one.
        parsed.tracking.forEach { codes[it] = now }
        renderResults()
    }

    private fun renderResults() {
        val now = System.currentTimeMillis()
        addresses.entries.removeAll { now - it.value > 6000 }
        codes.entries.removeAll { now - it.value > 6000 }
        val key = addresses.keys.joinToString("|") + "#" + codes.keys.joinToString("|")
        if (key == shownKey && results.childCount > 0) return
        shownKey = key
        results.removeAllViews()
        if (addresses.isEmpty() && codes.isEmpty()) {
            results.addFull(label(14f, M.attr.colorOnSurfaceVariant).apply {
                text = if (mode == MODE_FIND) "Waiting for a barcode…" else "Hold the label steady in view. Addresses and tracking numbers appear here."
            })
            return
        }
        if (codes.isNotEmpty()) results.addFull(label(12f, M.attr.colorPrimary, bold = true).apply {
            text = "TRACKING / BARCODE: " + codes.keys.first() + if (codes.size > 1) " (+${codes.size - 1})" else ""
        })
        for (a in addresses.keys.take(3)) {
            results.addFull(compact(tonal(button("Use: $a") { finishWith(address = a, code = codes.keys.firstOrNull()) })), topDp = 6)
        }
        if (addresses.isEmpty() && codes.isNotEmpty()) results.addFull(compact(tonal(button("Use code only") {
            finishWith(code = codes.keys.first())
        })), topDp = 6)
    }

    private fun finishWith(address: String? = null, code: String? = null) {
        if (finished) return
        finished = true
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ADDRESS, address).putExtra(EXTRA_CODE, code))
        finish()
    }

    override fun onDestroy() {
        runCatching { barcodes.close(); text.close() }
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_LABEL = "label"
        const val MODE_FIND = "find"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_CODE = "code"
        private const val REQ_CAMERA = 31
    }
}
