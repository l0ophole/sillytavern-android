package app.sillytavern.android.data

import java.io.File

enum class MaidKind { TEMP_FILE, ORPHAN_CHATS, UNUSED_AVATAR, EMPTY_FOLDER }

/** One thing the data maid would delete. [path] is relative to the data root. */
data class MaidFinding(val kind: MaidKind, val path: String, val bytes: Long)

/** Finds leftovers in a data root: nothing is deleted until [clean] is given findings, and only paths inside the root are touched. */
class DataMaid(private val paths: StoragePaths) {
    /**
     * @param personaAvatars file names in `User Avatars/` that a persona uses.
     * Group chats (`group chats/`) are never touched.
     */
    fun scan(personaAvatars: Set<String>): List<MaidFinding> {
        val root = paths.root.canonicalFile
        val out = ArrayList<MaidFinding>()
        fun rel(f: File) = f.canonicalFile.relativeTo(root).path.replace(File.separatorChar, '/')
        fun size(f: File) = if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        root.walkTopDown().filter { it.isFile && (it.name.endsWith(".tmp") || it.name.endsWith(".restoring")) }
            .forEach { out += MaidFinding(MaidKind.TEMP_FILE, rel(it), it.length()) }

        val characters = (paths.characters.listFiles { f -> f.extension.equals("png", true) } ?: emptyArray())
            .map { it.nameWithoutExtension }.toSet()
        (paths.chats.listFiles { f -> f.isDirectory } ?: emptyArray())
            .filter { it.name !in characters && it.name !in characters.map(StoragePaths::sanitizeFileName) }
            .forEach { out += MaidFinding(MaidKind.ORPHAN_CHATS, rel(it), size(it)) }

        (paths.userAvatars.listFiles { f -> f.isFile } ?: emptyArray())
            .filter { it.name !in personaAvatars }
            .forEach { out += MaidFinding(MaidKind.UNUSED_AVATAR, rel(it), it.length()) }

        // Deepest first so a folder emptied by another finding is still reported next scan, not skipped.
        listOf(paths.userImages, paths.sprites, paths.dataBank, paths.vectors).forEach { base ->
            if (!base.isDirectory) return@forEach
            base.walkBottomUp().filter { it.isDirectory && it != base && it.list().isNullOrEmpty() }
                .forEach { out += MaidFinding(MaidKind.EMPTY_FOLDER, rel(it), 0) }
        }
        return out.sortedWith(compareBy({ it.kind }, { it.path }))
    }

    /** Deletes the findings and returns how many bytes were freed. Paths that escape the data root are ignored. */
    fun clean(findings: List<MaidFinding>): Long {
        val root = paths.root.canonicalFile
        var freed = 0L
        for (f in findings) {
            val target = File(root, f.path).canonicalFile
            if (target == root || !target.path.startsWith(root.path + File.separator)) continue
            if (!target.exists()) continue
            val bytes = if (target.isFile) target.length() else target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (target.deleteRecursively()) freed += bytes
        }
        return freed
    }
}
