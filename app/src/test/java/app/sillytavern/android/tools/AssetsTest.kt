package app.sillytavern.android.tools

import app.sillytavern.android.appearance.BackgroundRepository
import app.sillytavern.android.card.CharacterRepository
import app.sillytavern.android.data.StoragePaths
import app.sillytavern.android.worldinfo.WorldBookRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AssetCatalogTest {
    @Test fun arrayFormat() {
        val l = AssetCatalog.parse("""[{"type":"background","name":"Forest","url":"https://x.test/a/forest.jpg"},{"type":"live2d","url":"https://x.test/m.zip"},{"type":"theme"}]""")
        assertEquals(2, l.size) // the entry without a URL is dropped
        assertEquals(AssetType.BACKGROUND, l[0].type); assertEquals("Forest", l[0].name); assertEquals("forest.jpg", l[0].fileName)
        assertNull(l[1].type); assertFalse(l[1].installable)
    }

    @Test fun sillyTavernCategoryObjectAndAssetsKey() {
        val l = AssetCatalog.parse("""{"character":[{"id":"Bob","url":"https://x.test/bob.png","description":"d"}],"bgm":[{"url":"https://x.test/s.mp3"}]}""")
        assertEquals(AssetType.CHARACTER, l[0].type); assertEquals("Bob", l[0].name); assertEquals("d", l[0].description)
        assertNull(l[1].type); assertEquals("bgm", l[1].rawType)
        assertEquals(1, AssetCatalog.parse("""{"assets":[{"type":"world","url":"https://x.test/w.json"}]}""").size)
    }

    @Test fun invalidCatalogIsAnError() {
        assertTrue(runCatching { AssetCatalog.parse("nope") }.isFailure)
        assertTrue(runCatching { AssetCatalog.parse("5") }.isFailure)
    }

    @Test fun nonHttpUrlsAreNotInstallable() {
        assertFalse(AssetEntry(AssetType.BACKGROUND, "background", "x", "file:///etc/passwd").installable)
        assertFalse(AssetEntry(AssetType.BACKGROUND, "background", "x", "ftp://h/x.png").installable)
    }
}

class AssetInstallerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer().also { it.start() }
    @After fun stop() = server.shutdown()
    private val paths by lazy { StoragePaths(tmp.newFolder()).ensure() }
    private val chars by lazy { CharacterRepository(paths) }
    private val worlds by lazy { WorldBookRepository(paths) }
    private val installer by lazy { AssetInstaller(paths, chars, worlds, BackgroundRepository(paths)) }
    private val theme = """{"name":"Night","main_text_color":"rgba(255, 255, 255, 1)"}"""

    @Test fun installsEachTypeIntoItsFolder() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"entries":{}}"""))
        assertEquals("w", installer.install(AssetEntry(AssetType.WORLD, "world", "w", server.url("/w.json").toString())))
        assertTrue(File(paths.worlds, "w.json").exists())
        server.enqueue(MockResponse().setBody(theme))
        assertEquals("Night", installer.install(AssetEntry(AssetType.THEME, "theme", "Night", server.url("/Night.json").toString())))
        assertEquals(listOf("Night"), installer.themes())
        assertEquals(theme, installer.themeText("Night"))
        server.enqueue(MockResponse().setBody("""{"name":"Bob","description":"d"}"""))
        assertEquals("Bob", installer.install(AssetEntry(AssetType.CHARACTER, "character", "Bob", server.url("/bob.json").toString())))
        assertEquals(1, chars.list().size)
        server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
        val bg = installer.install(AssetEntry(AssetType.BACKGROUND, "background", "pic", server.url("/pic.png").toString()))
        assertEquals("pic.png", bg); assertTrue(File(paths.backgrounds, "pic.png").exists())
    }

    @Test fun badDownloadsFail() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(runCatching { runBlocking { installer.install(AssetEntry(AssetType.WORLD, "world", "w", server.url("/w.json").toString())) } }.exceptionOrNull()!!.message!!.contains("404"))
        server.enqueue(MockResponse().setBody("not a theme"))
        assertTrue(runCatching { runBlocking { installer.install(AssetEntry(AssetType.THEME, "theme", "t", server.url("/t.json").toString())) } }.isFailure)
        assertTrue(runCatching { runBlocking { installer.install(AssetEntry(null, "bgm", "s", server.url("/s.mp3").toString())) } }.isFailure)
        assertTrue(runCatching { runBlocking { installer.fetchCatalog("file:///etc/passwd") } }.isFailure)
    }

    @Test fun oversizedDownloadIsRefused() {
        server.enqueue(MockResponse().setBody("x".repeat(100)))
        val small = AssetInstaller(paths, chars, worlds, BackgroundRepository(paths))
        // fetchCatalog caps at 2 MB; check the guard through a body larger than the cap using a chunked response
        server.enqueue(MockResponse().setChunkedBody("y".repeat(2_100_000), 65536))
        assertTrue(runCatching { runBlocking { small.fetchCatalog(server.url("/c").toString()) } }.exceptionOrNull() != null)
    }

    @Test fun fetchCatalogParsesTheDownload() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"type":"background","url":"https://x.test/a.jpg"}]"""))
        assertEquals(1, installer.fetchCatalog(server.url("/c.json").toString()).size)
    }

    @Test fun defaultContentInstallsOnceFromBundledFiles() {
        val real = File("src/main/assets")
        val d = DefaultContent({ p -> File(real, p).readBytes() }, installer, chars, worlds)
        assertEquals(2, d.installStarter())
        assertEquals(0, d.installStarter())
        assertEquals("Seraphina", chars.list().single().card.name)
        assertEquals(listOf("Eldoria"), worlds.list())
        assertEquals(5, d.installAll())
        assertEquals(5, installer.themes().size)
        assertTrue(d.items.all { d.isInstalled(it) })
    }
}
