package app.routeplanner.core

/** Which proof files (photos, signatures) to delete when the driver cleans up storage. */
object Pruning {
    class FileInfo(val path: String, val lastModifiedMs: Long, val bytes: Long)

    /**
     * The files to delete: those older than [olderThanDays] (all of them if null), never any in [keep] (the files the open
     * route still shows). Newest-first order is not needed; the result keeps the input order.
     */
    fun selectProof(files: List<FileInfo>, nowMs: Long, olderThanDays: Int?, keep: Set<String>): List<FileInfo> {
        val cutoff = olderThanDays?.let { nowMs - it * 86_400_000L }
        return files.filter { it.path !in keep && (cutoff == null || it.lastModifiedMs < cutoff) }
    }

    /** "12.3 MB", "850 KB", "0 B". */
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(java.util.Locale.ROOT, bytes / 1e9)
        bytes >= 1_000_000L -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1e6)
        bytes >= 1_000L -> "${bytes / 1000} KB"
        else -> "$bytes B"
    }
}
