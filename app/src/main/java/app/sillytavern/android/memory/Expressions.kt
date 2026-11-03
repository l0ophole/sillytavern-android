package app.sillytavern.android.memory

import app.sillytavern.android.data.StoragePaths
import java.io.File

/** Emotion labels of SillyTavern's default classifier (GoEmotions) and the sprite names that go with them. */
object ExpressionLabels {
    val DEFAULT = listOf(
        "admiration", "amusement", "anger", "annoyance", "approval", "caring", "confusion", "curiosity", "desire", "disappointment",
        "disapproval", "disgust", "embarrassment", "excitement", "fear", "gratitude", "grief", "joy", "love", "nervousness",
        "optimism", "pride", "realization", "relief", "remorse", "sadness", "surprise", "neutral",
    )

    /** Picks the first label named in a model answer (the earliest mention wins); null when none is. */
    fun parse(answer: String, labels: List<String>): String? {
        val low = answer.lowercase()
        return labels.mapNotNull { l ->
            Regex("(?<![\\p{L}-])" + Regex.escape(l.lowercase()) + "(?![\\p{L}-])").find(low)?.let { l to it.range.first }
        }.minByOrNull { it.second }?.first
    }
}

/** Chooses an expression label for a piece of text. */
fun interface ExpressionClassifier {
    suspend fun classify(text: String, labels: List<String>): String?
}

/** Word-list classifier: no model, no network. Counts emotional words, emoji and `*action*` cues per label. */
object KeywordClassifier : ExpressionClassifier {
    private val lexicon: Map<String, Regex> = mapOf(
        "admiration" to "admir|impress|amazing|wonderful|brilliant|incredible|magnificent|marvel|awe|remarkable|👏",
        "amusement" to "laugh|giggl|chuckl|haha|hehe|lol|funny|hilarious|amus|smirk|grin|snicker|😂|🤣",
        "anger" to "angry|anger|furious|rage|fury|hate|damn|bastard|snarl|growl|scream|shout|yell|livid|enraged|😡|🤬",
        "annoyance" to "annoy|irritat|ugh|whatever|sigh|roll(s|ed)? (her|his|their) eyes|bother|tiresome|pester|tsk",
        "approval" to "agree|approve|good job|well done|exactly|indeed|right you are|nod(s|ded)?|sounds good|precisely|👍",
        "caring" to "care for|worry|worried|are you (ok|okay|alright)|comfort|gentle|tender|hold(s)? you|protect|safe with me|take care|concern",
        "confusion" to "confus|puzzl|don't understand|do not understand|what do you mean|huh|bewilder|perplex|unclear|\\?\\?",
        "curiosity" to "curious|wonder|intrigu|tilts? (her|his|their) head|interesting|how come|tell me more|what is that|\\bwhy\\b|🤔",
        "desire" to "want you|desire|crave|yearn|long(s|ing)? for|need you|lust|hunger|wish for|tempt",
        "disappointment" to "disappoint|let down|expected more|too bad|alas|unfortunately|sigh(s|ed)? heavily|dishearten",
        "disapproval" to "disapprov|shouldn't|should not|inappropriate|unacceptable|wrong of you|frown|scold|reproach|how dare",
        "disgust" to "disgust|gross|revolt|nauseat|vile|repuls|yuck|eww|sicken|foul|🤢",
        "embarrassment" to "embarrass|blush|flustered|awkward|ashamed|red-faced|cheeks (turn|burn|flush)|mortified|stammer|fluster",
        "excitement" to "excite|thrill|can't wait|cannot wait|yay|woohoo|hooray|eager|let's go|amazing news|jump(s|ed)? (up|with)|!!+|🎉",
        "fear" to "afraid|scared|terrif|fear|fright|panic|horror|tremble|shiver|dread|help me|run!|trembl|😱|😨",
        "gratitude" to "thank|grateful|appreciate|owe you|bless you|much obliged|indebted|🙏",
        "grief" to "grief|griev|mourn|funeral|passed away|died|death of|lost (him|her|them|everything)|sob(s|bing)?|weep|devastat|heartbroken",
        "joy" to "happy|joy|delight|glad|cheer|smil(e|es|ed|ing)|beam|wonderful day|laughs? with|bliss|radiant|😊|😄|😀|🥰",
        "love" to "love you|i love|adore|darling|sweetheart|beloved|my dear|kiss|embrace|cherish|affection|my heart|❤|💕|😍",
        "nervousness" to "nervous|anxious|jitter|uneasy|fidget|gulp|swallow(s|ed)? hard|hesitat|worried sick|butterflies|apprehens|shaky|um\\.\\.\\.",
        "optimism" to "hope|optimis|bright side|things will|looking forward|better days|believe in|promising|confident|tomorrow will",
        "pride" to "proud|pride|accomplish|triumph|achiev|i did it|victory|we won|deserve|honor",
        "realization" to "realiz|realis|suddenly (understand|see|know)|it hits|now i see|so that's|dawns on|figured it out|oh!|aha|epiphany",
        "relief" to "relief|relieved|phew|thank goodness|finally over|at last|safe now|exhale|sigh(s|ed)? in relief|breath(es|ed)? out",
        "remorse" to "sorry|apolog|regret|forgive me|my fault|guilt|ashamed of|shouldn't have|i was wrong|remorse|pardon me",
        "sadness" to "sad|cry|cries|crying|tears?|sorrow|lonely|miss you|depress|gloom|heavy heart|mournful|weeps?|😢|😭|☹",
        "surprise" to "surpris|shock|gasp|astonish|startl|unexpected|can't believe|cannot believe|whoa|wow|what!|eyes (widen|go wide)|stunned|😮|😲",
    ).mapValues { (_, v) -> Regex(v, RegexOption.IGNORE_CASE) }

    fun score(text: String): Map<String, Int> =
        lexicon.mapValues { (_, r) -> r.findAll(text).count() }.filterValues { it > 0 }

    override suspend fun classify(text: String, labels: List<String>): String? = classifyNow(text, labels)

    /** Highest-scoring label that exists in [labels]; the last mention wins ties; "neutral" when nothing matches. */
    fun classifyNow(text: String, labels: List<String> = ExpressionLabels.DEFAULT): String? {
        val allowed = score(text).filterKeys { it in labels }
        val best = allowed.maxOfOrNull { it.value } ?: return "neutral".takeIf { it in labels }
        val tied = allowed.filterValues { it == best }.keys
        if (tied.size == 1) return tied.first()
        return tied.maxByOrNull { l -> lexicon.getValue(l).findAll(text).maxOf { it.range.first } }
    }
}

/** Asks a language model to pick a label: [generate] sends one prompt and returns the answer. */
class LlmClassifier(private val generate: suspend (String) -> String) : ExpressionClassifier {
    override suspend fun classify(text: String, labels: List<String>): String? =
        ExpressionLabels.parse(generate(prompt(text, labels)), labels)

    companion object {
        fun prompt(text: String, labels: List<String>) =
            "Choose the emotion that best describes the speaker in the text below. Answer with exactly one word from this list and nothing else:\n" +
                labels.joinToString(", ") + "\n\nText:\n" + text.take(2000)
    }
}

data class Sprite(val label: String, val file: File)

/** Sprite images in `user/sprites/<character name>/<label>.<png|jpg|webp|gif>`; `label-2.png` style names are extra images of a label. */
class SpriteRepository(private val root: File, private val random: kotlin.random.Random = kotlin.random.Random.Default) {
    private val ext = setOf("png", "jpg", "jpeg", "webp", "gif")
    private fun dir(character: String) = File(root, StoragePaths.sanitizeFileName(character))

    private fun labelOf(f: File) = f.nameWithoutExtension.lowercase().replace(Regex("[-_]\\d+$"), "")

    fun list(character: String): List<Sprite> =
        dir(character).listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in ext }
            .map { Sprite(labelOf(it), it) }.sortedWith(compareBy({ it.label }, { it.file.name }))

    fun labels(character: String): List<String> = list(character).map { it.label }.distinct()

    /** Stores an image for [label]; a second image for the same label gets a numbered name. */
    fun add(character: String, label: String, extension: String, bytes: ByteArray): Sprite {
        val d = dir(character).also { it.mkdirs() }
        val l = StoragePaths.sanitizeFileName(label).lowercase()
        val e = extension.lowercase().takeIf { it in ext } ?: "png"
        var f = File(d, "$l.$e"); var n = 2
        while (f.exists()) f = File(d, "$l-${n++}.$e")
        f.writeBytes(bytes)
        return Sprite(l, f)
    }

    fun remove(sprite: Sprite): Boolean = sprite.file.parentFile?.parentFile?.canonicalPath == root.canonicalPath && sprite.file.delete()

    /** The sprite for [label]: exact, then [fallback], then "neutral"; null when the character has none of these. */
    fun resolve(character: String, label: String?, fallback: String): Sprite? {
        val all = list(character)
        if (all.isEmpty()) return null
        for (l in listOfNotNull(label?.lowercase(), fallback.lowercase(), "neutral")) {
            val matches = all.filter { it.label == l }
            if (matches.isNotEmpty()) return matches[random.nextInt(matches.size)]
        }
        return null
    }
}
