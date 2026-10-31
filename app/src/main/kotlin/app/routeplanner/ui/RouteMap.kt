package app.routeplanner.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import app.routeplanner.NetStatus
import app.routeplanner.color
import app.routeplanner.core.LatLng
import app.routeplanner.core.RouteSession
import app.routeplanner.core.RouteStart
import app.routeplanner.core.Status
import com.google.android.material.R as M
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.log.Logger
import org.maplibre.android.log.LoggerDefinition
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory as P
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.tile.TileOperation
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng as MapLatLng

/**
 * The route map: numbered stop pins (current stop larger, finished dimmed,
 * failed in the error color), a dashed line through the remaining stops,
 * and the selected stop's drop-off pin.
 *
 * Robustness, because the first device run showed only MapLibre's beige
 * "loading" color:
 * - Pins are bitmaps drawn here and added as style images, so they need no
 *   glyphs, sprites or network.
 * - The basemap (OpenFreeMap) gets [STYLE_TIMEOUT_MS]; on failure or timeout
 *   we switch to an offline style that is just a themed background, so pins
 *   and the route line still show. MapLibre waits silently (no error) when
 *   Android reports no network for the app, which is what a blocked
 *   GrapheneOS "Network" permission looks like, so the timeout matters.
 * - A status chip shows what happened (tap to retry) and everything is
 *   logged under the tag "RoutePlanner".
 */
class RouteMap(
    private val ctx: Context,
    private val container: FrameLayout,
    savedState: Bundle?,
    private val listener: Listener,
    /** Camera target when there are no stops (last known location, or null for Tennessee). */
    private val defaultCenter: () -> LatLng?,
) {
    interface Listener {
        fun onStopTapped(id: String)
        /** Tap while [pickingFor] is set: the driver chose a drop-off point. */
        fun onPointPicked(stopId: String, p: LatLng)
        fun onLongPress(p: LatLng)
        /** Chip tapped: re-check the network (the map retries the basemap itself). */
        fun onMapRetry()
    }

    private var view: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private val main = Handler(Looper.getMainLooper())
    private val notice = ctx.label(13f, M.attr.colorOnSurfaceVariant).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dp(24), ctx.dp(24), ctx.dp(24), ctx.dp(24))
    }
    /** Small diagnostics line under the search bar ("Map: …"). Tap = retry. */
    val chip: TextView = ctx.label(12f, M.attr.colorOnSurfaceVariant).apply {
        background = ctx.roundRect(M.attr.colorSurfaceContainerHigh, 12)
        setPadding(ctx.dp(10), ctx.dp(5), ctx.dp(10), ctx.dp(5))
        visibility = View.GONE
        setOnClickListener { retry() }
    }

    /** Stop id whose drop-off pin the next map tap sets, or null. */
    var pickingFor: String? = null

    /** Space covered by the search bar (top) and the collapsed sheet (bottom), in px. */
    var insetTop = 0
    var insetBottom = 0

    private var last: Render? = null
    private var framedRoute: Any? = null
    private var focusedId: String? = null
    private var basemap = Basemap.LOADING
    private var tileErrors = 0
    private var lastError: String? = null
    private var firstFrame = false
    private val images = HashSet<String>()

    private enum class Basemap { LOADING, OK, FALLBACK }

    private class Render(val s: RouteSession?, val selectedId: String?, val start: RouteStart?, val end: LatLng?)

    init {
        installLogger()
        container.addView(notice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        try {
            MapLibre.getInstance(ctx.applicationContext)
            // Theme surface instead of MapLibre's default beige while nothing has rendered.
            val opts = MapLibreMapOptions.createFromAttributes(ctx).foregroundLoadColor(ctx.color(M.attr.colorSurfaceContainer))
            val mv = MapView(ctx, opts)
            mv.onCreate(savedState)
            container.addView(mv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            mv.addOnDidFailLoadingMapListener { msg -> onBasemapFailed("couldn't load style: $msg") }
            mv.addOnDidFinishRenderingFrameListener { fully, _, _ ->
                if (fully && !firstFrame) { firstFrame = true; Log.i(NetStatus.TAG, "map: first full frame (basemap=$basemap)"); updateChip() }
            }
            mv.addOnTileActionListener { op, _, _, _, _, _, sourceId ->
                if (op == TileOperation.Error) {
                    tileErrors++
                    if (tileErrors == 1 || tileErrors % 20 == 0) Log.w(NetStatus.TAG, "map: tile error #$tileErrors (source $sourceId)")
                    main.post { updateChip() }
                }
            }
            mv.addOnSpriteErrorListener { id, url -> Log.w(NetStatus.TAG, "map: sprite error $id $url") }
            mv.addOnGlyphsErrorListener { stack, _, _ -> Log.w(NetStatus.TAG, "map: glyph error ${stack.joinToString()}") }
            mv.addOnRenderErrorListener { lastError = "render error"; Log.e(NetStatus.TAG, "map: render error"); main.post { updateChip() } }
            mv.getMapAsync { m -> setUp(m) }
            view = mv
            setChip("Map: starting…")
        } catch (t: Throwable) {
            // Native library or GL problems: keep the app usable without a map.
            Log.e(NetStatus.TAG, "map: MapLibre failed to start", t)
            view = null
            showNotice("Map unavailable on this device (${t.javaClass.simpleName}). The stop list still works.")
        }
    }

    /** Routes MapLibre's own log lines to logcat under our tag and keeps the last error for the chip. */
    private fun installLogger() {
        Logger.setLoggerDefinition(object : LoggerDefinition {
            override fun v(tag: String, msg: String) {}
            override fun v(tag: String, msg: String, tr: Throwable) {}
            override fun d(tag: String, msg: String) {}
            override fun d(tag: String, msg: String, tr: Throwable) {}
            override fun i(tag: String, msg: String) { Log.i(NetStatus.TAG, "maplibre[$tag] $msg") }
            override fun i(tag: String, msg: String, tr: Throwable) { Log.i(NetStatus.TAG, "maplibre[$tag] $msg", tr) }
            override fun w(tag: String, msg: String) { Log.w(NetStatus.TAG, "maplibre[$tag] $msg") }
            override fun w(tag: String, msg: String, tr: Throwable) { Log.w(NetStatus.TAG, "maplibre[$tag] $msg", tr) }
            override fun e(tag: String, msg: String) { Log.e(NetStatus.TAG, "maplibre[$tag] $msg"); lastError = msg.take(80) }
            override fun e(tag: String, msg: String, tr: Throwable) { Log.e(NetStatus.TAG, "maplibre[$tag] $msg", tr); lastError = msg.take(80) }
        })
    }

    private fun showNotice(text: String) {
        notice.text = text
        notice.background = ctx.roundRect(M.attr.colorSurfaceContainer, 0)
        notice.bringToFront()
    }

    private fun setChip(text: String?) {
        if (text == null) { chip.visibility = View.GONE; return }
        chip.text = text
        chip.visibility = View.VISIBLE
        Log.i(NetStatus.TAG, text)
    }

    private val hideChip = Runnable { if (basemap == Basemap.OK && tileErrors == 0) chip.visibility = View.GONE }

    private fun updateChip() {
        val net = NetStatus.last
        val text = when (basemap) {
            Basemap.LOADING -> "Map: loading basemap…"
            Basemap.FALLBACK -> "Map: " + (if (net.state == NetStatus.State.NO_NETWORK) "no network access" else lastError ?: "basemap unavailable") +
                " · pins only · tap to retry"
            Basemap.OK -> when {
                tileErrors > 0 -> "Map: $tileErrors tile errors" + (if (net.state == NetStatus.State.NO_NETWORK) " (no network access)" else "") + " · tap to retry"
                firstFrame -> "Map: style loaded · tiles ok"
                else -> "Map: style loaded · loading tiles…"
            }
        }
        setChip(text)
        main.removeCallbacks(hideChip)
        if (basemap == Basemap.OK && tileErrors == 0 && firstFrame) main.postDelayed(hideChip, 3000)
    }

    /** Called by the host when [NetStatus] changes. */
    fun onNetworkChanged() {
        val st = NetStatus.last.state
        if (st == NetStatus.State.OK) {
            // Android said "offline" to MapLibre but our own request got through: tell MapLibre.
            if (MapLibre.isConnected() == false) { Log.w(NetStatus.TAG, "map: forcing MapLibre online"); MapLibre.setConnected(true) }
            if (basemap == Basemap.FALLBACK) loadBasemap()
        }
        updateChip()
    }

    fun retry() {
        listener.onMapRetry()
        tileErrors = 0
        lastError = null
        loadBasemap()
    }

    private fun styleUrl(): String {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) NetStatus.MAP_STYLE_DARK else NetStatus.MAP_STYLE_LIGHT
    }

    /** A style with no network resources at all: themed background only. */
    private fun fallbackStyle(): String {
        val bg = "#%06X".format(ctx.color(M.attr.colorSurfaceContainer) and 0xFFFFFF)
        return """{"version":8,"name":"offline","sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"$bg"}}]}"""
    }

    private val styleTimeout = Runnable {
        if (basemap == Basemap.LOADING) onBasemapFailed(if (NetStatus.systemSaysOffline(ctx)) "no network access" else "basemap timed out")
    }

    private fun setUp(m: MapLibreMap) {
        map = m
        m.uiSettings.apply {
            setLogoEnabled(false)
            setRotateGesturesEnabled(false)
            setTiltGesturesEnabled(false)
            setCompassEnabled(false)
            // OpenFreeMap / OpenMapTiles / OSM attribution is required: keep it, top-left under the search bar.
            setAttributionEnabled(true)
            setAttributionGravity(Gravity.TOP or Gravity.START)
            setAttributionMargins(ctx.dp(8), insetTop + ctx.dp(32), 0, 0)
            setAttributionTintColor(ctx.color(M.attr.colorOnSurfaceVariant))
        }
        m.addOnMapClickListener { p -> onTap(m, p) }
        m.addOnMapLongClickListener { p -> listener.onLongPress(LatLng(p.latitude, p.longitude)); true }
        // Never leave the camera on (0,0) at world zoom.
        val c = defaultCenter()
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(
            if (c != null) MapLatLng(c.lat, c.lng) else MapLatLng(35.86, -86.35), if (c != null) 12.0 else 6.0))
        loadBasemap()
    }

    private fun loadBasemap() {
        val m = map ?: return
        basemap = Basemap.LOADING
        firstFrame = false
        updateChip()
        main.removeCallbacks(styleTimeout)
        main.postDelayed(styleTimeout, STYLE_TIMEOUT_MS)
        val url = styleUrl()
        Log.i(NetStatus.TAG, "map: loading $url (MapLibre connected=${MapLibre.isConnected()})")
        m.setStyle(Style.Builder().fromUri(url)) { s ->
            if (basemap != Basemap.LOADING) return@setStyle // fallback already took over
            main.removeCallbacks(styleTimeout)
            basemap = Basemap.OK
            onStyle(s)
            // A style from cache with no reachable tiles never finishes a frame: fall back so pins show.
            main.removeCallbacks(frameTimeout)
            main.postDelayed(frameTimeout, STYLE_TIMEOUT_MS)
        }
    }

    private val frameTimeout = Runnable {
        if (basemap == Basemap.OK && !firstFrame && NetStatus.systemSaysOffline(ctx)) onBasemapFailed("no network access")
        else updateChip()
    }

    private fun onBasemapFailed(why: String) {
        main.removeCallbacks(styleTimeout)
        if (basemap == Basemap.FALLBACK) return
        Log.w(NetStatus.TAG, "map: basemap failed ($why); using offline background")
        lastError = why
        basemap = Basemap.FALLBACK
        val m = map ?: return
        m.setStyle(Style.Builder().fromJson(fallbackStyle())) { s -> if (basemap == Basemap.FALLBACK) onStyle(s) }
        updateChip()
    }

    private fun onStyle(s: Style) {
        images.clear()
        addLayers(s)
        style = s
        notice.text = ""
        notice.background = null
        view?.bringToFront()
        updateChip()
        last?.let { draw(it) } ?: frameDefault()
    }

    private fun frameDefault() {
        val m = map ?: return
        val c = defaultCenter() ?: return
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(MapLatLng(c.lat, c.lng), 12.0))
    }

    private fun onTap(m: MapLibreMap, p: MapLatLng): Boolean {
        pickingFor?.let { id ->
            pickingFor = null
            listener.onPointPicked(id, LatLng(p.latitude, p.longitude))
            return true
        }
        if (style == null) return false
        val pt = m.projection.toScreenLocation(p)
        val r = ctx.dpf(20f)
        val hit = m.queryRenderedFeatures(RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r), LAYER_PINS)
            .firstOrNull { it.hasProperty("id") } ?: return false
        listener.onStopTapped(hit.getStringProperty("id"))
        return true
    }

    private fun addLayers(s: Style) {
        val primary = ctx.color(M.attr.colorPrimary)
        val surface = ctx.color(M.attr.colorSurface)
        s.addSource(GeoJsonSource(SRC_LINE, FeatureCollection.fromFeatures(emptyList())))
        s.addSource(GeoJsonSource(SRC_PINS, FeatureCollection.fromFeatures(emptyList())))
        s.addSource(GeoJsonSource(SRC_ACCESS, FeatureCollection.fromFeatures(emptyList())))
        s.addSource(GeoJsonSource(SRC_LABELS, FeatureCollection.fromFeatures(emptyList())))
        s.addLayer(LineLayer(LAYER_LINE, SRC_LINE).withProperties(
            P.lineColor(primary), P.lineWidth(3f), P.lineOpacity(0.8f), P.lineDasharray(arrayOf(2f, 1.5f)),
        ))
        s.addLayer(CircleLayer(LAYER_ACCESS, SRC_ACCESS).withProperties(
            P.circleRadius(7f), P.circleColor(ctx.color(M.attr.colorTertiary)),
            P.circleStrokeColor(surface), P.circleStrokeWidth(2f),
        ))
        // Pins are bitmaps (see pinImage): no glyphs or sprites needed, so they render offline too.
        s.addLayer(SymbolLayer(LAYER_PINS, SRC_PINS).withProperties(
            P.iconImage(Expression.get("icon")),
            P.iconAllowOverlap(true), P.iconIgnorePlacement(true),
            P.symbolSortKey(Expression.get("z")),
        ))
        // ETA / street labels to the right of pending pins. Bitmaps like the pins (no glyphs, so offline-safe);
        // hidden when zoomed out, where they would only pile up.
        s.addLayer(SymbolLayer(LAYER_LABELS, SRC_LABELS).withProperties(
            P.iconImage(Expression.get("icon")),
            P.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_LEFT),
            P.iconOffset(arrayOf(15f, 0f)),
            P.iconAllowOverlap(true), P.iconIgnorePlacement(true),
        ).also { it.minZoom = 12f })
    }

    /** Small rounded tag with [text] on the surface color; drawn next to a pin. */
    private fun labelImage(text: String, emphasised: Boolean): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = ctx.dpf(11f); isFakeBoldText = emphasised
            color = ctx.color(if (emphasised) M.attr.colorOnPrimaryContainer else M.attr.colorOnSurface)
        }
        val padX = ctx.dpf(6f); val padY = ctx.dpf(3f)
        val w = p.measureText(text) + padX * 2
        val h = p.fontMetrics.let { it.descent - it.ascent } + padY * 2
        val bmp = Bitmap.createBitmap(w.toInt() + 2, h.toInt() + 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.color(if (emphasised) M.attr.colorPrimaryContainer else M.attr.colorSurface) }
        c.drawRoundRect(RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), ctx.dpf(8f), ctx.dpf(8f), bg)
        c.drawText(text, padX + 1f, padY + 1f - p.fontMetrics.ascent, p)
        return bmp
    }

    /** Circle with the stop number/mark, in the same theme colors as the list badges. */
    private fun pinImage(text: String, fill: Int, ink: Int, big: Boolean): Bitmap {
        val d = ctx.dpf(if (big) 34f else 26f)
        val stroke = ctx.dpf(2f)
        val bmp = Bitmap.createBitmap(d.toInt() + 2, d.toInt() + 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = bmp.width / 2f
        p.color = ctx.color(M.attr.colorSurface)
        c.drawCircle(cx, cx, d / 2, p)
        p.color = fill
        c.drawCircle(cx, cx, d / 2 - stroke, p)
        p.color = ink
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        p.textSize = ctx.dpf(if (text.length > 2) 10f else if (big) 15f else 12f)
        c.drawText(text, cx, cx - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    /**
     * Redraws pins for [s] plus the route [start] (a rounded-square "S" pin);
     * [selectedId] shows that stop's drop-off pin (defaults to the current stop).
     */
    fun render(s: RouteSession?, selectedId: String? = null, start: RouteStart? = s?.start, end: LatLng? = null) {
        if (s == null && start == null) { last = null; clear(); return }
        last = Render(s, selectedId, start, end)
        if (style != null) draw(last!!)
    }

    /** Distinct start pin: rounded square in the secondary color. */
    private fun startImage() = letterImage("S")

    /** Rounded-square letter pin ("S" start, "E" end). */
    private fun letterImage(letter: String): Bitmap {
        val d = ctx.dpf(30f)
        val bmp = Bitmap.createBitmap(d.toInt() + 2, d.toInt() + 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = ctx.dpf(8f)
        p.color = ctx.color(M.attr.colorSurface)
        c.drawRoundRect(RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), r, r, p)
        p.color = ctx.color(M.attr.colorSecondary)
        val st = ctx.dpf(2f)
        c.drawRoundRect(RectF(st, st, bmp.width - st, bmp.height - st), r, r, p)
        p.color = ctx.color(M.attr.colorOnSecondary)
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        p.textSize = ctx.dpf(14f)
        c.drawText(letter, bmp.width / 2f, bmp.height / 2f - (p.descent() + p.ascent()) / 2, p)
        return bmp
    }

    private fun clear() {
        val st = style ?: return
        st.getSourceAs<GeoJsonSource>(SRC_PINS)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        st.getSourceAs<GeoJsonSource>(SRC_LINE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        st.getSourceAs<GeoJsonSource>(SRC_ACCESS)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        st.getSourceAs<GeoJsonSource>(SRC_LABELS)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    private fun draw(r: Render) {
        val st = style ?: return
        val s = r.s
        val stops = s?.stops.orEmpty()
        val cur = s?.current?.takeIf { s.started }
        val startPin = r.start?.let { start ->
            if (images.add(START_ICON)) st.addImage(START_ICON, startImage())
            Feature.fromGeometry(Point.fromLngLat(start.point.lng, start.point.lat)).apply {
                addStringProperty("id", START_ID)
                addStringProperty("icon", START_ICON)
                addNumberProperty("z", 4)
            }
        }
        val endPin = r.end?.let { e ->
            if (images.add(END_ICON)) st.addImage(END_ICON, letterImage("E"))
            Feature.fromGeometry(Point.fromLngLat(e.lng, e.lat)).apply {
                addStringProperty("id", END_ID)
                addStringProperty("icon", END_ICON)
                addNumberProperty("z", 4)
            }
        }
        val pins = stops.map { stop ->
            val isCur = stop.id == cur?.id
            val c = ctx.badgeColors(stop, isCur)
            val text = badgeText(stop)
            val key = "rp-pin-$text-${c.fill}-${c.text}-$isCur"
            if (images.add(key)) st.addImage(key, pinImage(text, c.fill, c.text, isCur))
            Feature.fromGeometry(Point.fromLngLat(stop.target.lng, stop.target.lat)).apply {
                addStringProperty("id", stop.id)
                addStringProperty("icon", key)
                // Draw order: finished below pending, current on top.
                addNumberProperty("z", when { isCur -> 3; stop.status == Status.PENDING -> 2; else -> 1 })
            }
        }
        st.getSourceAs<GeoJsonSource>(SRC_PINS)?.setGeoJson(FeatureCollection.fromFeatures(pins + listOfNotNull(startPin, endPin)))

        // Labels: arrival time for every pending stop; the current stop also gets its street.
        val labels = if (s == null) emptyList() else stops.filter { it.status == Status.PENDING && it.etaSec > 0 }.map { stop ->
            val isCur = stop.id == cur?.id
            val time = ctx.clock(s, stop.etaSec)
            val text = if (isCur) "$time · ${stop.address.substringBefore(',').take(22)}" else time
            val key = "rp-label-$text-$isCur"
            if (images.add(key)) st.addImage(key, labelImage(text, isCur))
            Feature.fromGeometry(Point.fromLngLat(stop.target.lng, stop.target.lat)).apply { addStringProperty("icon", key) }
        }
        st.getSourceAs<GeoJsonSource>(SRC_LABELS)?.setGeoJson(FeatureCollection.fromFeatures(labels))

        // Before any stop is done the line begins at the start (the depot).
        val underway = stops.any { it.status != Status.PENDING }
        val head = r.start?.takeIf { !underway }?.let { listOf(Point.fromLngLat(it.point.lng, it.point.lat)) }.orEmpty()
        val remaining = head + stops.filter { it.status == Status.PENDING }.map { Point.fromLngLat(it.target.lng, it.target.lat) } +
            listOfNotNull(r.end?.let { Point.fromLngLat(it.lng, it.lat) })
        st.getSourceAs<GeoJsonSource>(SRC_LINE)?.setGeoJson(
            if (remaining.size < 2) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(remaining)))
        )

        val sel = (r.selectedId?.let { s?.stop(it) } ?: cur)?.takeIf { it.access != it.target || r.selectedId != null }
        st.getSourceAs<GeoJsonSource>(SRC_ACCESS)?.setGeoJson(
            sel?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.access.lng, it.access.lat))) }
                ?: FeatureCollection.fromFeatures(emptyList())
        )

        // Camera: frame the whole route once per route; then follow the current stop while driving.
        val routeKey = Triple(stops.firstOrNull()?.id, stops.size, r.start?.point)
        if (framedRoute != routeKey) { framedRoute = routeKey; focusedId = cur?.id; frameAll() }
        else if (cur != null && cur.id != focusedId) { focusedId = cur.id; focus(cur.target) }
    }

    /** Fits every stop (and the start) in the visible part of the map. */
    fun frameAll() {
        val m = map ?: return
        val r = last
        val pts = r?.s?.stops?.map { MapLatLng(it.target.lat, it.target.lng) }.orEmpty() +
            listOfNotNull(r?.start?.point?.let { MapLatLng(it.lat, it.lng) }, r?.end?.let { MapLatLng(it.lat, it.lng) })
        if (pts.isEmpty()) { frameDefault(); return }
        val distinct = pts.distinct()
        if (distinct.size == 1) { m.easeCamera(CameraUpdateFactory.newLatLngZoom(distinct[0], 15.0)); return }
        val pad = ctx.dp(48)
        val h = container.height
        if (h <= 0) { container.post { frameAll() }; return }
        // Keep the padding sane on small views, or MapLibre can't fit the bounds.
        val top = (insetTop + pad).coerceAtMost(h / 3)
        val bottom = (insetBottom + pad).coerceAtMost(h / 2)
        runCatching {
            m.easeCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(distinct).build(), pad, top, pad, bottom))
        }.onFailure { Log.w(NetStatus.TAG, "map: frameAll failed: $it") }
    }

    fun focus(p: LatLng) {
        val m = map ?: return
        val zoom = maxOf(m.cameraPosition.zoom, 14.0)
        m.easeCamera(CameraUpdateFactory.newLatLngZoom(MapLatLng(p.lat, p.lng), zoom))
    }

    fun updateInsets(top: Int, bottom: Int) {
        insetTop = top; insetBottom = bottom
        map?.uiSettings?.setAttributionMargins(ctx.dp(8), top + ctx.dp(32), 0, 0)
    }

    // Lifecycle forwarding (MapView requires every call).
    fun onStart() = view?.onStart()
    fun onResume() = view?.onResume()
    fun onPause() = view?.onPause()
    fun onStop() = view?.onStop()
    fun onLowMemory() = view?.onLowMemory()
    fun onSaveInstanceState(out: Bundle) = view?.onSaveInstanceState(out)
    fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        view?.onDestroy()
        view = null; map = null; style = null
    }

    companion object {
        /** Feature id of the start pin (tap → edit start). */
        const val START_ID = "__start__"
        const val END_ID = "__end__"
        private const val STYLE_TIMEOUT_MS = 10_000L
        private const val START_ICON = "rp-start"
        private const val END_ICON = "rp-end"
        private const val SRC_PINS = "rp-stops"
        private const val SRC_LINE = "rp-line"
        private const val SRC_ACCESS = "rp-access"
        private const val SRC_LABELS = "rp-labels"
        private const val LAYER_LABELS = "rp-labels-layer"
        private const val LAYER_PINS = "rp-stops-layer"
        private const val LAYER_LINE = "rp-line-layer"
        private const val LAYER_ACCESS = "rp-access-layer"
    }
}

/** Placeholder used when the map is turned off in Settings. */
fun mapOffNotice(ctx: Context) = TextView(ctx).apply {
    text = "Map is off (Settings)."
    gravity = Gravity.CENTER
    setTextColor(ctx.color(M.attr.colorOnSurfaceVariant))
}
