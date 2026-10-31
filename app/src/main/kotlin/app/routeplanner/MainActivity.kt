package app.routeplanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private var bottomInset = 0
    private lateinit var searchPanel: SearchPanel
    /** Zero-height view at the bottom (above nav bar / keyboard) that snackbars sit on. */
    private lateinit var snackAnchor: android.view.View
    private var permissions: PermissionsSheet? = null
    /** User turned the floating button on but must grant "display over other apps" first. */
    private var overlayWanted = false
    /** The search bar is choosing the route start rather than adding a stop. */
    private var searchingStart = false
    private var searchingEnd = false
    private lateinit var pickLabel: android.widget.TextView
    private var locating = false
    private val refresh = { render() }
    private val netChanged = { map?.onNetworkChanged(); Unit }

    override val mapAvailable: Boolean get() = map != null

    companion object {
        const val ACTION_FAIL = "app.routeplanner.FAIL"
        private const val REQ_IMPORT = 1
        private const val REQ_TILES = 3
        private const val REQ_VOICE = 4
        private const val REQ_PHOTO = 5
        private const val REQ_START_LOCATION = 8
        private const val REQ_ADDRESSES = 9
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        SessionStore.load(this)
        prefs = Prefs(this)
        // Routes saved before starts existed (or a cleared Prefs): reconcile the two copies.
        SessionStore.session?.let { s ->
            if (s.start == null && prefs.start != null) { s.start = prefs.start; s.end = prefs.end }
            else if (s.start != null && prefs.start == null) prefs.start = s.start
        }
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
        if (prefs.showMap) map = RouteMap(this, mapBox, b, this) { currentStart()?.point ?: actions.lastLocation(ask = false) }
        else mapBox.addView(mapOffNotice(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        topBar = buildTopBar()
        root.addView(topBar, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })
        pickBanner = buildPickBanner()
        root.addView(pickBanner, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })
        map?.let { m ->
            root.addView(m.chip, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.START })
        }

        sheet = RouteSheet(this, this)
        val sheetParams = CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        sheetParams.behavior = BottomSheetBehavior<LinearLayout>()
        root.addView(sheet.view, sheetParams)
        // Search suggestions float above the sheet, under the search bar.
        searchPanel = SearchPanel(this, search, { actions.addressSearch() }) { s, typed ->
            hideKeyboard()
            val p = s.point ?: return@SearchPanel
            prefs.addRecent(s)
            if (searchingEnd) setEnd(RouteEnd(EndMode.ADDRESS, p, s.label))
            else if (searchingStart || currentStart() == null) setStart(RouteStart(p, s.label))
            else actions.addStopAt(s.label, p, typed, s.approximate)
        }
        root.addView(searchPanel.card, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })
        snackAnchor = android.view.View(this)
        root.addView(snackAnchor, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { gravity = Gravity.BOTTOM })
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
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            topInset = bars.top
            bottomInset = maxOf(bars.bottom, ime.bottom)
            (snackAnchor.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = bottomInset + dp(8)
            snackAnchor.requestLayout()
            (topBar.layoutParams as ViewGroup.MarginLayoutParams).setMargins(bars.left + dp(12), bars.top + dp(8), bars.right + dp(12), 0)
            topBar.requestLayout()
            sheet.setBottomInset(bars.bottom)
            root.post { updateSheetGeometry() }
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        handleIntent(intent)
        root.post { GoogleTransport.warmUp(this) } // async; plain HTTP until Play services' Cronet is ready
        if (!prefs.setupShown) {
            prefs.setupShown = true
            root.post { showPermissions() }
        }
    }

    private fun showPermissions() {
        if (isFinishing || isDestroyed || permissions?.isShowing == true) return
        permissions = PermissionsSheet(this, { prefs.geocoderUrl }) { permissions = null }.also { it.show() }
    }

    private fun hideKeyboard() {
        search.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
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
                if (id == EditorInfo.IME_ACTION_SEARCH || enter) { searchPanel.submit(); true } else false
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
        pickLabel = label(14f, M.attr.colorOnTertiaryContainer).apply { text = "Tap the map where you park or drop off" }
        row.addWeighted(pickLabel)
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
        (searchPanel.card.layoutParams as ViewGroup.MarginLayoutParams).setMargins(dp(12), barBottom + dp(6), dp(12), 0)
        searchPanel.card.requestLayout()
        map?.chip?.let { c ->
            (c.layoutParams as ViewGroup.MarginLayoutParams).setMargins(dp(16), barBottom + dp(6), dp(16), 0)
            c.requestLayout()
        }
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
            menu.add(0, 6, 0, "Floating button").apply { isCheckable = true; isChecked = OverlayService.running }
            menu.add(0, 9, 0, "Permissions & network")
            menu.add(0, 7, 0, "Settings")
            if (s != null && s.stops.any { it.status != Status.PENDING }) menu.add(0, 10, 0, "Export delivery report")
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
                    9 -> showPermissions()
                    10 -> exportReport()
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

    override fun onStart() {
        super.onStart()
        map?.onStart()
        SessionStore.observe(refresh)
        NetStatus.observe(netChanged)
        NetStatus.check(this, prefs.geocoderUrl)
        render()
    }
    override fun onResume() {
        super.onResume()
        map?.onResume()
        OverlayService.appInForeground = true
        permissions?.refresh()
        if (overlayWanted && Settings.canDrawOverlays(this)) { overlayWanted = false; startOverlay() }
    }
    override fun onPause() { OverlayService.appInForeground = false; map?.onPause(); super.onPause() }
    override fun onStop() { SessionStore.unobserve(refresh); NetStatus.unobserve(netChanged); map?.onStop(); super.onStop() }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        permissions?.refresh()
        if (req == REQ_START_LOCATION) {
            if (LocationFix.hasPermission(this)) useMyLocation()
            else MaterialAlertDialogBuilder(this).setTitle("Location not allowed")
                .setMessage("Without location you can still search for your starting address or pick it on the map. To use your position later, allow Location in the permissions screen.")
                .setPositiveButton("Permissions") { _, _ -> showPermissions() }
                .setNeutralButton("Search address") { _, _ -> searchStart() }
                .setNegativeButton("Close", null).show()
        }
    }
    override fun onDestroy() { map?.onDestroy(); map = null; super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); map?.onLowMemory() }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); map?.onSaveInstanceState(out) }

    @Deprecated("Activity back handling; fine for a single-activity app")
    override fun onBackPressed() {
        when {
            searchPanel.isShowing -> { searchPanel.clear(); searchingStart = false; searchingEnd = false; render() }
            map?.pickingFor != null -> endPick()
            behavior.state == BottomSheetBehavior.STATE_EXPANDED && map != null -> behavior.state = BottomSheetBehavior.STATE_COLLAPSED
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /** The route start: the open route's, else the saved default. */
    private fun currentStart(): RouteStart? = SessionStore.session?.start ?: prefs.start

    private fun render() {
        val s = SessionStore.session
        val start = currentStart()
        sheet.render(s, start, s?.end?.mode ?: prefs.end.mode)
        map?.render(s, selectedId, start, endAddress())
        search.hint = when {
            searchingEnd -> "Search the ending address"
            searchingStart || start == null -> "Search your starting address"
            else -> "Add a stop"
        }
        val curId = s?.current?.id?.takeIf { s.started }
        if (s != null && curId != null && curId != lastCursorId) sheet.list.post { sheet.scrollToCurrent(s) }
        lastCursorId = curId
    }

    // ---- Feedback ------------------------------------------------------------

    override fun snack(text: String, action: String?, onAction: (() -> Unit)?) {
        if (isFinishing || isDestroyed) return
        // Anchored above the nav bar / keyboard (insets are consumed at the root, so the
        // snackbar can't see them itself); LENGTH_LONG always auto-dismisses.
        val bar = Snackbar.make(root, text, Snackbar.LENGTH_LONG)
        if (action != null && onAction != null) bar.setAction(action) { onAction() }
        bar.anchorView = snackAnchor
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
        val stamp = System.currentTimeMillis()
        val dir = File(filesDir, "proof").apply { mkdirs() }
        val photo = File(dir, "$stopId-$stamp.jpg")
        val signature = File(dir, "$stopId-$stamp-sig.png")
        Dialogs.proof(this, photo, signature, takePhoto = { takePhoto(it) }) { note, path, sigPath ->
            SessionStore.act(this) { mark(stopId, Status.COMPLETED, note = note, photo = path, signature = sigPath) }
            val what = listOfNotNull("photo".takeIf { path != null }, "signature".takeIf { sigPath != null })
            snack("Stop $n delivered" + if (what.isNotEmpty()) " with ${what.joinToString(" and ")}" else "", "Undo") { undo(stopId) }
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

    override fun optimize() { if (requireStart()) actions.replan(optimize = true, showBusy = true) }
    override fun update() = actions.replan(optimize = false, showBusy = false)

    override fun startRoute() {
        if (!requireStart()) return
        val s = SessionStore.session ?: return
        s.start()
        SessionStore.commit(this)
        update() // ETAs from now
        if (currentStart()?.live == true) refreshLiveStart()
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    override fun prev() = SessionStore.act(this) { back(); null }
    override fun next() = SessionStore.act(this) { next(); null }

    override fun importCsv() {
        if (!requireStart()) return
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
        pickLabel.text = "Tap the map where you park or drop off"
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

    override fun onStopTapped(id: String) = if (id == RouteMap.START_ID || id == RouteMap.END_ID) editStart() else openStop(id)

    override fun onPointPicked(stopId: String, p: LatLng) {
        if (stopId == RouteMap.START_ID) { endPick(); startAtPoint(p); return }
        if (stopId == RouteMap.END_ID) { endPick(); endAtPoint(p); return }
        val s = SessionStore.session
        val st = s?.setAccess(stopId, p)
        endPick()
        if (s == null || st == null) return
        prefs.addressMemory.remember(st.address, p)
        SessionStore.commit(this)
        snack("Drop-off pin saved for this address")
        update()
    }

    override fun onMapRetry() = NetStatus.check(this, prefs.geocoderUrl)

    override fun onLongPress(p: LatLng) {
        val search = actions.addressSearch()
        actions.background({ search.reverse(p) }) { r ->
            if (isFinishing || isDestroyed) return@background
            val address = r.getOrNull()
            val s = SessionStore.session
            val cur = s?.current?.takeIf { s.started && it.status == Status.PENDING }
            Dialogs.mapPoint(this, address, canAddStop = currentStart() != null, currentStopLabel = cur?.let { "stop ${it.optimizedIndex}" },
                onStart = { setStart(RouteStart(p, address ?: droppedPin(p))) },
                onEnd = { setEnd(RouteEnd(EndMode.ADDRESS, p, address ?: droppedPin(p))) },
                onAdd = { name -> actions.addStopAt(name, p) },
                onDropOff = { cur?.let { onPointPicked(it.id, p) } })
        }
    }

    // ---- Route start -----------------------------------------------------------

    /** Gate for planning actions: true when a start exists, else points the driver at the start card. */
    private fun requireStart(): Boolean {
        if (currentStart() != null) return true
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        snack("Set your starting point first: use your location, search an address, or pick it on the map.")
        return false
    }

    private fun droppedPin(p: LatLng) = "Dropped pin (%.5f, %.5f)".format(java.util.Locale.ROOT, p.lat, p.lng)

    /** New start: saved as the default for future routes and applied to the open route (ETAs refresh). */
    private fun setStart(start: RouteStart, quiet: Boolean = false) {
        searchingStart = false
        prefs.start = start
        val s = SessionStore.session
        if (s != null) {
            s.start = start
            SessionStore.commit(this)
            if (s.stops.any { it.status == Status.PENDING }) update()
        } else render()
        if (!quiet) snack(if (start.live) "Starting from your location" + (start.accuracyM?.let { " (±${it.toInt()} m)" } ?: "")
            else "Start: ${splitAddress(start.label).first}")
    }

    private fun startAtPoint(p: LatLng) {
        val search = actions.addressSearch()
        actions.background({ search.reverse(p) }) { r -> setStart(RouteStart(p, r.getOrNull() ?: droppedPin(p))) }
    }

    override fun editStart() {
        Dialogs.startOptions(this, SessionStore.session?.end ?: prefs.end,
            onLocation = { useMyLocation() },
            onSearch = { searchStart() },
            onPick = if (map != null) ({ pickStartOnMap() }) else null,
            onSearchEnd = { searchEnd() },
            onPickEnd = if (map != null) ({ pickEndOnMap() }) else null,
            onEnd = { setEnd(it) })
    }

    /** The chosen end address, when the route ends at one. */
    private fun endAddress(): LatLng? = (SessionStore.session?.end ?: prefs.end).takeIf { it.mode == EndMode.ADDRESS }?.point

    /** New end rule: saved as the default and applied to the open route (ETAs refresh). */
    private fun setEnd(end: RouteEnd) {
        searchingEnd = false
        prefs.end = end
        SessionStore.session?.let { s -> s.end = end; SessionStore.commit(this); update() } ?: render()
        if (end.mode == EndMode.ADDRESS) snack("Route ends at ${splitAddress(end.label ?: "chosen address").first}")
    }

    private fun endAtPoint(p: LatLng) {
        val search = actions.addressSearch()
        actions.background({ search.reverse(p) }) { r -> setEnd(RouteEnd(EndMode.ADDRESS, p, r.getOrNull() ?: droppedPin(p))) }
    }

    private fun searchEnd() {
        searchingEnd = true
        searchingStart = false
        render()
        focusSearch()
        snack("Type the ending address in the search bar")
    }

    private fun pickEndOnMap() {
        val m = map ?: run { snack("The map is off (Settings). Search for the address instead."); return }
        m.pickingFor = RouteMap.END_ID
        pickLabel.text = "Tap the map where the route ends"
        pickBanner.visibility = android.view.View.VISIBLE
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    /** "Use my current location": fresh platform fix; explains and offers the permission flow when needed. */
    override fun useMyLocation() {
        if (!LocationFix.hasPermission(this)) {
            MaterialAlertDialogBuilder(this).setTitle("Use your location?")
                .setMessage("Route Planner uses your position as the route start and to find nearby addresses first. It is only read while the app is open.")
                .setPositiveButton("Allow") { _, _ ->
                    requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), REQ_START_LOCATION)
                }
                .setNegativeButton("Not now", null).show()
            return
        }
        if (locating) return
        locating = true
        snack("Getting your location…")
        LocationFix.current(this) { fix, stale, error ->
            locating = false
            if (isFinishing || isDestroyed) return@current
            if (fix == null) {
                MaterialAlertDialogBuilder(this).setTitle("No location").setMessage(error ?: "No fix.")
                    .setPositiveButton("Try again") { _, _ -> useMyLocation() }
                    .setNeutralButton("Search address") { _, _ -> searchStart() }
                    .setNegativeButton("Close", null).show()
                return@current
            }
            setStart(RouteStart(fix.point, "My location", live = true, fixAtMs = fix.timeMs, accuracyM = fix.accuracyM), quiet = stale)
            if (stale) snack("No fresh fix yet; using your last known position.", "Retry") { useMyLocation() }
        }
    }

    /** Live start: refresh the fix silently (e.g. when the route starts). */
    private fun refreshLiveStart() {
        if (locating || !LocationFix.hasPermission(this)) return
        locating = true
        LocationFix.current(this) { fix, _, _ ->
            locating = false
            if (fix != null && currentStart()?.live == true && !isDestroyed)
                setStart(RouteStart(fix.point, "My location", live = true, fixAtMs = fix.timeMs, accuracyM = fix.accuracyM), quiet = true)
        }
    }

    override fun searchStart() {
        searchingStart = true
        render()
        focusSearch()
        snack("Type your starting address in the search bar")
    }

    override fun pickStartOnMap() {
        val m = map ?: run { snack("The map is off (Settings). Use your location or search instead."); return }
        m.pickingFor = RouteMap.START_ID
        pickLabel.text = "Tap the map at your starting point"
        pickBanner.visibility = android.view.View.VISIBLE
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    // ---- Inputs --------------------------------------------------------------

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

    /** ⋮ → Export delivery report: a zip (CSV + proof photos + signatures) handed to the share sheet. */
    private fun exportReport() {
        val s = SessionStore.session ?: return
        val stops = s.stops
        val packages = s.packages
        val busy = Dialogs.busy(this, "Building report…")
        Thread {
            val r = runCatching { ProofExport.build(this, stops, packages) }
            runOnUiThread {
                busy.dismiss()
                r.onSuccess { zip ->
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, "Delivery report")
                            .putExtra(Intent.EXTRA_TEXT, DeliveryReport.summary(stops))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share delivery report"))
                }.onFailure { snack("Couldn't build the report: ${it.message}") }
            }
        }.start()
    }

    /** ⋮ → Floating button: a real on/off toggle. */
    private fun toggleOverlay() {
        if (OverlayService.running) { OverlayService.stop(this); snack("Floating button off"); return }
        if (!Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow display over other apps")
                .setMessage("The floating button sits on top of Google Maps or Waze. Android only grants this in its Settings: on the next screen, find Route Planner and turn on \"Allow display over other apps\", then come back.")
                .setPositiveButton("Open Settings") { _, _ ->
                    overlayWanted = true
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Not now", null)
                .show()
            return
        }
        startOverlay()
    }

    private fun startOverlay() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PermissionsSheet.REQ)
        OverlayService.start(this)
        snack("Floating button on: it shows over your navigation app. Tap it for actions, drag to move.")
    }

    private fun showSettings() {
        val tiles = Routing.tilesFile(this)
        val model = Dialogs.SettingsModel(
            waze = prefs.navApp == NavApp.WAZE,
            autoNavigate = prefs.autoNavigate,
            showMap = prefs.showMap,
            serverUrl = prefs.valhallaUrl,
            geocoderUrl = prefs.geocoderUrl,
            googleSuggest = prefs.googleSuggest,
            googleTransport = GoogleTransport.description,
            addressInfo = AddressPack.summary(this) ?: "No address data. Import a Tennessee addresses.sqlite to look up house addresses offline.",
            addressAttribution = AddressPack.meta(this)["attribution"],
            recentCount = prefs.recents.size,
            roadsInfo = if (tiles.exists()) "Offline road data installed (${tiles.length() / 1_000_000} MB)."
            else "No offline road data: times come from the server below, or straight-line estimates.",
        )
        Dialogs.settings(this, model, openPermissions = { showPermissions() },
            importAddresses = {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_ADDRESSES)
            },
            manageRecents = { manageRecents() },
            openOfflineMaps = { showOfflineMaps() },
            importRoads = {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_TILES)
        }) { m ->
            prefs.navApp = if (m.waze) NavApp.WAZE else NavApp.GOOGLE
            prefs.autoNavigate = m.autoNavigate
            prefs.valhallaUrl = m.serverUrl
            prefs.geocoderUrl = m.geocoderUrl
            prefs.googleSuggest = m.googleSuggest
            SessionStore.session?.let { s -> s.navApp = prefs.navApp; s.autoNavigate = m.autoNavigate; SessionStore.commit(this) }
            if (m.showMap != prefs.showMap) { prefs.showMap = m.showMap; recreate() }
        }
    }

    // ---- Offline maps ----------------------------------------------------------

    /** The area to save: everything the route touches, padded so the edges aren't the screen edge. */
    private fun routeArea(): Bounds? {
        val s = SessionStore.session
        val pts = s?.stops?.map { it.target }.orEmpty() + listOfNotNull(currentStart()?.point, endAddress())
        return Bounds.around(pts)?.padded(0.25, 0.02)
    }

    private fun showOfflineMaps() {
        OfflineMaps.list(this) { saved ->
            if (isFinishing || isDestroyed) return@list
            OfflineMapsDialog.show(this, saved, canDownload = routeArea() != null,
                onDownload = { confirmOfflineDownload() },
                onDelete = { s -> Dialogs.confirm(this, "Delete \"${s.name}\"?", "The saved map is removed from this phone.", "Delete") {
                    OfflineMaps.delete(s) { snack("Offline map deleted"); showOfflineMaps() }
                } })
        }
    }

    private fun confirmOfflineDownload() {
        val area = routeArea() ?: run { snack("Set a starting point first so there's an area to save."); return }
        val plan = OfflineMaps.plan(area)
        val name = (currentStart()?.label?.let { splitAddress(it).first } ?: "Route area") + " area"
        val msg = "About %.0f × %.0f km, zoom %d–%d, roughly %d tiles per style (light and dark). Needs a connection and stays on this phone."
            .format(java.util.Locale.ROOT, plan.widthKm, plan.heightKm, plan.minZoom, plan.maxZoom, plan.tiles) +
            if (plan.maxZoom < OfflineMaps.MAX_ZOOM) "\n\nThe area is large, so street-level detail is limited to zoom ${plan.maxZoom}." else ""
        Dialogs.confirm(this, "Download offline map?", msg, "Download") { runOfflineDownload(name, plan) }
    }

    private fun runOfflineDownload(name: String, plan: OfflineMaps.Plan) {
        val text = android.widget.TextView(this).apply { setPadding(dp(24), dp(8), dp(24), 0); text = "Starting…" }
        var job: OfflineMaps.Job? = null
        val dlg = MaterialAlertDialogBuilder(this).setTitle("Downloading map").setView(text).setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> job?.cancel(); snack("Download cancelled") }.show()
        job = OfflineMaps.download(this, name, plan,
            onProgress = { p -> text.text = "${p.percent}% · ${p.text}" },
            onDone = { r ->
                if (dlg.isShowing && !isDestroyed) dlg.dismiss()
                r.onSuccess { snack("Offline map saved: $name") }
                    .onFailure { snack("Map download failed: ${it.message}") }
            })
    }

    /** Settings → recent searches: remove one, or clear all. */
    private fun manageRecents() {
        val items = prefs.recents
        if (items.isEmpty()) { snack("No recent searches"); return }
        MaterialAlertDialogBuilder(this).setTitle("Recent searches")
            .setItems(items.map { it.label }.toTypedArray()) { _, i ->
                Dialogs.confirm(this, "Remove from recents?", items[i].label, "Remove") {
                    prefs.recents = prefs.recents.filter { SuggestRanker.key(it) != SuggestRanker.key(items[i]) }
                }
            }
            .setNeutralButton("Clear all") { _, _ -> prefs.recents = emptyList(); snack("Recent searches cleared") }
            .setNegativeButton("Close", null).show()
    }

    @Deprecated("fine for a single-activity app")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        when (req) {
            REQ_IMPORT -> data?.data?.let { actions.importCsv(it) }
            REQ_TILES -> data?.data?.let { actions.importTiles(it) }
            REQ_ADDRESSES -> data?.data?.let { actions.importAddressPack(it) { } }
            REQ_VOICE -> data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                search.setText(it)
                searchPanel.submit()
            }
            REQ_PHOTO -> Toast.makeText(this, "Photo attached", Toast.LENGTH_SHORT).show()
        }
    }
}
