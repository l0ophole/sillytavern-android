package app.sillytavern.android.card

import org.junit.Assert.*
import org.junit.Test

class CardFieldsTest {
    @Test fun favDefaultsFalseAndRoundTrips() {
        val c = CharacterCard(name = "A")
        assertFalse(c.fav)
        val back = CardCodec.parse(CardCodec.toJson(c.withFav(true)))
        assertTrue(back.fav); assertFalse(back.withFav(false).fav)
    }

    @Test fun talkativenessDefaultsHalf() = assertEquals(0.5, CharacterCard(name = "A").talkativeness, 0.0)

    @Test fun talkativenessStringInV1StyleCardParsed() {
        assertEquals(0.8, CardCodec.parse("""{"name":"A","talkativeness":"0.8"}""").talkativeness, 1e-9)
        assertEquals(0.3, CardCodec.parse("""{"spec":"chara_card_v2","data":{"name":"A","extensions":{"talkativeness":"0.3"}}}""").talkativeness, 1e-9)
    }

    @Test fun unknownExtensionsKeptWhenSettingFav() {
        val c = CardCodec.parse("""{"spec":"chara_card_v2","data":{"name":"A","extensions":{"depth_prompt":{"prompt":"p"}}}}""").withFav(true)
        assertTrue(CardCodec.toJson(c).contains("depth_prompt"))
    }

    @Test fun invalidTalkativenessClamped() {
        assertEquals(1.0, CharacterCard(name = "A").withTalkativeness(7.0).talkativeness, 0.0)
        assertEquals(0.5, CardCodec.parse("""{"name":"A","talkativeness":"abc"}""").talkativeness, 0.0)
    }
}
