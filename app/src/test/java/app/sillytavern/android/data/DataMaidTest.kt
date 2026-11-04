package app.sillytavern.android.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DataMaidTest {
    private fun paths() = StoragePaths(File(Files.createTempDirectory("dm").toFile(), "data")).ensure()

    @Test fun findsLeftoversAndKeepsLiveData() {
        val p = paths()
        File(p.characters, "alive.png").writeBytes(byteArrayOf(1))
        File(p.chats, "alive").mkdirs(); File(p.chats, "alive/a.jsonl").writeText("x")
        File(p.chats, "gone").mkdirs(); File(p.chats, "gone/a.jsonl").writeText("12345")
        File(p.userAvatars, "used.png").writeBytes(byteArrayOf(1)); File(p.userAvatars, "stale.png").writeBytes(byteArrayOf(1, 2))
        File(p.root, "settings.json.tmp").writeText("t")
        File(p.userImages, "emptyfolder").mkdirs()
        File(p.userImages, "full").mkdirs(); File(p.userImages, "full/i.png").writeBytes(byteArrayOf(1))
        File(p.groupChats, "g.jsonl").writeText("keep")

        val maid = DataMaid(p)
        val found = maid.scan(setOf("used.png"))
        assertEquals(
            setOf("settings.json.tmp", "chats/gone", "User Avatars/stale.png", "user/images/emptyfolder"),
            found.map { it.path }.toSet(),
        )
        assertEquals(5L, found.single { it.path == "chats/gone" }.bytes)
        val freed = maid.clean(found)
        assertEquals(1L + 5 + 2, freed)
        assertTrue(File(p.chats, "alive/a.jsonl").exists()); assertTrue(File(p.groupChats, "g.jsonl").exists())
        assertTrue(File(p.userImages, "full/i.png").exists()); assertTrue(File(p.userAvatars, "used.png").exists())
        assertTrue(maid.scan(setOf("used.png")).isEmpty())
    }

    @Test fun cleanNeverLeavesTheRoot() {
        val p = paths()
        val outside = File(p.root.parentFile, "precious.txt").apply { writeText("keep") }
        val freed = DataMaid(p).clean(listOf(
            MaidFinding(MaidKind.TEMP_FILE, "../precious.txt", 4), MaidFinding(MaidKind.EMPTY_FOLDER, "", 0),
        ))
        assertEquals(0L, freed)
        assertTrue(outside.exists()); assertTrue(p.root.exists())
    }
}
