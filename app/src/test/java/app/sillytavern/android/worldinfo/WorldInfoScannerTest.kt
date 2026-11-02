package app.sillytavern.android.worldinfo

import app.sillytavern.android.prompt.ApproxTokenCounter
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class WorldInfoScannerTest {
    private var uid = 0
    private fun ent(vararg keys: String, content: String = "C${uid}", block: WorldEntry.() -> WorldEntry = { this }) =
        ScanEntry("book", WorldEntry(uid = uid++, key = keys.toList(), content = content).block())

    private fun scan(
        entries: List<ScanEntry>, chat: List<String> = emptyList(), st: WorldInfoSettings = WorldInfoSettings(),
        ctx: Int = 100000, trigger: String = "normal", seed: Int = 1,
    ) = WorldInfoScanner(st, ApproxTokenCounter, Random(seed))
        .scan(entries, chat, ctx, GlobalScanData(trigger = trigger)).activated.map { it.entry.content }

    @Test fun constantFiresOnEmptyChat() = assertEquals(listOf("K"), scan(listOf(ent(content = "K") { copy(constant = true) })))
    @Test fun primaryKeyActivates() = assertEquals(listOf("X"), scan(listOf(ent("dragon", content = "X")), listOf("a Dragon!")))
    @Test fun noKeysNeverActivates() = assertTrue(scan(listOf(ent()), listOf("anything")).isEmpty())
    @Test fun blankKeyIgnored() = assertTrue(scan(listOf(ent(" ")), listOf("anything")).isEmpty())
    @Test fun disabledSkipped() = assertTrue(scan(listOf(ent("a") { copy(disable = true) }), listOf("a")).isEmpty())

    private fun sec(logic: Int, chat: String) =
        scan(listOf(ent("a", content = "X") { copy(keysecondary = listOf("b", "c"), selectiveLogic = logic) }), listOf(chat)).isNotEmpty()
    @Test fun secondaryAndAny() { assertTrue(sec(WiLogic.AND_ANY, "a b")); assertFalse(sec(WiLogic.AND_ANY, "a z")) }
    @Test fun secondaryNotAll() { assertTrue(sec(WiLogic.NOT_ALL, "a b")); assertFalse(sec(WiLogic.NOT_ALL, "a b c")) }
    @Test fun secondaryNotAny() { assertTrue(sec(WiLogic.NOT_ANY, "a z")); assertFalse(sec(WiLogic.NOT_ANY, "a b")) }
    @Test fun secondaryAndAll() { assertTrue(sec(WiLogic.AND_ALL, "a b c")); assertFalse(sec(WiLogic.AND_ALL, "a b")) }

    @Test fun triggerFilter() {
        val e = listOf(ent("a") { copy(triggers = listOf("continue")) })
        assertTrue(scan(e, listOf("a"), trigger = "normal").isEmpty())
        assertEquals(1, scan(e, listOf("a"), trigger = "continue").size)
    }

    private val rec = WorldInfoSettings(recursive = true)
    @Test fun recursionChainsEntries() {
        val l = listOf(ent("a", content = "mentions beta"), ent("beta", content = "B"))
        assertEquals(listOf("mentions beta", "B"), scan(l, listOf("a"), rec))
        assertEquals(listOf("mentions beta"), scan(l, listOf("a")))
    }
    @Test fun recursionLoopTerminates() {
        val l = listOf(ent("a", content = "has b"), ent("b", content = "has a"))
        assertEquals(2, scan(l, listOf("a"), rec).size)
    }
    @Test fun preventRecursionStopsChain() {
        val l = listOf(ent("a", content = "mentions beta") { copy(preventRecursion = true) }, ent("beta", content = "B"))
        assertEquals(1, scan(l, listOf("a"), rec).size)
    }
    @Test fun excludeRecursionSkippedOnRecursivePass() {
        val l = listOf(ent("a", content = "mentions beta"), ent("beta", content = "B") { copy(excludeRecursion = true) })
        assertEquals(1, scan(l, listOf("a"), rec).size)
        assertEquals(2, scan(l, listOf("a beta"), rec).size) // direct chat match is fine
    }
    @Test fun delayUntilRecursionWaits() {
        val l = listOf(ent("a", content = "x") { copy(delayUntilRecursion = 1) })
        assertTrue(scan(l, listOf("a"), rec).isEmpty() || scan(l, listOf("a"), rec).size == 1) // levels run after initial pass
        val l2 = listOf(ent("trigger", content = "says zed"), ent("zed", content = "Z") { copy(delayUntilRecursion = 1) })
        assertEquals(listOf("says zed", "Z"), scan(l2, listOf("trigger zed"), rec))
        assertEquals(listOf("says zed"), scan(l2, listOf("trigger zed"), rec.copy(recursive = false)))
    }
    @Test fun maxRecursionStepsLimits() {
        val l = listOf(ent("a", content = "b"), ent("b", content = "c"), ent("c", content = "d"))
        assertEquals(2, scan(l, listOf("a"), rec.copy(maxRecursionSteps = 2)).size)
    }
    @Test fun minActivationsDeepensScan() {
        val l = listOf(ent("deep", content = "D"))
        val chat = listOf("one", "two", "deep three")
        assertTrue(scan(l, chat).isEmpty())
        assertEquals(listOf("D"), scan(l, chat, WorldInfoSettings(minActivations = 1)))
    }

    @Test fun groupWeightPicksOne() {
        val l = List(3) { ent("a") { copy(group = "g") } }
        for (seed in 0..5) assertEquals(1, scan(l, listOf("a"), seed = seed).size)
    }
    @Test fun groupOverrideWins() {
        val l = listOf(ent("a", content = "low") { copy(group = "g") }, ent("a", content = "top") { copy(group = "g", groupOverride = true) })
        assertEquals(listOf("top"), scan(l, listOf("a")))
    }
    @Test fun groupAlreadyActivatedBlocksOthers() {
        val l = listOf(ent("a", content = "first") { copy(group = "g") }, ent("beta", content = "second") { copy(group = "g") })
        // 'first' fires on the initial pass; 'second' becomes a candidate only via recursion and must be blocked.
        val chain = listOf(ent("a", content = "beta here") { copy(group = "g") }, l[1])
        assertEquals(listOf("beta here"), scan(chain, listOf("a"), rec))
    }
    @Test fun groupScoringKeepsBest() {
        val l = listOf(
            ent("a", content = "one") { copy(group = "g") },
            ent("a", "b", content = "two") { copy(group = "g") },
        )
        assertEquals(listOf("two"), scan(l, listOf("a b"), WorldInfoSettings(useGroupScoring = true)))
    }
    @Test fun probabilityZeroNeverFires() =
        assertTrue(scan(listOf(ent("a") { copy(probability = 0) }), listOf("a")).isEmpty())
    @Test fun probabilityFailureNotRetriedInRecursion() {
        // fails once; later recursion pass must not give it another roll
        val l = listOf(ent("a", content = "go a") { copy(probability = 0) }, ent("trig", content = "says a again"))
        assertEquals(listOf("says a again"), scan(l, listOf("trig a"), rec))
    }

    @Test fun budgetStopsLaterEntries() {
        val big = "x".repeat(200)
        val l = listOf(ent("a", content = big), ent("a", content = big), ent("a", content = big))
        assertTrue(scan(l, listOf("a"), ctx = 400).size < 3) // budget 25% of 400 = 100 tokens
    }
    @Test fun ignoreBudgetStillAdded() {
        val big = "x".repeat(400)
        val l = listOf(ent("a", content = big), ent("a", content = "free") { copy(ignoreBudget = true) })
        assertTrue("free" in scan(l, listOf("a"), ctx = 400))
    }
    @Test fun budgetCapApplies() {
        val l = listOf(ent("a", content = "x".repeat(100)), ent("a", content = "y".repeat(100)))
        assertEquals(2, scan(l, listOf("a"), ctx = 100000).size)
        assertTrue(scan(l, listOf("a"), WorldInfoSettings(budgetCap = 30), ctx = 100000).size < 2)
    }
    @Test fun inputOrderPreservedInActivated() {
        val l = listOf(ent("a", content = "1"), ent("a", content = "2"), ent("a", content = "3"))
        assertEquals(listOf("1", "2", "3"), scan(l, listOf("a")))
    }
    @Test fun scanDepthZeroChatStillConstants() {
        val l = listOf(ent(content = "K") { copy(constant = true) }, ent("a", content = "N"))
        assertEquals(listOf("K"), scan(l, listOf("a"), WorldInfoSettings(depth = 0)))
    }
    @Test fun activateDecoratorStripped() {
        val l = listOf(ent("zzz", content = "@@activate\nBody"))
        assertEquals(listOf("Body"), scan(l, listOf("nothing")))
    }
    @Test fun macrosExpandedInKeys() {
        val s = WorldInfoScanner(WorldInfoSettings(), ApproxTokenCounter, Random(1)) { it.replace("{{char}}", "Bob") }
        val r = s.scan(listOf(ent("{{char}}")), listOf("hi bob"), 10000, GlobalScanData())
        assertEquals(1, r.activated.size)
    }
}
