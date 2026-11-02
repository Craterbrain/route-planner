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

    override fun attachBaseContext(base: Context) = super.attachBaseContext(ThemeMode.wrap(base)) // Settings → Theme
    private val scanFlow by lazy { ScanFlow(this, actions, this, { search }, { currentStart()?.point }, { map != null }, ::pickDropOff) }
    private val settings by lazy {
        SettingsFlow(this, prefs, this, { showPermissions() }, { offlineMaps.show() }, { storageFlow.show() }, { Welcome.show(this) }, ::pickDocument, REQ_ADDRESSES, REQ_TILES, { roadDownload.show(blocking = false) }, ::floatingSettingChanged)
    }
    private val roadDownload by lazy {
        RoadDownloadFlow(this, {
            roadKey = null
            updateRoadPath(SessionStore.session, currentStart())
            prefs.dataUpdateState = ""
            snack("Routing with on-device roads")
        }, { pickDocument(REQ_TILES) })
    }
    private var checkedUpdates = false

    /** Once a week at most, ask GitHub whether newer road / address data is published, and tell the user (never downloads by itself). */
    private fun checkDataUpdates() {
        if (checkedUpdates || isFinishing || isDestroyed) return
        checkedUpdates = true
        val day = 24 * 3_600_000L
        if (prefs.dataUpdateState.isNotEmpty()) { announceDataUpdate(); return } // known from an earlier check; nag once per launch
        if (System.currentTimeMillis() - prefs.dataCheckedAt < 7 * day || NetStatus.systemSaysOffline(this)) return
        actions.background({ RoadDownload.updateAvailable(this) }) { r ->
            r.onFailure { AppLog.w("data update check failed", it) }
            val s = r.getOrNull()
            if (r.isSuccess) prefs.dataCheckedAt = System.currentTimeMillis()
            if (s != null) { prefs.dataUpdateState = s; announceDataUpdate() }
        }
    }

    private fun announceDataUpdate() {
        val s = prefs.dataUpdateState.takeIf { it.isNotEmpty() } ?: return
        val name = s.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        snack("Newer road and address data is available for $name", "Update") { roadDownload.show(blocking = false, preselect = s) }
    }

    private var askedRoads = false
    private var firstRun = false

    /** Without road data (and no server set) the app can't route along roads: ask once per launch, blocking. */
    private fun maybeAskRoads() {
        if (askedRoads || isFinishing || isDestroyed) return
        askedRoads = true
        if (Routing.tilesFile(this).exists() || prefs.valhallaUrl.isNotEmpty()) { checkDataUpdates(); return }
        roadDownload.show(blocking = true)
    }
    private val storageFlow by lazy { StorageFlow(this, actions, this) }
    private val library by lazy { RouteLibraryFlow(this, actions, prefs, this) }
    private val offlineMaps by lazy { OfflineMapsFlow(this, { routeArea() }, { currentStart()?.label }, ::snack) }
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
        const val ACTION_OPEN_STOP = "app.routeplanner.OPEN_STOP"
        private const val REQ_IMPORT = 1
        private const val REQ_TILES = 3
        private const val REQ_VOICE = 4
        private const val REQ_PHOTO = 5
        private const val REQ_START_LOCATION = 8
        private const val REQ_ADDRESSES = 9
        private const val REQ_SCAN_LABEL = 10
        private const val REQ_SCAN_FIND = 11
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
        searchPanel = SearchPanel(this, search, { actions.addressSearch() }, onAddAnyway = { text -> scanFlow.addAnyway(text) }) { s, typed ->
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
        val fitList = { sheet.setOverflow((sheet.view.bottom - root.height).coerceAtLeast(0)) }
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(v: android.view.View, newState: Int) = fitList()
            override fun onSlide(v: android.view.View, offset: Float) = fitList()
        })
        sheet.view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> root.post { fitList() } }
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
        // A crash since the driver last looked: offer the log once (it holds no addresses or typed text).
        CrashLog.lastCrashAt(this)?.takeIf { it > prefs.seenCrashAt }?.let { at ->
            prefs.seenCrashAt = at
            root.post { snack("The app crashed last time. Share the log so it can be fixed?", "Share") { DiagnosticsShare.share(this) } }
        }
        root.post { GoogleTransport.warmUp(this) } // async; plain HTTP until Play services' Cronet is ready
        if (!prefs.setupShown) {
            prefs.setupShown = true
            firstRun = true
            root.post { Welcome.show(this) { showPermissions() } } // welcome first, then the permissions list
        }
    }

    private fun showPermissions() {
        if (isFinishing || isDestroyed || permissions?.isShowing == true) return
        permissions = PermissionsSheet(this, { prefs.geocoderUrl }) { permissions = null; maybeAskRoads() }.also { it.show() }
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
        row.addView(iconButton(R.drawable.ic_scan, "Scan a shipping label") { scanLabel() })
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
        // The collapsed sheet also clears the navigation bar, so no button sits under it.
        val peek = (sheet.handle.height + sheet.header.height + dp(4) + bottomInset)
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
            menu.add(0, 11, 0, "Scan label")
            if (s != null && s.packages.isNotEmpty()) menu.add(0, 12, 0, "Check a parcel (scan)")
            if (hasPending) menu.add(0, 2, 0, "Optimize route")
            if (hasPending) menu.add(0, 3, 0, "Update times (keep order)")
            if (hasPending) menu.add(0, 4, 0, "Reverse route")
            if (map != null && s != null) menu.add(0, 5, 0, "Show whole route")
            menu.add(0, 9, 0, "Permissions & network")
            menu.add(0, 7, 0, "Settings")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> importCsv()
                    2 -> optimize()
                    3 -> actions.replan(optimize = false, showBusy = true)
                    4 -> SessionStore.session?.let { r -> r.reverse(); SessionStore.commit(this@MainActivity); update() }
                    5 -> map?.frameAll()
                    7 -> settings.show()
                    9 -> showPermissions()
                    11 -> scanLabel()
                    12 -> startActivityForResult(Intent(this@MainActivity, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_FIND), REQ_SCAN_FIND)
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
        if (i.action == ACTION_OPEN_STOP) { setIntent(Intent()); SessionStore.session?.current?.let { openStop(it.id) } }
        // A CSV shared or opened from another app (a route from another phone, or an address list).
        RouteSharing.uriFrom(i)?.let { uri -> setIntent(Intent()); actions.importRouteFile(uri) }
    }

    /** ⋮ → Share route…: a CSV through the share sheet. With finished stops, asks whether to send only what is left. */
    private fun shareRoute() {
        val s = SessionStore.session ?: return
        val done = s.stops.any { it.status != Status.PENDING }
        val pending = s.stops.any { it.status == Status.PENDING }
        if (!done) { RouteSharing.share(this, s, includeFinished = false); return }
        if (!pending) { RouteSharing.share(this, s, includeFinished = true); return }
        MaterialAlertDialogBuilder(this).setTitle("Share route")
            .setItems(arrayOf("Remaining stops (hand off)", "All stops")) { _, i -> RouteSharing.share(this, s, includeFinished = i == 1) }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onStart() {
        super.onStart()
        roadKey = null // try the road path again (road data may have been imported since)
        if (!resumedOverlay) { resumedOverlay = true; if (SessionStore.session?.started == true) startFloatingButton() } // reopened mid-route
        if (!firstRun) window.decorView.post { maybeAskRoads() } // first launch asks after the welcome and permissions
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

    // The road-following line: worked out in the background whenever the points the route drives through change.
    private var roadKey: List<LatLng>? = null
    private var roadGen = 0

    /** Start (before any stop is done), the stops still to do, and the end: the line's waypoints, as the map draws them. */
    private fun routePoints(s: RouteSession?, start: RouteStart?): List<LatLng> {
        if (s == null) return emptyList()
        val pending = s.stops.filter { it.status == Status.PENDING }
        if (pending.isEmpty()) return emptyList()
        val underway = s.stops.any { it.status != Status.PENDING }
        return (if (!underway) listOfNotNull(start?.point) else emptyList()) + pending.map { it.access } + listOfNotNull(s.endPoint())
    }

    private fun updateRoadPath(s: RouteSession?, start: RouteStart?) {
        val m = map ?: return
        val points = routePoints(s, start)
        if (points == roadKey) return
        roadKey = points
        m.setRoadPath(null) // a stale line would highlight the wrong roads; the dashed one shows meanwhile
        if (points.size < 2) return
        val roads = Routing.roads(this, prefs)
        if (roads == null) { AppLog.w("roads: no road data or server set up; drawing straight lines"); return }
        val mine = ++roadGen
        actions.background({ roads.path(points) }) { r ->
            if (mine != roadGen || isFinishing || isDestroyed) return@background
            val res = r.getOrNull()
            if (res == null) AppLog.w("roads: lookup failed", r.exceptionOrNull())
            else AppLog.i("roads: ${res.roadLegs} of ${res.legs} legs followed roads")
            m.setRoadPath(res?.takeIf { it.roadLegs > 0 }?.path)
        }
    }

    private fun render() {
        val s = SessionStore.session
        val start = currentStart()
        sheet.render(s, start, s?.end?.mode ?: prefs.end.mode)
        map?.render(s, selectedId, start, endAddress())
        updateRoadPath(s, start)
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
        startFloatingButton()
    }

    /** Stop: leave driving mode for the planning screen. Nothing is lost; the snackbar can resume. */
    override fun stopRoute() {
        val s = SessionStore.session ?: return
        s.stop()
        SessionStore.commit(this)
        snack("Stopped routing. Your progress is kept.", "Resume") {
            SessionStore.session?.let { it.started = true; SessionStore.commit(this) }
        }
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

    // ---- Route library: New / Save / Open (the dialogs live in RouteLibraryFlow) --

    /** The card's ⋮ menu. */
    override fun routeMenu(anchor: android.view.View) {
        val has = SessionStore.session?.stops?.isNotEmpty() == true
        android.widget.PopupMenu(this, anchor).apply {
            val done = SessionStore.session?.stops?.any { it.status != Status.PENDING } == true
            menu.add(0, 1, 0, "New route")
            if (has) menu.add(0, 2, 0, "Save route")
            menu.add(0, 3, 0, "Open route")
            if (has) menu.add(0, 4, 0, "Share route…")
            if (done) menu.add(0, 5, 0, "Export delivery report")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> newRoute(); 2 -> library.save(); 3 -> library.open(); 4 -> shareRoute()
                    5 -> SessionStore.session?.let { s -> ProofExport.share(this@MainActivity, s.stops, s.packages, ::snack) }
                }
                true
            }
        }.show()
    }

    override fun newRoute() = library.newRoute()

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
        val st = s?.placeStop(stopId, p)
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

    /** Opens the system file picker; the result comes back to onActivityResult under [req]. */
    private fun pickDocument(req: Int) =
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), req)

    /** Search bar camera button / ⋮ → Scan label. */
    private fun scanLabel() {
        if (!requireStart()) return
        startActivityForResult(Intent(this, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_LABEL), REQ_SCAN_LABEL)
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

    private var askedOverlayPermission = false
    private var resumedOverlay = false

    /** Settings → Floating button toggled. Off removes the button now; on brings it up if a route is running. */
    private fun floatingSettingChanged(on: Boolean) {
        if (!on) { OverlayService.stop(this); snack("Floating button off"); return }
        if (SessionStore.session?.started == true) startFloatingButton(askPermission = true)
        else snack("Floating button on: it shows when you start a route")
    }

    /** Brings the floating button up when the setting is on, asking for "display over other apps" (once per launch) if needed. */
    private fun startFloatingButton(askPermission: Boolean = false) {
        if (!prefs.floatingButton || OverlayService.running) return
        if (!Settings.canDrawOverlays(this)) {
            if (!askPermission && askedOverlayPermission) return
            askedOverlayPermission = true
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow display over other apps")
                .setMessage("The floating button sits on top of Google Maps or Waze. Android only grants this in its Settings: on the next screen, find Route Planner and turn on \"Allow display over other apps\", then come back. You can turn the button off in Settings.")
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
    }

    // ---- Offline maps (the dialogs live in OfflineMapsFlow) ---------------------

    /** The area to save: everything the route touches, padded so the edges aren't the screen edge. */
    private fun routeArea(): Bounds? {
        val s = SessionStore.session
        val pts = s?.stops?.map { it.target }.orEmpty() + listOfNotNull(currentStart()?.point, endAddress())
        return Bounds.around(pts)?.padded(0.25, 0.02)
    }

    @Deprecated("fine for a single-activity app")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        when (req) {
            REQ_IMPORT -> data?.data?.let { actions.importRouteFile(it) }
            REQ_TILES -> data?.data?.let { actions.importTiles(it) }
            REQ_ADDRESSES -> data?.data?.let { actions.importAddressPack(it) { } }
            REQ_VOICE -> data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                search.setText(it)
                searchPanel.submit()
            }
            REQ_PHOTO -> Toast.makeText(this, "Photo attached", Toast.LENGTH_SHORT).show()
            REQ_SCAN_LABEL -> data?.getStringExtra(ScanActivity.EXTRA_ADDRESS)?.let { scanFlow.addScanned(it, data.getStringExtra(ScanActivity.EXTRA_NAME), data.getStringArrayListExtra(ScanActivity.EXTRA_CODES).orEmpty()) } ?: snack("No address read")
            REQ_SCAN_FIND -> data?.getStringExtra(ScanActivity.EXTRA_STOP_ID)?.let { id ->
                SessionStore.session?.stop(id)?.let { st -> snack("Stop ${st.optimizedIndex} · ID ${st.entryNo}"); openStop(id) }
            }
        }
    }
}
