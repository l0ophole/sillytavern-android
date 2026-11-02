package app.sillytavern.android.worldinfo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val DEFAULT_DEPTH = 4
const val DEFAULT_WEIGHT = 100
const val MAX_SCAN_DEPTH = 1000

object WiPosition { const val BEFORE = 0; const val AFTER = 1; const val AN_TOP = 2; const val AN_BOTTOM = 3; const val AT_DEPTH = 4; const val EM_TOP = 5; const val EM_BOTTOM = 6; const val OUTLET = 7 }
object WiLogic { const val AND_ANY = 0; const val NOT_ALL = 1; const val NOT_ANY = 2; const val AND_ALL = 3 }

/** One lorebook entry (SillyTavern world-file format). Unknown keys live in [extra] and survive re-export. */
data class WorldEntry(
    val uid: Int = 0,
    val key: List<String> = emptyList(),
    val keysecondary: List<String> = emptyList(),
    val comment: String = "",
    val content: String = "",
    val constant: Boolean = false,
    val selective: Boolean = true,
    val selectiveLogic: Int = WiLogic.AND_ANY,
    val order: Int = 100,
    val position: Int = WiPosition.BEFORE,
    val disable: Boolean = false,
    val ignoreBudget: Boolean = false,
    val excludeRecursion: Boolean = false,
    val preventRecursion: Boolean = false,
    val delayUntilRecursion: Int = 0,
    val probability: Int = 100,
    val useProbability: Boolean = true,
    val depth: Int = DEFAULT_DEPTH,
    val outletName: String = "",
    val group: String = "",
    val groupOverride: Boolean = false,
    val groupWeight: Int = DEFAULT_WEIGHT,
    val scanDepth: Int? = null,
    val caseSensitive: Boolean? = null,
    val matchWholeWords: Boolean? = null,
    val useGroupScoring: Boolean? = null,
    val role: Int = 0,
    val sticky: Int? = null,
    val cooldown: Int? = null,
    val delay: Int? = null,
    val matchPersonaDescription: Boolean = false,
    val matchCharacterDescription: Boolean = false,
    val matchCharacterPersonality: Boolean = false,
    val matchCharacterDepthPrompt: Boolean = false,
    val matchScenario: Boolean = false,
    val matchCreatorNotes: Boolean = false,
    val triggers: List<String> = emptyList(),
    val characterFilterNames: List<String> = emptyList(),
    val characterFilterTags: List<String> = emptyList(),
    val characterFilterExclude: Boolean = false,
    val displayIndex: Int = 0,
    val extra: JsonObject = JsonObject(emptyMap()),
)

data class WorldBook(
    val name: String,
    val entries: List<WorldEntry> = emptyList(),
    val extra: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class WorldInfoSettings(
    val depth: Int = 2,
    val minActivations: Int = 0,
    val minActivationsDepthMax: Int = 0,
    val budget: Int = 25,
    val budgetCap: Int = 0,
    val includeNames: Boolean = true,
    val recursive: Boolean = false,
    val overflowAlert: Boolean = false,
    val caseSensitive: Boolean = false,
    val matchWholeWords: Boolean = false,
    val useGroupScoring: Boolean = false,
    val characterStrategy: Int = 1,
    val maxRecursionSteps: Int = 0,
)
