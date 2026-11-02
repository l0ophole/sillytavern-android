package app.sillytavern.android.worldinfo

import app.sillytavern.android.prompt.ApproxTokenCounter
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TimedEffectsTest {
    private fun se(block: WorldEntry.() -> WorldEntry) = ScanEntry("b", WorldEntry(uid = 1, key = listOf("a"), content = "C").block())
    private fun chat(n: Int, withKeyAt: Set<Int> = emptySet()) = (n - 1 downTo 0).map { if (it in withKeyAt) "a" else "x" } // newest first
    private fun run(e: ScanEntry, len: Int, keyAt: Set<Int>, st: TimedState, dry: Boolean = false, depth: Int = 1): Pair<Boolean, TimedState> {
        val r = WorldInfoScanner(WorldInfoSettings(depth = depth), ApproxTokenCounter)
            .scan(listOf(e), chat(len, keyAt), 100000, GlobalScanData(), st, dry)
        return (r.activated.isNotEmpty()) to r.timed
    }

    @Test fun delaySuppressesEarlyChat() {
        val e = se { copy(delay = 3) }
        assertFalse(run(e, 2, setOf(1), TimedState()).first)
        assertTrue(run(e, 3, setOf(2), TimedState()).first)
    }
    @Test fun stickyKeepsActiveWithoutKeys() {
        val e = se { copy(sticky = 3) } // window = 3 messages: active at lengths 6 and 7, over at 8
        var (hit, st) = run(e, 5, setOf(4), TimedState()); assertTrue(hit)
        run(e, 6, emptySet(), st).let { assertTrue("sticky msg 6", it.first); st = it.second }
        run(e, 7, emptySet(), st).let { assertTrue("sticky msg 7", it.first); st = it.second }
        assertFalse("sticky over at 8", run(e, 8, emptySet(), st).first)
    }
    @Test fun cooldownSuppressesAfterSticky() {
        val e = se { copy(sticky = 1, cooldown = 2) }
        var (_, st) = run(e, 5, setOf(4), TimedState())
        st = run(e, 6, emptySet(), st).second           // sticky active
        assertFalse("cooldown at 7", run(e, 7, setOf(6), st).first)
    }
    @Test fun cooldownAloneSuppresses() {
        val e = se { copy(cooldown = 2) }
        val (hit, st) = run(e, 5, setOf(4), TimedState()); assertTrue(hit)
        assertFalse(run(e, 6, setOf(5), st).first)
        assertTrue(run(e, 7, setOf(6), st).first)
    }
    @Test fun stickySkipsProbabilityReroll() {
        val e = se { copy(sticky = 3, probability = 50) }
        val st = TimedState().record(listOf(e), 5)
        // sticky entry on a later turn fires regardless of the roll (many seeds)
        for (seed in 0..20) {
            val r = WorldInfoScanner(WorldInfoSettings(), ApproxTokenCounter, kotlin.random.Random(seed))
                .scan(listOf(e), chat(6), 100000, GlobalScanData(), st)
            assertEquals(1, r.activated.size)
        }
    }
    @Test fun dryRunDoesNotAdvance() {
        val e = se { copy(sticky = 2) }
        val (hit, st) = run(e, 5, setOf(4), TimedState(), dry = true)
        assertTrue(hit); assertTrue(st.sticky.isEmpty())
    }
    @Test fun jsonRoundTrip() {
        val st = TimedState(sticky = mapOf("b.1" to TimedState.Effect(3, 5, false)), cooldown = mapOf("b.2" to TimedState.Effect(1, 4, true)))
        val back = TimedState.fromJson(st.toJson())
        assertEquals(st.sticky, back.sticky); assertEquals(st.cooldown, back.cooldown)
    }
    @Test fun corruptJsonGivesEmptyState() {
        val bad = Json.parseToJsonElement("""{"sticky":{"b.1":"oops","b.2":{"start":"x"}},"cooldown":5}""").jsonObject
        val st = TimedState.fromJson(bad)
        assertTrue(st.sticky.isEmpty() && st.cooldown.isEmpty())
        assertTrue(TimedState.fromJson(null).sticky.isEmpty())
    }
}
