package app.routeplanner

import android.content.Context
import app.routeplanner.core.Bounds
import app.routeplanner.core.TileMath
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Explicit offline map download with MapLibre's OfflineManager. OpenFreeMap allows at most 3,000 tiles
 * (or 50 MB) per download, so the zoom range is chosen to stay under that. Both the light and the dark
 * style are saved (the style JSON, sprites and tiles are cached per style URL), so the map still loads
 * offline whichever way the system theme is set. Callbacks arrive on the main thread.
 */
object OfflineMaps {
    const val TILE_LIMIT = 3000L
    const val MIN_ZOOM = 8
    const val MAX_ZOOM = 14 // OpenMapTiles vector tiles end at 14; deeper just over-zooms.
    private const val TAG = NetStatus.TAG

    class Plan(val bounds: Bounds, val minZoom: Int, val maxZoom: Int, val tiles: Long) {
        val widthKm get() = TileMath.widthKm(bounds)
        val heightKm get() = TileMath.heightKm(bounds)
    }

    /** What to download for [area] (already padded): the deepest zoom that stays under the tile limit. */
    fun plan(area: Bounds): Plan {
        val z = TileMath.deepestZoom(area, MIN_ZOOM, MAX_ZOOM, TILE_LIMIT)
        return Plan(area, MIN_ZOOM, z, TileMath.tileCount(area, MIN_ZOOM, z))
    }

    class Saved(val name: String, val regions: List<OfflineRegion>, val bytes: Long, val complete: Boolean)

    /** Saved maps grouped by name, with their size on disk. */
    fun list(ctx: Context, onResult: (List<Saved>) -> Unit) {
        OfflineManager.getInstance(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions?.toList().orEmpty()
                if (regions.isEmpty()) { onResult(emptyList()); return }
                val status = HashMap<Long, OfflineRegionStatus?>()
                fun finish() {
                    val groups = regions.groupBy { nameOf(it) }.map { (name, rs) ->
                        Saved(name, rs, rs.sumOf { status[it.id]?.completedResourceSize ?: 0L }, rs.all { status[it.id]?.isComplete == true })
                    }
                    onResult(groups)
                }
                for (r in regions) r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status0: OfflineRegionStatus?) { status[r.id] = status0; if (status.size == regions.size) finish() }
                    override fun onError(error: String?) { status[r.id] = null; if (status.size == regions.size) finish() }
                })
            }
            override fun onError(error: String) { AppLog.w("offline: list failed: $error"); onResult(emptyList()) }
        })
    }

    private fun nameOf(r: OfflineRegion): String =
        runCatching { JSONObject(String(r.metadata)).optString("name") }.getOrNull()?.ifEmpty { null } ?: "Offline map ${r.id}"

    class Progress(val percent: Int, val text: String)

    /**
     * Downloads [plan] for [name], light style first, then dark. [onProgress] reports 0..100 overall;
     * [onDone] gets success or the first error. Returns a handle whose [Job.cancel] stops and deletes the partial download.
     */
    fun download(ctx: Context, name: String, plan: Plan, onProgress: (Progress) -> Unit, onDone: (Result<Unit>) -> Unit): Job {
        val job = Job()
        val styles = listOf("light" to NetStatus.MAP_STYLE_LIGHT, "dark" to NetStatus.MAP_STYLE_DARK)
        fun runStyle(i: Int) {
            if (job.cancelled) return
            if (i == styles.size) { onDone(Result.success(Unit)); return }
            val (label, url) = styles[i]
            val b = plan.bounds
            val def = OfflineTilePyramidRegionDefinition(
                url, LatLngBounds.from(b.north, b.east, b.south, b.west),
                plan.minZoom.toDouble(), plan.maxZoom.toDouble(), ctx.resources.displayMetrics.density,
            )
            val meta = JSONObject().put("name", name).put("style", label).put("tiles", plan.tiles)
                .put("minZoom", plan.minZoom).put("maxZoom", plan.maxZoom).toString().toByteArray()
            OfflineManager.getInstance(ctx).createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    job.region = offlineRegion
                    if (job.cancelled) { offlineRegion.delete(noopDelete); return }
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val req = status.requiredResourceCount.coerceAtLeast(1)
                            val pct = (100.0 * status.completedResourceCount / req).toInt().coerceIn(0, 100)
                            onProgress(Progress((i * 100 + pct) / styles.size,
                                "$label map: ${status.completedResourceCount} of $req files · ${status.completedResourceSize / 1024} KB"))
                            if (status.isComplete) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                                    override fun onStatusChanged(status: OfflineRegionStatus) {}
                                    override fun onError(error: OfflineRegionError) {}
                                    override fun mapboxTileCountLimitExceeded(limit: Long) {}
                                })
                                job.region = null
                                runStyle(i + 1)
                            }
                        }
                        override fun onError(error: OfflineRegionError) {
                            // Individual tile errors are retried by the engine; report and stop on a hard failure.
                            AppLog.w("offline: ${error.reason}: ${error.message}")
                            if (error.reason == OfflineRegionError.REASON_CONNECTION && !job.cancelled) {
                                job.cancel()
                                onDone(Result.failure(IllegalStateException("No network connection. Try again when you're online.")))
                            }
                        }
                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            job.cancel()
                            onDone(Result.failure(IllegalStateException("Too many tiles ($limit). Try a smaller area.")))
                        }
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }
                override fun onError(error: String) { onDone(Result.failure(IllegalStateException(error))) }
            })
        }
        runStyle(0)
        return job
    }

    class Job {
        @Volatile var cancelled = false
        var region: OfflineRegion? = null
        /** Stops the download and removes whatever was saved so far. */
        fun cancel() {
            cancelled = true
            region?.let { it.setDownloadState(OfflineRegion.STATE_INACTIVE); it.delete(noopDelete) }
            region = null
        }
    }

    private val noopDelete = object : OfflineRegion.OfflineRegionDeleteCallback {
        override fun onDelete() {}
        override fun onError(error: String) { AppLog.w("offline: delete failed: $error") }
    }

    fun delete(saved: Saved, onDone: () -> Unit) {
        var left = saved.regions.size
        if (left == 0) { onDone(); return }
        for (r in saved.regions) r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { if (--left == 0) onDone() }
            override fun onError(error: String) { AppLog.w("offline: delete failed: $error"); if (--left == 0) onDone() }
        })
    }
}
