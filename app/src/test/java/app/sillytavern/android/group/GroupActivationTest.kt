package app.sillytavern.android.group

import app.sillytavern.android.chat.ChatMessage
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class GroupActivationTest {
    private val alice = Member("Alice.png", "Alice", 1.0)
    private val bob = Member("Bob.png", "Bob", 1.0)
    private fun said(m: Member, t: String = "x") = ChatMessage.assistant(m.name, t).withOriginalAvatar(m.avatar)
    private fun user(t: String = "hi") = ChatMessage.user("Me", t)

    @Test fun naturalMentionActivates() {
        val a = alice.copy(talkativeness = 0.0); val b = bob.copy(talkativeness = 0.0)
        assertEquals(listOf("Bob.png"), GroupActivation.natural(listOf(a, b), "hey bob, how are you", null, false, true, Random(1)))
    }

    @Test fun naturalBansLastSpeakerUnlessSelfAllowed() {
        val last = said(alice)
        assertEquals(listOf("Bob.png"), GroupActivation.natural(listOf(alice, bob), "", last, false, false, Random(1)))
        assertEquals(setOf("Alice.png", "Bob.png"), GroupActivation.natural(listOf(alice, bob), "", last, true, false, Random(1)).toSet())
    }

    @Test fun naturalFallsBackToRandomWhenNobodyRolls() {
        val r = GroupActivation.natural(listOf(alice.copy(talkativeness = 0.0), bob.copy(talkativeness = 0.0)), "", null, false, true, Random(3))
        assertEquals(1, r.size)
    }

    @Test fun naturalSingleMemberJustSpokeStillPicks() {
        val a = alice.copy(talkativeness = 0.0)
        assertEquals(listOf("Alice.png"), GroupActivation.natural(listOf(a), "", said(a), false, false, Random(1)))
    }

    @Test fun naturalNoMembersIsEmpty() = assertTrue(GroupActivation.natural(emptyList(), "hi", null, false, true).isEmpty())

    @Test fun listKeepsOrderDistinct() =
        assertEquals(listOf("Bob.png", "Alice.png"), GroupActivation.list(listOf(bob, alice, bob)))

    @Test fun pooledPicksUnspokenFirst() {
        val chat = listOf(user(), said(alice))
        assertEquals(listOf("Bob.png"), GroupActivation.pooled(listOf(alice, bob), chat, chat.last(), false, Random(1)))
    }

    @Test fun pooledAvoidsLastSpeaker() {
        val chat = listOf(user(), said(alice), said(bob))
        repeat(10) { assertEquals(listOf("Alice.png"), GroupActivation.pooled(listOf(alice, bob), chat, chat.last(), false, Random(it))) }
    }

    @Test fun pooledUserInputResetsSpoken() {
        val chat = listOf(user(), said(alice), user())
        assertEquals(1, GroupActivation.pooled(listOf(alice, bob), chat, chat.last(), true, Random(1)).size)
    }

    @Test fun swipeUsesOriginalAvatar() {
        val chat = listOf(user(), said(bob))
        assertEquals(listOf("Bob.png"), GroupActivation.swipe(listOf(alice, bob), chat))
    }

    @Test fun swipeSkipsUserAndNarrator() {
        val chat = listOf(said(alice), user(), ChatMessage.narrator("n"))
        assertEquals(listOf("Alice.png"), GroupActivation.swipe(listOf(alice, bob), chat))
    }

    @Test fun swipeMissingMemberReturnsEmpty() {
        val chat = listOf(user(), said(bob))
        assertTrue(GroupActivation.swipe(listOf(alice), chat).isEmpty())
    }

    @Test fun swipeFallsBackToNameForOldMessages() {
        val chat = listOf(ChatMessage.assistant("Alice", "x"))
        assertEquals(listOf("Alice.png"), GroupActivation.swipe(listOf(alice, bob), chat))
    }

    @Test fun extractWordsUnicode() = assertEquals(listOf("zoë", "o'neil", "42"), GroupActivation.extractWords("Zoë, O'Neil! 42"))
}
