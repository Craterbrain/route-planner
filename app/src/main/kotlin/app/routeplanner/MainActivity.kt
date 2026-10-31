package app.routeplanner

import android.Manifest
import android.annotation.SuppressLint
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

    companion object {
        const val ACTION_FAIL = "app.routeplanner.FAIL"
        private const val REQ_IMPORT = 1
        private const val REQ_LOCATION = 2
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
        weighted(btn("✓ Delivered") { SessionStore.act(this) { deliver() } })
        weighted(btn("✗", M.attr.materialButtonOutlinedStyle) { showFailDialog() })
        weighted(btn("Next ►", M.attr.materialButtonOutlinedStyle) { SessionStore.act(this) { next(); null } })

        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun tool(v: View) = tools.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        tool(btn("Import CSV", M.attr.borderlessButtonStyle) { pickManifest() })
        tool(btn("Navigate", M.attr.borderlessButtonStyle) { navigate() })
        tool(btn("Bar overlay", M.attr.borderlessButtonStyle) { toggleOverlay() })

        val prefs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val navToggle = btn("", M.attr.borderlessButtonStyle) { }
        navToggle.setOnClickListener {
            val s = SessionStore.session ?: return@setOnClickListener
            s.navApp = if (s.navApp == NavApp.GOOGLE) NavApp.WAZE else NavApp.GOOGLE
            SessionStore.commit(this)
        }
        navToggle.tag = "nav"
        val auto = MaterialSwitch(this).apply {
            text = "Auto-navigate"
            setOnCheckedChangeListener { v, on ->
                if (v.isPressed) { SessionStore.session?.autoNavigate = on; SessionStore.commit(this@MainActivity) }
            }
            tag = "auto"
        }
        prefs.addView(navToggle, LinearLayout.LayoutParams(0, -2, 1f))
        prefs.addView(auto, LinearLayout.LayoutParams(0, -2, 1f))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(header); root.addView(cardView, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) }); root.addView(bar); root.addView(tools); root.addView(prefs)
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
        val uri = data?.data ?: return
        if (req != REQ_IMPORT || res != RESULT_OK) return
        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
        val m = importManifest(text, dayStartSec = 8 * 3600)
        if (m.stops.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle("Nothing imported").setMessage(m.errors.joinToString("\n").ifEmpty { "No rows" }).show()
            return
        }
        val route = Solver.optimize(startPoint(m.stops), m.stops, HaversineMatrix())
        SessionStore.set(this, RouteSession(route.stops, m.packages))
        val msg = "${route.stops.size} stops, ~${route.totalSec / 60} min" +
            (if (route.lateStops > 0) ", ${route.lateStops} late" else "") +
            (if (m.errors.isNotEmpty()) "\n\nSkipped:\n" + m.errors.joinToString("\n") else "")
        MaterialAlertDialogBuilder(this).setTitle("Route planned").setMessage(msg).setPositiveButton("OK", null).show()
    }

    /** Where the route starts: last known fix if permitted, else the stops' centroid. */
    @SuppressLint("MissingPermission")
    private fun startPoint(stops: List<Stop>): LatLng {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
        } else {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                ?.let { return LatLng(it.latitude, it.longitude) }
        }
        return LatLng(stops.map { it.target.lat }.average(), stops.map { it.target.lng }.average())
    }
}
