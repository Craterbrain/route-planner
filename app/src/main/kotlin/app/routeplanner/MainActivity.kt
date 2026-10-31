package app.routeplanner

import android.Manifest
import android.annotation.SuppressLint
import android.speech.RecognizerIntent
import android.text.InputType
import android.widget.EditText
import androidx.core.content.FileProvider
import java.io.File
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.R as M
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.routeplanner.core.*

class MainActivity : Activity() {
    private lateinit var header: TextView
    private lateinit var card: TextView
    private lateinit var list: LinearLayout
    private val refresh = { render() }
    private lateinit var prefs: Prefs
    private var proofNoteField: EditText? = null
    private var addStopField: EditText? = null
    private var pendingPhoto: File? = null

    companion object {
        const val ACTION_FAIL = "app.routeplanner.FAIL"
        private const val REQ_IMPORT = 1
        private const val REQ_LOCATION = 2
        private const val REQ_TILES = 3
        private const val REQ_VOICE = 4
        private const val REQ_PHOTO = 5
        private val FAIL_LABELS = mapOf(
            FailReason.RECIPIENT_MISSING to "Recipient missing",
            FailReason.ACCESS_CODE_NEEDED to "Access code needed",
            FailReason.WRONG_ADDRESS to "Wrong address",
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, style: Int = M.attr.materialButtonStyle, onClick: () -> Unit) =
        MaterialButton(this, null, style).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun text(size: Float, colorAttr: Int = M.attr.colorOnSurface) = TextView(this).apply {
        textSize = size
        setTextColor(color(colorAttr))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        SessionStore.load(this)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        // Edge-to-edge is enforced at targetSdk 35: keep content clear of system bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left + dp(16), b.top + dp(16), b.right + dp(16), b.bottom + dp(16))
            WindowInsetsCompat.CONSUMED
        }
        header = text(22f)
        card = text(16f, M.attr.colorOnPrimaryContainer)
        val cardView = MaterialCardView(this).apply {
            setCardBackgroundColor(color(M.attr.colorPrimaryContainer))
            radius = dp(20).toFloat()
            cardElevation = 0f
            addView(card, ViewGroup.LayoutParams(-1, -2))
            card.setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun weighted(v: View) = bar.addView(v, LinearLayout.LayoutParams(0, dp(64), 1f))
        weighted(btn("◄ Prev", M.attr.materialButtonOutlinedStyle) { SessionStore.act(this) { prev(); null } })
        weighted(btn("✓ Delivered") { SessionStore.act(this) { deliver() } }.also {
            it.setOnLongClickListener { showProofDialog(); true } // long-press: proof of delivery
        })
        weighted(btn("✗", M.attr.materialButtonOutlinedStyle) { showFailDialog() })
        weighted(btn("Next ►", M.attr.materialButtonOutlinedStyle) { SessionStore.act(this) { next(); null } })

        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun tool(v: View) = tools.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        tool(btn("Import CSV", M.attr.borderlessButtonStyle) { pickManifest() })
        tool(btn("Add stop", M.attr.borderlessButtonStyle) { showAddStopDialog() })
        tool(btn("Navigate", M.attr.borderlessButtonStyle) { navigate() })
        tool(btn("Bar", M.attr.borderlessButtonStyle) { toggleOverlay() })
        tool(btn("⚙", M.attr.borderlessButtonStyle) { showSettings() })

        val prefsRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val navToggle = btn("", M.attr.borderlessButtonStyle) { }
        navToggle.setOnClickListener {
            val s = SessionStore.session ?: return@setOnClickListener
            s.navApp = if (s.navApp == NavApp.GOOGLE) NavApp.WAZE else NavApp.GOOGLE
            prefs.navApp = s.navApp
            SessionStore.commit(this)
        }
        navToggle.tag = "nav"
        val auto = MaterialSwitch(this).apply {
            text = "Auto-navigate"
            setOnCheckedChangeListener { v, on ->
                if (v.isPressed) { prefs.autoNavigate = on; SessionStore.session?.autoNavigate = on; SessionStore.commit(this@MainActivity) }
            }
            tag = "auto"
        }
        prefsRow.addView(navToggle, LinearLayout.LayoutParams(0, -2, 1f))
        prefsRow.addView(auto, LinearLayout.LayoutParams(0, -2, 1f))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(header); root.addView(cardView, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) }); root.addView(bar); root.addView(tools); root.addView(prefsRow)
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        handleIntent(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        handleIntent(i)
    }

    private fun handleIntent(i: Intent) {
        if (i.action == ACTION_FAIL) showFailDialog()
    }

    override fun onStart() { super.onStart(); SessionStore.observe(refresh); render() }
    override fun onStop() { SessionStore.unobserve(refresh); super.onStop() }

    private fun render() {
        val s = SessionStore.session
        val root = header.parent as ViewGroup
        val navBtn = root.findViewWithTag<MaterialButton>("nav")
        val auto = root.findViewWithTag<MaterialSwitch>("auto")
        list.removeAllViews()
        if (s == null) {
            header.text = "No route"
            card.text = "Import a manifest CSV to plan a route.\n\nColumns: address, lat, lng, package, zone, priority, window_start, window_end, service_min, notes"
            navBtn.text = "Nav: —"
            return
        }
        val done = s.stops.count { it.status != Status.PENDING }
        header.text = "Route · $done / ${s.stops.size} done"
        navBtn.text = "Nav: ${s.navApp.name.lowercase().replaceFirstChar { it.uppercase() }}"
        auto.isChecked = s.autoNavigate
        val c = s.current
        card.text = if (c == null || s.done) "All stops handled." else buildString {
            append("#${c.optimizedIndex}  ${c.address}\n")
            if (c.priority) append("★ Priority\n")
            c.window?.let { append("Window ${clock(it.first)}–${clock(it.last)}\n") }
            if (c.notes.isNotEmpty()) append("Note: ${c.notes}\n")
            append("Find: " + s.finder().joinToString("  ") { "${it.id} @ ${it.truckZone ?: "?"}" })
        }
        for (st in s.stops) list.addView(text(14f).apply {
            val mark = when (st.status) {
                Status.COMPLETED -> "✓"; Status.FAILED -> "✗"; Status.SKIPPED -> "–"; Status.PENDING -> "•"
            }
            text = "$mark ${st.optimizedIndex}. ${st.address}  ${clock(st.etaSec.toLong())}" +
                (if (st.lateSec > 0) "  LATE" else "") + (if (st.priority) " ★" else "")
            textSize = if (st.id == c?.id) 17f else 14f
            setTextColor(color(when {
                st.id == c?.id -> M.attr.colorPrimary
                st.status == Status.FAILED -> M.attr.colorError
                st.status == Status.PENDING -> M.attr.colorOnSurface
                else -> M.attr.colorOnSurfaceVariant
            }))
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { if (!st.isCurrent(s)) { s.jumpTo(st.id); SessionStore.commit(this@MainActivity) } }
        })
    }

    private fun clock(sec: Long): String = "%02d:%02d".format((sec / 3600 + 8) % 24, sec % 3600 / 60)
    private fun Stop.isCurrent(s: RouteSession) = s.current?.id == id

    private fun showFailDialog() {
        val s = SessionStore.session ?: return
        if (s.current == null) return
        val reasons = FailReason.values()
        MaterialAlertDialogBuilder(this)
            .setTitle("Why wasn't it delivered?")
            .setItems(reasons.map { FAIL_LABELS.getValue(it) }.toTypedArray()) { _, i ->
                SessionStore.act(this) { fail(reasons[i]) }
            }.show()
    }

    private fun navigate() {
        val s = SessionStore.session ?: return
        s.link()?.let { SessionStore.launchNav(this, it, s.navApp) }
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow “display over other apps”, then tap again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        OverlayService.start(this)
        Toast.makeText(this, "Action bar on — long-press Back/Home to switch apps", Toast.LENGTH_SHORT).show()
    }

    private fun pickManifest() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel"))
        }, REQ_IMPORT)
    }

    @Deprecated("fine for a single-activity app")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        when (req) {
            REQ_IMPORT -> data?.data?.let { importManifestFrom(it) }
            REQ_TILES -> data?.data?.let { importTiles(it) }
            REQ_VOICE -> data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.let { addStopField?.setText(it) }
            REQ_PHOTO -> Toast.makeText(this, "Photo attached", Toast.LENGTH_SHORT).show()
        }
    }

    /** Runs [work] off the main thread, then [done] on it. */
    private fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        Thread { val r = runCatching(work); runOnUiThread { done(r) } }.start()
    }

    private fun importManifestFrom(uri: Uri) {
        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
        val busy = MaterialAlertDialogBuilder(this).setTitle("Planning route…").setMessage("Geocoding and routing").setCancelable(false).show()
        val start = startPoint(null)
        background({
            val m = importManifest(text, dayStartSec = 8 * 3600, geocoder = PlatformGeocoder(this), memory = prefs.addressMemory)
            val src = Routing.source(this, prefs)
            val origin = start ?: LatLng(m.stops.map { it.target.lat }.average(), m.stops.map { it.target.lng }.average())
            Triple(m, if (m.stops.isEmpty()) null else Solver.optimize(origin, m.stops, src.provider), src)
        }) { r ->
            busy.dismiss()
            val (m, route, src) = r.getOrElse {
                MaterialAlertDialogBuilder(this).setTitle("Import failed").setMessage(it.message).setPositiveButton("OK", null).show(); return@background
            }
            if (route == null) {
                MaterialAlertDialogBuilder(this).setTitle("Nothing imported").setMessage(m.errors.joinToString("\n").ifEmpty { "No rows" }).show()
                return@background
            }
            SessionStore.set(this, RouteSession(route.stops, m.packages, prefs.navApp, prefs.autoNavigate))
            val matrix = src.provider as? ValhallaMatrix
            val msg = "${route.stops.size} stops, ~${route.totalSec / 60} min\nTimes: ${src.label}" +
                (if (matrix?.degraded == true) " (some legs estimated)" else "") +
                (if (route.lateStops > 0) "\n${route.lateStops} late" else "") +
                (if (m.errors.isNotEmpty()) "\n\nSkipped:\n" + m.errors.joinToString("\n") else "")
            MaterialAlertDialogBuilder(this).setTitle("Route planned").setMessage(msg).setPositiveButton("OK", null).show()
        }
    }

    private fun importTiles(uri: Uri) {
        val busy = MaterialAlertDialogBuilder(this).setTitle("Importing map data…").setCancelable(false).show()
        background({
            val tmp = File(filesDir, "valhalla_tiles.tar.part")
            contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            check(tmp.renameTo(Routing.tilesFile(this)) || (Routing.tilesFile(this).delete() && tmp.renameTo(Routing.tilesFile(this))))
            Routing.source(this, prefs).label
        }) { r ->
            busy.dismiss()
            Toast.makeText(this, r.fold({ "Routing with $it" }, { "Import failed: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val tiles = Routing.tilesFile(this)
        box.addView(text(14f).apply {
            text = if (tiles.exists()) "Offline roads: ${tiles.length() / 1_000_000} MB map data installed"
            else "Offline roads: no map data. Import a valhalla_tiles.tar built for your region."
        })
        box.addView(btn("Import map data (.tar)", M.attr.materialButtonOutlinedStyle) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_TILES)
        })
        box.addView(text(14f).apply { text = "\nOptional Valhalla server (used only without map data):" })
        val url = EditText(this).apply {
            hint = "http://192.168.1.10:8002"; setText(prefs.valhallaUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(url)
        MaterialAlertDialogBuilder(this).setTitle("Settings").setView(box)
            .setPositiveButton("Save") { _, _ -> prefs.valhallaUrl = url.text.toString() }
            .setNegativeButton("Close", null).show()
    }

    private fun showAddStopDialog() {
        val s = SessionStore.session ?: return
        val box = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); gravity = Gravity.CENTER_VERTICAL }
        val field = EditText(this).apply { hint = "Address"; inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        addStopField = field
        box.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(btn("🎤", M.attr.borderlessButtonStyle) {
            runCatching {
                startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true), REQ_VOICE)
            }.onFailure { Toast.makeText(this, "Voice input unavailable", Toast.LENGTH_SHORT).show() }
        })
        MaterialAlertDialogBuilder(this).setTitle("Add stop").setView(box)
            .setPositiveButton("Add") { _, _ -> addStop(s, field.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addStop(s: RouteSession, address: String) {
        if (address.isEmpty()) return
        val here = startPoint(null) ?: s.current?.access ?: return
        background({
            val pin = prefs.addressMemory.access(address)
            val door = PlatformGeocoder(this).geocode(address) ?: error("Couldn't find “$address”")
            val stop = Stop(java.util.UUID.randomUUID().toString(), address, door, pin ?: door)
            s.inject(stop, Routing.source(this, prefs).provider, here)
        }) { r ->
            SessionStore.commit(this)
            r.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun showProofDialog() {
        val c = SessionStore.session?.current ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val note = EditText(this).apply { hint = "Note (e.g. left behind back porch pot)" }
        proofNoteField = note
        val photo = File(File(filesDir, "proof").apply { mkdirs() }, "${c.id}.jpg").also { pendingPhoto = it }
        box.addView(note)
        box.addView(btn("📷 Take photo", M.attr.materialButtonOutlinedStyle) {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", photo)
            runCatching {
                startActivityForResult(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri), REQ_PHOTO)
            }.onFailure { Toast.makeText(this, "No camera app available", Toast.LENGTH_SHORT).show() }
        })
        MaterialAlertDialogBuilder(this).setTitle("Proof of delivery").setView(box)
            .setPositiveButton("Delivered") { _, _ ->
                val p = photo.takeIf { it.exists() && it.length() > 0 }?.absolutePath
                SessionStore.act(this) { deliver(note.text.toString(), p) }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Last known fix if permitted (asks once); null when unknown. */
    @SuppressLint("MissingPermission")
    private fun startPoint(@Suppress("UNUSED_PARAMETER") unused: Nothing?): LatLng? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            return null
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            ?.let { LatLng(it.latitude, it.longitude) }
    }
}
