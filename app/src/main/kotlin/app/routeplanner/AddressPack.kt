package app.routeplanner

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import app.routeplanner.core.address.AddrPoint
import app.routeplanner.core.address.AddressStore
import app.routeplanner.core.address.OfflineAddressSearch
import app.routeplanner.core.address.StreetRow
import java.io.File

/** The offline address pack (docs/address-pack.md, format 1) over read-only SQLite. */
class SqliteAddressStore(private val db: SQLiteDatabase) : AddressStore {
    private fun street(c: android.database.Cursor) = StreetRow(
        c.getLong(0), c.getString(1), c.getString(2),
        if (c.isNull(3)) null else c.getString(3), if (c.isNull(4)) null else c.getString(4), if (c.isNull(5)) null else c.getString(5),
        c.getInt(6), c.getInt(7),
    )

    override fun streetsInRange(from: String, to: String, postcode: String?, limit: Int): List<StreetRow> {
        val sql = "SELECT id, name, norm, city, pcity, postcode, lat, lon FROM streets WHERE norm >= ? AND norm < ?" +
            (if (postcode != null) " AND postcode = ?" else "") + " LIMIT $limit"
        val args = if (postcode != null) arrayOf(from, to, postcode) else arrayOf(from, to)
        return db.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(street(c)) } }
    }

    private fun points(sql: String, args: Array<String>): List<AddrPoint> =
        db.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(AddrPoint(c.getInt(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4))) } }

    override fun exact(streetId: Long, num: Int, number: String) =
        points("SELECT num, number, unit, lat, lon FROM addr WHERE street_id=? AND num=? AND number=?", arrayOf(streetId.toString(), num.toString(), number))

    override fun below(streetId: Long, num: Int, parity: Int?) = points(
        "SELECT num, number, unit, lat, lon FROM addr WHERE street_id=? AND num>=0 AND num<?" +
            (if (parity != null) " AND num % 2 = ?" else "") + " ORDER BY num DESC LIMIT 1",
        listOfNotNull(streetId.toString(), num.toString(), parity?.toString()).toTypedArray(),
    ).firstOrNull()

    override fun above(streetId: Long, num: Int, parity: Int?) = points(
        "SELECT num, number, unit, lat, lon FROM addr WHERE street_id=? AND num>?" +
            (if (parity != null) " AND num % 2 = ?" else "") + " ORDER BY num ASC LIMIT 1",
        listOfNotNull(streetId.toString(), num.toString(), parity?.toString()).toTypedArray(),
    ).firstOrNull()
}

/** Import, validation and a cached read-only connection for filesDir/addresses.sqlite. */
object AddressPack {
    const val FORMAT = 1

    fun file(ctx: Context) = File(ctx.filesDir, "addresses.sqlite")

    private var db: SQLiteDatabase? = null
    private var stamp = 0L
    private var cachedMeta: Map<String, String> = emptyMap()

    private fun open(f: File) = SQLiteDatabase.openDatabase(f.path, null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    /** Reads PRAGMA user_version and the meta table; throws when the file is not a format-1 pack. */
    private fun validate(d: SQLiteDatabase): Map<String, String> {
        val version = d.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
        check(version == FORMAT) { "Not an address pack (format $version, expected $FORMAT)" }
        val meta = d.rawQuery("SELECT key, value FROM meta", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1) ?: "") }
        }
        check(meta["format"] == FORMAT.toString()) { "Unsupported pack format ${meta["format"]}" }
        return meta
    }

    @Synchronized private fun connection(ctx: Context): SQLiteDatabase? {
        val f = file(ctx)
        if (!f.exists()) { db?.close(); db = null; cachedMeta = emptyMap(); return null }
        val s = f.lastModified() xor f.length()
        if (db == null || s != stamp) {
            db?.close()
            db = runCatching { open(f).also { cachedMeta = validate(it) } }
                .onFailure { AppLog.w("address pack unusable: $it") }.getOrNull()
            stamp = s
        }
        return db
    }

    /** Search over the installed pack, or null when there is none. */
    fun search(ctx: Context): OfflineAddressSearch? = connection(ctx)?.let { OfflineAddressSearch(SqliteAddressStore(it)) }

    fun meta(ctx: Context): Map<String, String> { connection(ctx); return cachedMeta }

    /** Streams the picked file to addresses.sqlite.part, validates it, then renames it into place. Blocking. */
    fun import(ctx: Context, uri: Uri): Map<String, String> {
        val part = File(ctx.filesDir, "addresses.sqlite.part")
        (ctx.contentResolver.openInputStream(uri) ?: error("Couldn't read the file")).use { i -> part.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        return install(ctx, part)
    }

    /** Validates a finished addresses.sqlite.part and moves it into place. Blocking. */
    fun install(ctx: Context, part: File): Map<String, String> {
        val meta = try { open(part).use { validate(it) } } catch (e: Exception) { part.delete(); throw e }
        synchronized(this) {
            db?.close(); db = null
            val dest = file(ctx)
            check(part.renameTo(dest) || (dest.delete() && part.renameTo(dest))) { "Couldn't move the pack into place" }
        }
        return meta
    }

    /** "Tennessee · 3,890,861 addresses · built 2026-10-31 · 125 MB" for Settings. */
    fun summary(ctx: Context): String? {
        val m = meta(ctx).takeIf { it.isNotEmpty() } ?: return null
        val mb = file(ctx).length() / 1_000_000
        val n = m["addresses"]?.toLongOrNull()?.let { "%,d addresses".format(it) }
        return listOfNotNull(m["state"], n, m["built_at"]?.take(10)?.let { "built $it" }, "release ${m["release"] ?: "?"}", "$mb MB").joinToString(" · ")
    }
}
