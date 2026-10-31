package app.routeplanner

import android.content.Context
import app.routeplanner.core.*
import com.valhalla.valhalla.Valhalla
import com.valhalla.config.ValhallaConfigBuilder
import java.io.File

/**
 * Chooses where road travel times come from, best first:
 * 1. on-device Valhalla over an imported tile extract (works offline),
 * 2. a Valhalla server URL from settings,
 * 3. straight-line estimates.
 */
object Routing {
    fun tilesFile(ctx: Context) = File(ctx.filesDir, "valhalla_tiles.tar")

    private var engine: Valhalla? = null
    private var engineStamp = 0L

    @Synchronized
    private fun engine(ctx: Context): Valhalla? {
        val f = tilesFile(ctx)
        if (!f.exists()) { engine?.close(); engine = null; return null }
        val stamp = f.lastModified() xor f.length()
        if (engine == null || stamp != engineStamp) {
            engine?.close()
            val config = ValhallaConfigBuilder().withTileExtract(f.absolutePath).build()
            engine = Valhalla(ctx.applicationContext, config)
            engineStamp = stamp
        }
        return engine
    }

    class Source(val provider: MatrixProvider, val label: String)

    fun source(ctx: Context, prefs: Prefs): Source {
        val e = runCatching { engine(ctx) }.getOrNull()
        if (e != null) {
            // The raw JSON API lets the tested ValhallaMatrix parser serve both server and device.
            return Source(ValhallaMatrix("on-device", Http { _, body -> e.matrixRaw(body) }), "on-device roads")
        }
        if (prefs.valhallaUrl.isNotEmpty()) return Source(ValhallaMatrix(prefs.valhallaUrl), "server roads")
        return Source(HaversineMatrix(), "straight-line estimates")
    }
}
