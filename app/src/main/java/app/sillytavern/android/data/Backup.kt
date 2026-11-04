package app.sillytavern.android.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class BackupException(message: String) : Exception(message)

@Serializable
data class BackupHeader(
    val format: Int = 1,
    val createdAt: Long = 0,
    val user: String = "",
    val files: Int = 0,
)

@Serializable
data class DataSettings(
    val autoBackup: Boolean = false,
    val intervalHours: Int = 24,
    val keep: Int = 5,
)

/** Zips a user's data root and restores such a zip. API keys (`secrets.enc.json`) are never included: they are bound to this device's Keystore. */
class BackupService(private val paths: StoragePaths, private val user: String = "default", private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun skipped(rel: String) = rel == paths.secretsFile.name || rel.endsWith(".tmp") || rel.startsWith(RESTORE_LEFTOVER)

    private fun files(): List<Pair<String, File>> {
        val root = paths.root.canonicalFile
        return root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') to it }
            .filter { !skipped(it.first) }.sortedBy { it.first }.toList()
    }

    /** Writes the backup zip and returns the number of data files in it. Does not close [out]. */
    fun write(out: OutputStream): Int {
        val list = files()
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(HEADER))
        zip.write(json.encodeToString(BackupHeader.serializer(), BackupHeader(1, now(), user, list.size)).toByteArray())
        zip.closeEntry()
        for ((rel, f) in list) {
            zip.putNextEntry(ZipEntry("$DATA_PREFIX$rel"))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
        return list.size
    }

    /** Reads only the header (the first entry); null when this is not one of our backups. */
    fun inspect(input: InputStream): BackupHeader? = runCatching {
        ZipInputStream(input).use { z ->
            val e = z.nextEntry ?: return null
            if (e.name != HEADER) return null
            json.decodeFromString(BackupHeader.serializer(), z.readBytes().toString(Charsets.UTF_8))
        }
    }.getOrNull()

    /**
     * Replaces the data with the backup. Everything is extracted into a staging folder first (zip-slip safe, entry and size caps),
     * so a bad zip changes nothing. The previous data is kept as `<root>.before-restore` until the next restore. Secrets stay as they were.
     */
    fun restore(input: InputStream) {
        val root = paths.root.canonicalFile
        val staging = File(root.parentFile, root.name + ".restoring")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            var count = 0
            var total = 0L
            var header: BackupHeader? = null
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = e.name.replace('\\', '/')
                    if (name == HEADER) {
                        header = runCatching { json.decodeFromString(BackupHeader.serializer(), z.readBytes().toString(Charsets.UTF_8)) }.getOrNull()
                        continue
                    }
                    if (header == null) throw BackupException("This is not a backup made by this app")
                    if (e.isDirectory) continue
                    if (!name.startsWith(DATA_PREFIX)) throw BackupException("Unexpected entry in the backup: ${e.name}")
                    val rel = name.removePrefix(DATA_PREFIX)
                    if (rel.isEmpty() || rel.startsWith("/") || rel.split('/').any { it == ".." || it.isEmpty() }) throw BackupException("The backup contains an unsafe path: ${e.name}")
                    if (skipped(rel)) continue
                    if (++count > MAX_FILES) throw BackupException("The backup has too many files")
                    val target = File(staging, rel).canonicalFile
                    if (!target.path.startsWith(staging.canonicalPath + File.separator)) throw BackupException("The backup contains an unsafe path: ${e.name}")
                    target.parentFile.mkdirs()
                    target.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = z.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_TOTAL_BYTES) throw BackupException("The backup is larger than ${MAX_TOTAL_BYTES / 1024 / 1024 / 1024} GB")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            if (header == null) throw BackupException("This is not a backup made by this app")
            if (header!!.format > 1) throw BackupException("This backup was made by a newer version of the app")
            // Carry over what a backup never contains.
            File(root, paths.secretsFile.name).takeIf { it.isFile }?.copyTo(File(staging, paths.secretsFile.name), overwrite = true)
            val old = File(root.parentFile, root.name + BEFORE_RESTORE)
            old.deleteRecursively()
            if (!root.renameTo(old)) throw BackupException("Could not move the current data aside")
            if (!staging.renameTo(root)) {
                old.renameTo(root)
                throw BackupException("Could not put the restored data in place")
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    companion object {
        const val HEADER = "st-android-backup.json"
        const val DATA_PREFIX = "data/"
        const val BEFORE_RESTORE = ".before-restore"
        private const val RESTORE_LEFTOVER = "\u0000"
        const val MAX_FILES = 200_000
        const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024

        fun fileName(user: String, time: Long): String {
            val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply { timeZone = TimeZone.getDefault() }
            return "st-backup-${StoragePaths.sanitizeFileName(user)}-${fmt.format(Date(time))}.zip"
        }
    }
}

/** Rotating backups in an app-private folder: [runIfDue] writes a new one when the newest is older than the interval, then trims to [keep]. */
class AutoBackup(private val dir: File, private val service: BackupService, private val now: () -> Long = System::currentTimeMillis) {
    fun list(): List<File> = (dir.listFiles { f -> f.isFile && f.name.startsWith("st-backup-") && f.extension == "zip" } ?: emptyArray())
        .sortedByDescending { it.lastModified() }

    fun runIfDue(settings: DataSettings): File? {
        if (!settings.autoBackup) return null
        val newest = list().firstOrNull()
        if (newest != null && now() - newest.lastModified() < settings.intervalHours.coerceAtLeast(1) * 3_600_000L) return null
        return runNow(settings.keep)
    }

    fun runNow(keep: Int): File {
        dir.mkdirs()
        val target = File(dir, BackupService.fileName(dirUser(), now()))
        val tmp = File(dir, target.name + ".tmp")
        try {
            tmp.outputStream().use { service.write(it) }
            if (!tmp.renameTo(target)) throw BackupException("Could not save the backup")
            target.setLastModified(now())
        } finally {
            tmp.delete()
        }
        list().drop(keep.coerceAtLeast(1)).forEach { it.delete() }
        return target
    }

    private fun dirUser() = dir.name
}
