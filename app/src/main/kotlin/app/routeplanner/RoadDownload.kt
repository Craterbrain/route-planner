package app.routeplanner

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.GZIPInputStream

/**
 * Downloads a state's road tiles from the route-planner-roads GitHub release (see that repo's manifest.json):
 * the gzipped tar in parts of at most 1.9 GB, resumable, each part and the final tar checked against SHA-256.
 * The result is the same valhalla_tiles.tar a manual import produces.
 */
object RoadDownload {
    const val BASE = "https://github.com/Craterbrain/route-planner-roads/releases/download/roads/"
    const val ADDR_BASE = "https://github.com/Craterbrain/route-planner-roads/releases/download/addresses/"
    private const val UA = "RoutePlanner (+https://github.com/Craterbrain/route-planner-roads)"

    class Part(val name: String, val bytes: Long, val sha256: String)
    class Pack(val state: String, val built: String, val tarBytes: Long, val tarSha256: String, val parts: List<Part>) {
        val downloadBytes get() = parts.sumOf { it.bytes }
        val title get() = state.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }

    /** An offline address pack for one state (the "addresses" release); optional next to the road data. */
    class AddrPack(val state: String, val release: String, val file: String, val gzBytes: Long, val gzSha256: String, val sqliteBytes: Long, val sqliteSha256: String)

    class Cancelled : Exception("Cancelled")

    private fun open(url: String, from: Long = 0): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", UA)
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    /** Every state published so far, smallest name first. */
    fun manifest(): List<Pack> {
        val c = open(BASE + "manifest.json")
        try {
            check(c.responseCode == 200) { "Road data list not available (HTTP ${c.responseCode})" }
            val states = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("states")
            return states.keys().asSequence().sorted().map { k ->
                val s = states.getJSONObject(k)
                val ps = s.getJSONArray("parts")
                Pack(k, s.optString("built"), s.getLong("tar_bytes"), s.getString("tar_sha256"),
                    (0 until ps.length()).map { ps.getJSONObject(it).let { p -> Part(p.getString("name"), p.getLong("bytes"), p.getString("sha256")) } })
            }.toList()
        } finally { c.disconnect() }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "roads-download")

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Address packs published so far, by state name; empty when the list can't be read (they are optional). */
    fun addressManifest(): Map<String, AddrPack> = runCatching {
        val c = open(ADDR_BASE + "manifest.json")
        try {
            check(c.responseCode == 200)
            val states = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("states")
            states.keys().asSequence().associateWith { k ->
                val s = states.getJSONObject(k)
                AddrPack(k, s.optString("release"), s.getString("file"), s.getLong("gz_bytes"), s.getString("gz_sha256"), s.getLong("sqlite_bytes"), s.getString("sqlite_sha256"))
            }
        } finally { c.disconnect() }
    }.getOrDefault(emptyMap())

    /** Downloads [f] from [url], resuming a partial file, and checks its size and SHA-256. [onBytes] gets the bytes of this file so far. */
    private fun fetchFile(f: File, url: String, bytes: Long, sha: String, cancelled: () -> Boolean, onBytes: (Long) -> Unit) {
        if (f.length() > bytes) f.delete()
        if (f.length() == bytes && sha256(f) == sha) { onBytes(bytes); return }
        val have = f.length()
        val c = open(url, have)
        try {
            val resumed = c.responseCode == 206
            check(resumed || c.responseCode == 200) { "Download failed (HTTP ${c.responseCode})" }
            if (!resumed) f.delete()
            var got = if (resumed) have else 0L
            c.inputStream.use { i ->
                java.io.FileOutputStream(f, resumed).use { o ->
                    val b = ByteArray(1 shl 16)
                    var last = 0L
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = i.read(b); if (n < 0) break
                        o.write(b, 0, n); got += n
                        val now = System.nanoTime()
                        if (now - last > 200_000_000L) { last = now; onBytes(got) }
                    }
                }
            }
        } finally { c.disconnect() }
        check(f.length() == bytes) { "Download was cut short; try again to resume." }
        if (sha256(f) != sha) { f.delete(); error("A downloaded file failed its checksum; try again.") }
        onBytes(bytes)
    }

    /**
     * Fetches, verifies and installs [pack] (and [addr], when there is one). [progress] gets (bytes done, bytes total) over
     * everything; [cancelled] is polled and a [Cancelled] is thrown, keeping the partial files so a retry resumes.
     */
    fun install(ctx: Context, pack: Pack, addr: AddrPack?, cancelled: () -> Boolean, progress: (Long, Long) -> Unit, phase: (String) -> Unit) {
        val d = dir(ctx).apply { mkdirs() }
        // Room for the downloads plus the unpacked files (the downloads are removed once verified).
        val need = pack.downloadBytes + pack.tarBytes + (addr?.let { it.gzBytes + it.sqliteBytes } ?: 0L)
        check(ctx.filesDir.usableSpace > need) { "Not enough free space: needs about ${need / 1_000_000} MB." }
        val total = pack.downloadBytes + (addr?.gzBytes ?: 0L)
        var done = 0L
        phase("Downloading road data")
        for (p in pack.parts) {
            fetchFile(File(d, p.name), BASE + p.name, p.bytes, p.sha256, cancelled) { progress(done + it, total) }
            done += p.bytes
        }
        if (addr != null) {
            phase("Downloading address data")
            fetchFile(File(d, addr.file), ADDR_BASE + addr.file, addr.gzBytes, addr.gzSha256, cancelled) { progress(done + it, total) }
        }
        phase("Unpacking road data")
        val tmp = File(ctx.filesDir, "valhalla_tiles.tar.part")
        val streams: List<InputStream> = pack.parts.map { File(d, it.name).inputStream() }
        gunzipChecked(SequenceInputStream(Collections.enumeration(streams)), tmp, pack.tarBytes, pack.tarSha256, cancelled)
        val dest = Routing.tilesFile(ctx)
        check(tmp.renameTo(dest) || (dest.delete() && tmp.renameTo(dest))) { "Couldn't move the file into place" }
        if (addr != null) {
            phase("Unpacking address data")
            val part = File(ctx.filesDir, "addresses.sqlite.part")
            gunzipChecked(File(d, addr.file).inputStream(), part, addr.sqliteBytes, addr.sqliteSha256, cancelled)
            AddressPack.install(ctx, part)
        }
        d.deleteRecursively()
        recordInstalled(ctx, pack, addr)
    }

    /** What the installed data was built from, so a newer build can be told apart (manual imports leave no record). */
    private fun recordFile(ctx: Context) = File(ctx.filesDir, "offline-data.json")

    private fun recordInstalled(ctx: Context, pack: Pack, addr: AddrPack?) {
        val j = JSONObject().put("state", pack.state).put("roads_sha256", pack.tarSha256)
        if (addr != null) j.put("addr_sha256", addr.sqliteSha256)
        recordFile(ctx).writeText(j.toString())
    }

    /** Name of the state with a newer published build than what is installed, or null. Needs the network. */
    fun updateAvailable(ctx: Context): String? {
        val rec = runCatching { JSONObject(recordFile(ctx).readText()) }.getOrNull() ?: return null
        if (!Routing.tilesFile(ctx).exists()) return null
        val state = rec.getString("state")
        val roads = manifest().firstOrNull { it.state == state }
        val addr = addressManifest()[state]
        val newer = (roads != null && roads.tarSha256 != rec.optString("roads_sha256")) ||
            (addr != null && rec.has("addr_sha256") && addr.sqliteSha256 != rec.getString("addr_sha256"))
        return if (newer) state else null
    }

    /** gunzips [src] into [out], checking the result's size and SHA-256; deletes [out] and throws on failure or cancel. */
    private fun gunzipChecked(src: InputStream, out: File, bytes: Long, sha: String, cancelled: () -> Boolean) {
        val md = MessageDigest.getInstance("SHA-256")
        try {
            GZIPInputStream(src, 1 shl 16).use { gz ->
                out.outputStream().use { o ->
                    val b = ByteArray(1 shl 16)
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = gz.read(b); if (n < 0) break
                        md.update(b, 0, n); o.write(b, 0, n)
                    }
                }
            }
            check(out.length() == bytes && md.digest().joinToString("") { "%02x".format(it) } == sha) { "The unpacked data failed its check; download it again." }
        } catch (e: Exception) { out.delete(); throw e }
    }
}
