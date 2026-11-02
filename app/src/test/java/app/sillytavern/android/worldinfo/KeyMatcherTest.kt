package app.sillytavern.android.worldinfo

import org.junit.Assert.*
import org.junit.Test

class KeyMatcherTest {
    private val s = WorldInfoSettings()
    private fun e(block: WorldEntry.() -> WorldEntry = { this }) = WorldEntry().block()
    private fun m(hay: String, key: String, entry: WorldEntry = e(), settings: WorldInfoSettings = s) =
        KeyMatcher.matches(hay, key, entry, settings)
    private fun buf(vararg msgs: String, st: WorldInfoSettings = s, g: GlobalScanData = GlobalScanData()) =
        ScanBuffer(msgs.toList(), g, st)

    @Test fun plainCaseInsensitive() = assertTrue(m("a dragon", "Dragon"))
    @Test fun caseSensitiveEntryOverridesSetting() {
        assertFalse(m("a dragon", "Dragon", e { copy(caseSensitive = true) }))
        assertTrue(m("a Dragon", "Dragon", e { copy(caseSensitive = true) }))
        assertTrue(m("a dragon", "Dragon", e { copy(caseSensitive = false) }, s.copy(caseSensitive = true)))
    }
    @Test fun wholeWordSingle() {
        val w = e { copy(matchWholeWords = true) }
        assertFalse(m("concatenate", "cat", w)); assertTrue(m("a cat.", "cat", w))
    }
    @Test fun wholeWordMultiWordUsesContains() = assertTrue(m("the red dragon sleeps", "red dragon", e { copy(matchWholeWords = true) }))
    @Test fun regexKey() = assertTrue(m("DRAGON", "/dr.g[oa]n/i"))
    @Test fun invalidRegexIsNoMatch() = assertFalse(m("[unclosed", "/[unclosed/"))
    @Test fun slashKeyWithInnerSlashIsPlain() = assertTrue(m("cat /dev/null now", "/dev/null"))

    @Test fun scanDepthLimitsMessages() {
        val b = buf("newest", "older")
        val t = b.text(e(), ScanState.INITIAL)
        assertTrue("newest" in t && "older" in t) // default depth 2
        assertFalse("older" in buf("newest", "older", st = s.copy(depth = 1)).text(e(), ScanState.INITIAL))
    }
    @Test fun scanDepthZeroEmpty() = assertEquals("", buf("x", st = s.copy(depth = 0)).text(e(), ScanState.INITIAL))
    @Test fun entryScanDepthOverrides() {
        val t = buf("a", "b", "c", st = s.copy(depth = 1)).text(e { copy(scanDepth = 3) }, ScanState.INITIAL)
        assertTrue("c" in t)
    }
    @Test fun globalFieldsOnlyWhenOptedIn() {
        val g = GlobalScanData(scenario = "SCENE", characterDescription = "DESC")
        assertFalse("SCENE" in buf("x", g = g).text(e(), ScanState.INITIAL))
        val t = buf("x", g = g).text(e { copy(matchScenario = true) }, ScanState.INITIAL)
        assertTrue("SCENE" in t && "DESC" !in t)
    }
    @Test fun recurseBufferExcludedInMinActivations() {
        val b = buf("x"); b.addRecurse("RECURSED")
        assertTrue("RECURSED" in b.text(e(), ScanState.RECURSION))
        assertFalse("RECURSED" in b.text(e(), ScanState.MIN_ACTIVATIONS))
        assertTrue(b.hasRecurse())
    }
    @Test fun advanceDeepensScan() {
        val b = buf("a", "b", "c"); assertEquals(2, b.depth()); b.advance(); assertEquals(3, b.depth())
        assertTrue("c" in b.text(e(), ScanState.MIN_ACTIVATIONS))
    }
}
