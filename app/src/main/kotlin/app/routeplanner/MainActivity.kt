package app.routeplanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.routeplanner.core.*
import app.routeplanner.ui.*
import com.google.android.material.R as M
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import java.io.File

/**
 * The route screen, laid out like Circuit: a full-screen map with numbered
 * stop pins, a floating "add stop" search bar (typed, voice, overflow menu),
 * and a draggable bottom sheet with the route summary, the next-stop card and
 * the stop list. Stop details open in their own sheet.
 */
class MainActivity : Activity(), RouteHost, RouteActions.Feedback, RouteMap.Listener {
    private lateinit var prefs: Prefs
    private lateinit var actions: RouteActions
    private lateinit var root: CoordinatorLayout
    private lateinit var topBar: MaterialCardView
    private lateinit var search: EditText
    private lateinit var pickBanner: MaterialCardView
    private lateinit var sheet: RouteSheet
    private lateinit var behavior: BottomSheetBehavior<LinearLayout>
    private var map: RouteMap? = null
    private var selectedId: String? = null
    private var lastCursorId: String? = null
    private var topInset = 0
    private val refresh = { render() }

    override val mapAvailable: Boolean get() = map != null

    companion object {
        const val ACTION_FAIL = "app.routeplanner.FAIL"
        private const val REQ_IMPORT = 1
        private const val REQ_TILES = 3
        private const val REQ_VOICE = 4
        private const val REQ_PHOTO = 5
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        SessionStore.load(this)
        prefs = Prefs(this)
        actions = RouteActions(this, prefs, this)

        // Draw behind the system bars on every API level (targetSdk 35 forces it on Android 15).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        root = CoordinatorLayout(this).apply { setBackgroundColor(color(M.attr.colorSurface)) }
        val mapBox = FrameLayout(this)
        root.addView(mapBox, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (prefs.showMap) map = RouteMap(this, mapBox, b, this)
        else mapBox.addView(mapOffNotice(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        topBar = buildTopBar()
        root.addView(topBar, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })
        pickBanner = buildPickBanner()
        root.addView(pickBanner, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })

        sheet = RouteSheet(this, this)
        val sheetParams = CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        sheetParams.behavior = BottomSheetBehavior<LinearLayout>()
        root.addView(sheet.view, sheetParams)
        behavior = BottomSheetBehavior.from(sheet.view).apply {
            isFitToContents = false
            halfExpandedRatio = 0.5f
            isHideable = false
            peekHeight = dp(160)
            state = if (map == null) BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_COLLAPSED
        }
        sheet.header.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) root.post { updateSheetGeometry() }
        }
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) root.post { updateSheetGeometry() }
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topInset = bars.top
            (topBar.layoutParams as ViewGroup.MarginLayoutParams).setMargins(bars.left + dp(12), bars.top + dp(8), bars.right + dp(12), 0)
            topBar.requestLayout()
            sheet.setBottomInset(bars.bottom)
            root.post { updateSheetGeometry() }
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        handleIntent(intent)
    }

    // ---- Layout pieces -------------------------------------------------------

    private fun buildTopBar(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dpf(28f)
            cardElevation = dpf(3f)
            setCardBackgroundColor(color(M.attr.colorSurfaceContainerHigh))
            strokeWidth = 0
        }
        val row = horizontal().apply { setPadding(dp(16), dp(2), dp(4), dp(2)) }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(color(M.attr.colorOnSurfaceVariant))
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        search = EditText(this).apply {
            hint = "Add a stop"
            background = null
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(dp(12), dp(14), dp(4), dp(14))
            setOnEditorActionListener { _, id, ev ->
                val enter = ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
                if (id == EditorInfo.IME_ACTION_SEARCH || enter) { submitSearch(); true } else false
            }
        }
        row.addWeighted(search)
        row.addView(iconButton(R.drawable.ic_mic, "Add stop by voice") { startVoice() })
        val more = iconButton(R.drawable.ic_more_vert, "More options") {}
        more.setOnClickListener { showMenu(more) }
        row.addView(more)
        card.addView(row)
        return card
    }

    private fun buildPickBanner(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dpf(20f)
            cardElevation = dpf(3f)
            setCardBackgroundColor(color(M.attr.colorTertiaryContainer))
            strokeWidth = 0
            visibility = android.view.View.GONE
        }
        val row = horizontal().apply { setPadding(dp(16), dp(4), dp(4), dp(4)) }
        row.addWeighted(label(14f, M.attr.colorOnTertiaryContainer).apply { text = "Tap the map where you park or drop off" })
        row.addView(button("Cancel", M.attr.borderlessButtonStyle) { endPick() })
        card.addView(row)
        return card
    }

    /** Peek = sheet header; expanded sheet stops below the search bar; map padding follows. */
    private fun updateSheetGeometry() {
        if (isDestroyed) return
        val barBottom = topInset + dp(8) + topBar.height
        val peek = (sheet.handle.height + sheet.header.height + dp(4))
            .coerceAtMost((root.height * 0.62f).toInt().coerceAtLeast(dp(120)))
            .coerceAtLeast(dp(96))
        behavior.peekHeight = peek
        behavior.expandedOffset = barBottom + dp(8)
        // Keep the half-expanded stop above the (possibly tall) collapsed one.
        if (root.height > 0) behavior.halfExpandedRatio = ((peek + dp(220)).toFloat() / root.height).coerceIn(0.5f, 0.9f)
        (pickBanner.layoutParams as ViewGroup.MarginLayoutParams).setMargins(dp(12), barBottom + dp(8), dp(12), 0)
        pickBanner.requestLayout()
        map?.updateInsets(barBottom, peek)
    }

    private fun showMenu(anchor: android.view.View) {
        val s = SessionStore.session
        val hasPending = s?.stops?.any { it.status == Status.PENDING } == true
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Import spreadsheet (CSV)")
            if (hasPending) menu.add(0, 2, 0, "Optimize route")
            if (hasPending) menu.add(0, 3, 0, "Update times (keep order)")
            if (hasPending) menu.add(0, 4, 0, "Reverse route")
            if (map != null && s != null) menu.add(0, 5, 0, "Show whole route")
            menu.add(0, 6, 0, "Floating action bar")
            menu.add(0, 7, 0, "Settings")
            if (s != null) menu.add(0, 8, 0, "Clear route")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> importCsv()
                    2 -> optimize()
                    3 -> actions.replan(optimize = false, showBusy = true)
                    4 -> SessionStore.session?.let { r -> r.reverse(); SessionStore.commit(this@MainActivity); update() }
                    5 -> map?.frameAll()
                    6 -> toggleOverlay()
                    7 -> showSettings()
                    8 -> clearRoute()
                }
                true
            }
        }.show()
    }

    // ---- Lifecycle -----------------------------------------------------------

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        handleIntent(i)
    }

    private fun handleIntent(i: Intent) {
        if (i.action == ACTION_FAIL) SessionStore.session?.current?.let { fail(it.id) }
    }

    override fun onStart() { super.onStart(); map?.onStart(); SessionStore.observe(refresh); render() }
    override fun onResume() { super.onResume(); map?.onResume() }
    override fun onPause() { map?.onPause(); super.onPause() }
    override fun onStop() { SessionStore.unobserve(refresh); map?.onStop(); super.onStop() }
    override fun onDestroy() { map?.onDestroy(); map = null; super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); map?.onLowMemory() }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); map?.onSaveInstanceState(out) }

    @Deprecated("Activity back handling; fine for a single-activity app")
    override fun onBackPressed() {
        when {
            map?.pickingFor != null -> endPick()
            behavior.state == BottomSheetBehavior.STATE_EXPANDED && map != null -> behavior.state = BottomSheetBehavior.STATE_COLLAPSED
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun render() {
        val s = SessionStore.session
        sheet.render(s)
        map?.render(s, selectedId)
        val curId = s?.current?.id?.takeIf { s.started }
        if (s != null && curId != null && curId != lastCursorId) sheet.list.post { sheet.scrollToCurrent(s) }
        lastCursorId = curId
    }

    // ---- Feedback ------------------------------------------------------------

    override fun snack(text: String, action: String?, onAction: (() -> Unit)?) {
        if (isFinishing || isDestroyed) return
        val bar = Snackbar.make(root, text, Snackbar.LENGTH_LONG)
        if (action != null && onAction != null) bar.setAction(action) { onAction() }
        bar.show()
    }

    // ---- RouteHost -----------------------------------------------------------

    override fun openStop(id: String) {
        val s = SessionStore.session ?: return
        val st = s.stop(id) ?: return
        selectedId = id
        map?.render(s, id)
        map?.focus(st.target)
        StopDetailSheet(this, this, id).apply {
            // Dismiss callbacks arrive asynchronously: don't clear a selection the pin picker now owns.
            onClosed = { if (selectedId == id && map?.pickingFor == null) { selectedId = null; render() } }
        }.show()
    }

    override fun navigate(stopId: String) {
        val s = SessionStore.session ?: return
        val st = s.stop(stopId) ?: return
        SessionStore.launchNav(this, navLink(s.navApp, st.access), s.navApp)
    }

    override fun deliver(stopId: String) {
        val n = SessionStore.session?.stop(stopId)?.optimizedIndex ?: return
        SessionStore.act(this) { mark(stopId, Status.COMPLETED) }
        snack("Stop $n delivered", "Undo") { undo(stopId) }
    }

    override fun deliverWithProof(stopId: String) {
        val n = SessionStore.session?.stop(stopId)?.optimizedIndex ?: return
        // A fresh file per attempt, so an undone delivery's photo is never re-attached.
        val photo = File(File(filesDir, "proof").apply { mkdirs() }, "$stopId-${System.currentTimeMillis()}.jpg")
        Dialogs.proof(this, photo, takePhoto = { takePhoto(it) }) { note, path ->
            SessionStore.act(this) { mark(stopId, Status.COMPLETED, note = note, photo = path) }
            snack("Stop $n delivered" + if (path != null) " with photo" else "", "Undo") { undo(stopId) }
        }
    }

    override fun fail(stopId: String) {
        val n = SessionStore.session?.stop(stopId)?.optimizedIndex ?: return
        Dialogs.failReason(this) { reason ->
            SessionStore.act(this) { mark(stopId, Status.FAILED, reason) }
            snack("Stop $n failed: ${FAIL_LABELS[reason]}", "Undo") { undo(stopId) }
        }
    }

    override fun undo(stopId: String) {
        val s = SessionStore.session ?: return
        s.undo(stopId)
        SessionStore.commit(this)
    }

    override fun moveStop(stopId: String, to: Int) {
        val s = SessionStore.session ?: return
        if (s.moveStop(stopId, to)) { SessionStore.commit(this); update() }
    }

    override fun optimize() = actions.replan(optimize = true, showBusy = true)
    override fun update() = actions.replan(optimize = false, showBusy = false)

    override fun startRoute() {
        val s = SessionStore.session ?: return
        s.start()
        SessionStore.commit(this)
        update() // ETAs from now and from where the driver is
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    override fun prev() = SessionStore.act(this) { back(); null }
    override fun next() = SessionStore.act(this) { next(); null }

    override fun importCsv() {
        val s = SessionStore.session
        val launch = {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel"))
            }, REQ_IMPORT)
        }
        if (s != null && s.stops.any { it.status == Status.PENDING }) Dialogs.confirm(this, "Replace current route?", "The imported spreadsheet becomes a new route.", "Import") { launch() }
        else launch()
    }

    override fun focusSearch() {
        search.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun clearRoute() {
        Dialogs.confirm(this, "Clear route?", "All stops and their status are removed from this phone. Proof photos stay in app storage.", "Clear") {
            SessionStore.set(this, null)
            OverlayService.stop(this)
        }
    }

    override fun pickDropOff(stopId: String) {
        val m = map ?: return
        val st = SessionStore.session?.stop(stopId) ?: return
        selectedId = stopId
        m.pickingFor = stopId
        pickBanner.visibility = android.view.View.VISIBLE
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        m.render(SessionStore.session, stopId)
        m.focus(st.access)
    }

    private fun endPick() {
        map?.pickingFor = null
        pickBanner.visibility = android.view.View.GONE
        selectedId = null
        render()
    }

    // ---- RouteMap.Listener ---------------------------------------------------

    override fun onStopTapped(id: String) = openStop(id)

    override fun onPointPicked(stopId: String, p: LatLng) {
        val s = SessionStore.session
        val st = s?.setAccess(stopId, p)
        endPick()
        if (s == null || st == null) return
        prefs.addressMemory.remember(st.address, p)
        SessionStore.commit(this)
        snack("Drop-off pin saved for this address")
        update()
    }

    override fun onLongPress(p: LatLng) {
        actions.background({ PlatformGeocoder(this).reverse(p) }) { r ->
            if (isFinishing || isDestroyed) return@background
            val address = r.getOrNull()
            val s = SessionStore.session
            val cur = s?.current?.takeIf { s.started && it.status == Status.PENDING }
            Dialogs.mapPoint(this, address, cur?.let { "stop ${it.optimizedIndex}" },
                onAdd = { name -> actions.addStopAt(name, p) },
                onDropOff = { cur?.let { onPointPicked(it.id, p) } })
        }
    }

    // ---- Inputs --------------------------------------------------------------

    private fun submitSearch() {
        val text = search.text.toString().trim()
        if (text.isEmpty()) return
        search.setText("")
        search.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        actions.addAddress(text)
    }

    private fun startVoice() {
        runCatching {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say an address")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true), REQ_VOICE)
        }.onFailure { Toast.makeText(this, "Voice input unavailable", Toast.LENGTH_SHORT).show() }
    }

    private fun takePhoto(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        runCatching {
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION), REQ_PHOTO)
        }.onFailure { Toast.makeText(this, "No camera app available", Toast.LENGTH_SHORT).show() }
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow “display over other apps”, then try again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        OverlayService.start(this)
        snack("Action bar on: it floats over your navigation app")
    }

    private fun showSettings() {
        val tiles = Routing.tilesFile(this)
        val model = Dialogs.SettingsModel(
            waze = prefs.navApp == NavApp.WAZE,
            autoNavigate = prefs.autoNavigate,
            showMap = prefs.showMap,
            serverUrl = prefs.valhallaUrl,
            roadsInfo = if (tiles.exists()) "Offline road data installed (${tiles.length() / 1_000_000} MB)."
            else "No offline road data: times come from the server below, or straight-line estimates.",
        )
        Dialogs.settings(this, model, importRoads = {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_TILES)
        }) { m ->
            prefs.navApp = if (m.waze) NavApp.WAZE else NavApp.GOOGLE
            prefs.autoNavigate = m.autoNavigate
            prefs.valhallaUrl = m.serverUrl
            SessionStore.session?.let { s -> s.navApp = prefs.navApp; s.autoNavigate = m.autoNavigate; SessionStore.commit(this) }
            if (m.showMap != prefs.showMap) { prefs.showMap = m.showMap; recreate() }
        }
    }

    @Deprecated("fine for a single-activity app")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        when (req) {
            REQ_IMPORT -> data?.data?.let { actions.importCsv(it) }
            REQ_TILES -> data?.data?.let { actions.importTiles(it) }
            REQ_VOICE -> data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                search.setText(it)
                submitSearch()
            }
            REQ_PHOTO -> Toast.makeText(this, "Photo attached", Toast.LENGTH_SHORT).show()
        }
    }
}
