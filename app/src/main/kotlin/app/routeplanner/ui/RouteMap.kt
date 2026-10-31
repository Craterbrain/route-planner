package app.routeplanner.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import app.routeplanner.color
import app.routeplanner.core.LatLng
import app.routeplanner.core.RouteSession
import app.routeplanner.core.Status
import com.google.android.material.R as M
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory as P
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng as MapLatLng

/**
 * The route map: numbered stop pins (current stop highlighted, finished stops
 * dimmed, failed in the error color), a dashed line through the remaining
 * stops in visiting order, and the selected stop's drop-off pin.
 *
 * Tiles come from OpenFreeMap (OSM data, no key). If MapLibre can't start or
 * tiles can't load, a notice is shown and the rest of the app is unaffected.
 */
class RouteMap(
    private val ctx: Context,
    private val container: FrameLayout,
    savedState: Bundle?,
    private val listener: Listener,
) {
    interface Listener {
        fun onStopTapped(id: String)
        /** Tap while [pickingFor] is set: the driver chose a drop-off point. */
        fun onPointPicked(stopId: String, p: LatLng)
        fun onLongPress(p: LatLng)
    }

    private var view: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private val notice = ctx.label(13f, M.attr.colorOnSurfaceVariant).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dp(24), ctx.dp(24), ctx.dp(24), ctx.dp(24))
    }

    /** Stop id whose drop-off pin the next map tap sets, or null. */
    var pickingFor: String? = null

    /** Space covered by the search bar (top) and the collapsed sheet (bottom), in px. */
    var insetTop = 0
    var insetBottom = 0

    private var last: Render? = null
    private var framedRoute: Any? = null
    private var focusedId: String? = null

    private class Render(val s: RouteSession, val selectedId: String?)

    val isReady: Boolean get() = style != null

    init {
        container.addView(notice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        notice.text = "Loading map…"
        try {
            MapLibre.getInstance(ctx.applicationContext)
            val mv = MapView(ctx)
            mv.onCreate(savedState)
            container.addView(mv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            mv.addOnDidFailLoadingMapListener { showNotice("Map couldn't load (offline?). The stop list still works.") }
            mv.getMapAsync { m -> setUp(m) }
            view = mv
        } catch (t: Throwable) {
            // Native library or GL problems: keep the app usable without a map.
            view = null
            showNotice("Map unavailable on this device. The stop list still works.")
        }
    }

    private fun showNotice(text: String) {
        notice.text = text
        notice.bringToFront()
        notice.background = ctx.roundRect(M.attr.colorSurfaceContainerHigh, 0)
    }

    private fun styleUrl(): String {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) "https://tiles.openfreemap.org/styles/dark" else "https://tiles.openfreemap.org/styles/positron"
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
            setAttributionMargins(ctx.dp(8), insetTop + ctx.dp(4), 0, 0)
            setAttributionTintColor(ctx.color(M.attr.colorOnSurfaceVariant))
        }
        m.addOnMapClickListener { p -> onTap(m, p) }
        m.addOnMapLongClickListener { p -> listener.onLongPress(LatLng(p.latitude, p.longitude)); true }
        m.setStyle(Style.Builder().fromUri(styleUrl())) { s ->
            addLayers(s)
            style = s
            notice.text = ""
            notice.background = null
            view?.bringToFront()
            last?.let { draw(it) }
        }
    }

    private fun onTap(m: MapLibreMap, p: MapLatLng): Boolean {
        pickingFor?.let { id ->
            pickingFor = null
            listener.onPointPicked(id, LatLng(p.latitude, p.longitude))
            return true
        }
        val pt = m.projection.toScreenLocation(p)
        val r = ctx.dpf(18f)
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
        s.addLayer(LineLayer(LAYER_LINE, SRC_LINE).withProperties(
            P.lineColor(primary), P.lineWidth(3f), P.lineOpacity(0.7f), P.lineDasharray(arrayOf(2f, 1.5f)),
        ))
        s.addLayer(CircleLayer(LAYER_ACCESS, SRC_ACCESS).withProperties(
            P.circleRadius(7f), P.circleColor(ctx.color(M.attr.colorTertiary)),
            P.circleStrokeColor(surface), P.circleStrokeWidth(2f),
        ))
        // Colors are per-feature strings computed from the theme, so pins match the list badges.
        s.addLayer(CircleLayer(LAYER_PINS, SRC_PINS).withProperties(
            P.circleRadius(Expression.get("r")),
            P.circleColor(Expression.toColor(Expression.get("fill"))),
            P.circleStrokeColor(surface), P.circleStrokeWidth(2f),
            P.circleSortKey(Expression.get("z")),
        ))
        s.addLayer(SymbolLayer(LAYER_LABELS, SRC_PINS).withProperties(
            P.textField(Expression.get("n")),
            P.textFont(arrayOf("Noto Sans Bold")),
            P.textSize(12f),
            P.textColor(Expression.toColor(Expression.get("ink"))),
            P.textAllowOverlap(true), P.textIgnorePlacement(true),
            P.symbolSortKey(Expression.get("z")),
        ))
    }

    /** Redraws pins for [s]; [selectedId] shows that stop's drop-off pin (defaults to the current stop). */
    fun render(s: RouteSession?, selectedId: String? = null) {
        if (s == null) { last = null; clear(); return }
        last = Render(s, selectedId)
        if (style != null) draw(last!!)
    }

    private fun clear() {
        val st = style ?: return
        st.getSourceAs<GeoJsonSource>(SRC_PINS)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        st.getSourceAs<GeoJsonSource>(SRC_LINE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        st.getSourceAs<GeoJsonSource>(SRC_ACCESS)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    private fun draw(r: Render) {
        val st = style ?: return
        val s = r.s
        val cur = s.current?.takeIf { s.started }
        val pins = s.stops.map { stop ->
            val isCur = stop.id == cur?.id
            val c = ctx.badgeColors(stop, isCur)
            Feature.fromGeometry(Point.fromLngLat(stop.target.lng, stop.target.lat)).apply {
                addStringProperty("id", stop.id)
                addStringProperty("n", badgeText(stop))
                addStringProperty("fill", hex(c.fill))
                addStringProperty("ink", hex(c.text))
                addNumberProperty("r", if (isCur) 15 else 11)
                // Draw order: finished below pending, current on top.
                addNumberProperty("z", when { isCur -> 3; stop.status == Status.PENDING -> 2; else -> 1 })
            }
        }
        st.getSourceAs<GeoJsonSource>(SRC_PINS)?.setGeoJson(FeatureCollection.fromFeatures(pins))

        val remaining = s.stops.filter { it.status == Status.PENDING }.map { Point.fromLngLat(it.target.lng, it.target.lat) }
        st.getSourceAs<GeoJsonSource>(SRC_LINE)?.setGeoJson(
            if (remaining.size < 2) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(remaining)))
        )

        val sel = (r.selectedId?.let { s.stop(it) } ?: cur)?.takeIf { it.access != it.target || r.selectedId != null }
        st.getSourceAs<GeoJsonSource>(SRC_ACCESS)?.setGeoJson(
            sel?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.access.lng, it.access.lat))) }
                ?: FeatureCollection.fromFeatures(emptyList())
        )

        // Camera: frame the whole route once per route; then follow the current stop while driving.
        val routeKey = s.stops.firstOrNull()?.id to s.stops.size
        if (framedRoute != routeKey) { framedRoute = routeKey; focusedId = cur?.id; frameAll(s) }
        else if (cur != null && cur.id != focusedId) { focusedId = cur.id; focus(cur.target) }
    }

    /** Fits every stop in the visible part of the map. */
    fun frameAll(s: RouteSession? = last?.s) {
        val m = map ?: return
        val pts = s?.stops?.map { MapLatLng(it.target.lat, it.target.lng) }.orEmpty()
        if (pts.isEmpty()) return
        val distinct = pts.distinct()
        if (distinct.size == 1) { m.easeCamera(CameraUpdateFactory.newLatLngZoom(distinct[0], 15.0)); return }
        val pad = ctx.dp(48)
        val h = container.height
        // Keep the padding sane on tiny/unmeasured views, or MapLibre can't fit the bounds.
        val top = (insetTop + pad).coerceAtMost(h / 3)
        val bottom = (insetBottom + pad).coerceAtMost(h / 2)
        runCatching {
            m.easeCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(distinct).build(), pad, top, pad, bottom))
        }
    }

    fun focus(p: LatLng) {
        val m = map ?: return
        val zoom = maxOf(m.cameraPosition.zoom, 14.0)
        m.easeCamera(CameraUpdateFactory.newLatLngZoom(MapLatLng(p.lat, p.lng), zoom))
    }

    fun updateInsets(top: Int, bottom: Int) {
        insetTop = top; insetBottom = bottom
        map?.uiSettings?.setAttributionMargins(ctx.dp(8), top + ctx.dp(4), 0, 0)
    }

    // Lifecycle forwarding (MapView requires every call).
    fun onStart() = view?.onStart()
    fun onResume() = view?.onResume()
    fun onPause() = view?.onPause()
    fun onStop() = view?.onStop()
    fun onLowMemory() = view?.onLowMemory()
    fun onSaveInstanceState(out: Bundle) = view?.onSaveInstanceState(out)
    fun onDestroy() {
        view?.onDestroy()
        view = null; map = null; style = null
    }

    private companion object {
        const val SRC_PINS = "rp-stops"
        const val SRC_LINE = "rp-line"
        const val SRC_ACCESS = "rp-access"
        const val LAYER_PINS = "rp-stops-circle"
        const val LAYER_LABELS = "rp-stops-label"
        const val LAYER_LINE = "rp-line-layer"
        const val LAYER_ACCESS = "rp-access-layer"
    }
}

/** Placeholder used when the map is turned off in Settings. */
fun mapOffNotice(ctx: Context) = TextView(ctx).apply {
    text = "Map is off (Settings)."
    gravity = Gravity.CENTER
    setTextColor(ctx.color(M.attr.colorOnSurfaceVariant))
}
