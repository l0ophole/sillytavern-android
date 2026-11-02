package app.sillytavern.android.worldinfo

import kotlinx.serialization.json.*

/** Reads and writes SillyTavern world files and the V2 card `character_book` shape. */
object WorldJson {
    private val json = Json { prettyPrint = true }

    private val known = setOf(
        "uid", "key", "keysecondary", "comment", "content", "constant", "selective", "selectiveLogic", "order", "position",
        "disable", "ignoreBudget", "excludeRecursion", "preventRecursion", "delayUntilRecursion", "probability",
        "useProbability", "depth", "outletName", "group", "groupOverride", "groupWeight", "scanDepth", "caseSensitive",
        "matchWholeWords", "useGroupScoring", "role", "sticky", "cooldown", "delay", "matchPersonaDescription",
        "matchCharacterDescription", "matchCharacterPersonality", "matchCharacterDepthPrompt", "matchScenario",
        "matchCreatorNotes", "triggers", "characterFilter", "displayIndex",
    )

    private fun JsonElement?.prim() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.str(k: String, d: String = "") = this[k].prim()?.contentOrNull ?: d
    private fun JsonObject.int(k: String, d: Int): Int = this[k].prim()?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.booleanOrNull?.let { b -> if (b) 1 else 0 } } ?: d
    private fun JsonObject.intN(k: String): Int? = this[k].prim()?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject.bool(k: String, d: Boolean) = this[k].prim()?.let { it.booleanOrNull ?: it.intOrNull?.let { n -> n != 0 } } ?: d
    private fun JsonObject.boolN(k: String): Boolean? = this[k].prim()?.let { it.booleanOrNull }
    private fun JsonObject.strs(k: String): List<String> = when (val v = this[k]) {
        is JsonArray -> v.mapNotNull { it.prim()?.contentOrNull }
        is JsonPrimitive -> if (v is JsonNull) emptyList() else v.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        else -> emptyList()
    }

    private fun entry(o: JsonObject, fallbackUid: Int): WorldEntry {
        val cf = o["characterFilter"] as? JsonObject
        return WorldEntry(
            uid = o.int("uid", fallbackUid), key = o.strs("key"), keysecondary = o.strs("keysecondary"),
            comment = o.str("comment"), content = o.str("content"), constant = o.bool("constant", false),
            selective = o.bool("selective", true), selectiveLogic = o.int("selectiveLogic", 0),
            order = o.int("order", 100), position = o.int("position", 0), disable = o.bool("disable", false),
            ignoreBudget = o.bool("ignoreBudget", false), excludeRecursion = o.bool("excludeRecursion", false),
            preventRecursion = o.bool("preventRecursion", false), delayUntilRecursion = o.int("delayUntilRecursion", 0),
            probability = o.int("probability", 100), useProbability = o.bool("useProbability", true),
            depth = o.int("depth", DEFAULT_DEPTH), outletName = o.str("outletName"), group = o.str("group"),
            groupOverride = o.bool("groupOverride", false), groupWeight = o.int("groupWeight", DEFAULT_WEIGHT),
            scanDepth = o.intN("scanDepth"), caseSensitive = o.boolN("caseSensitive"),
            matchWholeWords = o.boolN("matchWholeWords"), useGroupScoring = o.boolN("useGroupScoring"),
            role = o.int("role", 0), sticky = o.intN("sticky"), cooldown = o.intN("cooldown"), delay = o.intN("delay"),
            matchPersonaDescription = o.bool("matchPersonaDescription", false),
            matchCharacterDescription = o.bool("matchCharacterDescription", false),
            matchCharacterPersonality = o.bool("matchCharacterPersonality", false),
            matchCharacterDepthPrompt = o.bool("matchCharacterDepthPrompt", false),
            matchScenario = o.bool("matchScenario", false), matchCreatorNotes = o.bool("matchCreatorNotes", false),
            triggers = o.strs("triggers"),
            characterFilterNames = cf?.strs("names").orEmpty(), characterFilterTags = cf?.strs("tags").orEmpty(),
            characterFilterExclude = cf?.bool("isExclude", false) ?: false,
            displayIndex = o.int("displayIndex", fallbackUid),
            extra = JsonObject(o.filterKeys { it !in known }),
        )
    }

    private fun strArr(l: List<String>) = JsonArray(l.map(::JsonPrimitive))
    private fun nInt(v: Int?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull
    private fun nBool(v: Boolean?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull

    private fun entryJson(e: WorldEntry) = buildJsonObject {
        e.extra.forEach { (k, v) -> put(k, v) }
        put("uid", e.uid); put("key", strArr(e.key)); put("keysecondary", strArr(e.keysecondary))
        put("comment", e.comment); put("content", e.content); put("constant", e.constant); put("selective", e.selective)
        put("selectiveLogic", e.selectiveLogic); put("order", e.order); put("position", e.position)
        put("disable", e.disable); put("ignoreBudget", e.ignoreBudget); put("excludeRecursion", e.excludeRecursion)
        put("preventRecursion", e.preventRecursion); put("delayUntilRecursion", e.delayUntilRecursion)
        put("probability", e.probability); put("useProbability", e.useProbability); put("depth", e.depth)
        put("outletName", e.outletName); put("group", e.group); put("groupOverride", e.groupOverride)
        put("groupWeight", e.groupWeight); put("scanDepth", nInt(e.scanDepth)); put("caseSensitive", nBool(e.caseSensitive))
        put("matchWholeWords", nBool(e.matchWholeWords)); put("useGroupScoring", nBool(e.useGroupScoring))
        put("role", e.role); put("sticky", nInt(e.sticky)); put("cooldown", nInt(e.cooldown)); put("delay", nInt(e.delay))
        put("matchPersonaDescription", e.matchPersonaDescription); put("matchCharacterDescription", e.matchCharacterDescription)
        put("matchCharacterPersonality", e.matchCharacterPersonality); put("matchCharacterDepthPrompt", e.matchCharacterDepthPrompt)
        put("matchScenario", e.matchScenario); put("matchCreatorNotes", e.matchCreatorNotes)
        put("triggers", strArr(e.triggers)); put("displayIndex", e.displayIndex)
        put("characterFilter", buildJsonObject {
            put("isExclude", e.characterFilterExclude); put("names", strArr(e.characterFilterNames)); put("tags", strArr(e.characterFilterTags))
        })
    }

    fun decode(name: String, text: String): WorldBook {
        val root = json.parseToJsonElement(text.trimStart('﻿')).jsonObject
        val entries = when (val e = root["entries"]) {
            is JsonObject -> e.entries.mapIndexedNotNull { i, (k, v) -> (v as? JsonObject)?.let { entry(it, k.toIntOrNull() ?: i) } }
            is JsonArray -> e.mapIndexedNotNull { i, v -> (v as? JsonObject)?.let { entry(it, i) } }
            else -> emptyList()
        }
        return WorldBook(name, entries, JsonObject(root.filterKeys { it != "entries" }))
    }

    fun encode(book: WorldBook): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        book.extra.forEach { (k, v) -> put(k, v) }
        put("entries", buildJsonObject { book.entries.forEach { put(it.uid.toString(), entryJson(it)) } })
    })

    fun fromCharacterBook(name: String, cb: JsonObject): WorldBook {
        val arr = cb["entries"] as? JsonArray ?: JsonArray(emptyList())
        val entries = arr.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val x = o["extensions"] as? JsonObject ?: JsonObject(emptyMap())
            WorldEntry(
                uid = o.int("id", i), key = o.strs("keys"), keysecondary = o.strs("secondary_keys"),
                comment = o.str("comment"), content = o.str("content"), constant = o.bool("constant", false),
                selective = o.bool("selective", false), order = o.int("insertion_order", 100),
                position = x.intN("position") ?: if (o.str("position") == "before_char") 0 else 1,
                disable = !o.bool("enabled", true),
                excludeRecursion = x.bool("exclude_recursion", false), preventRecursion = x.bool("prevent_recursion", false),
                delayUntilRecursion = x.int("delay_until_recursion", 0), displayIndex = x.int("display_index", i),
                probability = x.int("probability", 100), useProbability = x.bool("useProbability", true),
                depth = x.int("depth", DEFAULT_DEPTH), selectiveLogic = x.int("selectiveLogic", 0),
                outletName = x.str("outlet_name"), group = x.str("group"), groupOverride = x.bool("group_override", false),
                groupWeight = x.int("group_weight", DEFAULT_WEIGHT), scanDepth = x.intN("scan_depth"),
                caseSensitive = x.boolN("case_sensitive"), matchWholeWords = x.boolN("match_whole_words"),
                useGroupScoring = x.boolN("use_group_scoring"), role = x.int("role", 0),
                sticky = x.intN("sticky"), cooldown = x.intN("cooldown"), delay = x.intN("delay"),
                matchPersonaDescription = x.bool("match_persona_description", false),
                matchCharacterDescription = x.bool("match_character_description", false),
                matchCharacterPersonality = x.bool("match_character_personality", false),
                matchCharacterDepthPrompt = x.bool("match_character_depth_prompt", false),
                matchScenario = x.bool("match_scenario", false), matchCreatorNotes = x.bool("match_creator_notes", false),
                triggers = x.strs("triggers"), ignoreBudget = x.bool("ignore_budget", false),
            )
        }
        return WorldBook(name, entries, JsonObject(cb.filterKeys { it != "entries" }))
    }

    fun toCharacterBook(book: WorldBook): JsonObject = buildJsonObject {
        book.extra.forEach { (k, v) -> put(k, v) }
        put("entries", JsonArray(book.entries.map { e ->
            buildJsonObject {
                put("id", e.uid); put("keys", strArr(e.key)); put("secondary_keys", strArr(e.keysecondary))
                put("comment", e.comment); put("content", e.content); put("constant", e.constant)
                put("selective", e.selective); put("insertion_order", e.order); put("enabled", !e.disable)
                put("position", if (e.position == 0) "before_char" else "after_char")
                put("extensions", buildJsonObject {
                    put("position", e.position); put("exclude_recursion", e.excludeRecursion)
                    put("prevent_recursion", e.preventRecursion); put("delay_until_recursion", e.delayUntilRecursion)
                    put("display_index", e.displayIndex); put("probability", e.probability)
                    put("useProbability", e.useProbability); put("depth", e.depth); put("selectiveLogic", e.selectiveLogic)
                    put("outlet_name", e.outletName); put("group", e.group); put("group_override", e.groupOverride)
                    put("group_weight", e.groupWeight); put("scan_depth", nInt(e.scanDepth))
                    put("case_sensitive", nBool(e.caseSensitive)); put("match_whole_words", nBool(e.matchWholeWords))
                    put("use_group_scoring", nBool(e.useGroupScoring)); put("role", e.role)
                    put("sticky", nInt(e.sticky)); put("cooldown", nInt(e.cooldown)); put("delay", nInt(e.delay))
                    put("match_persona_description", e.matchPersonaDescription)
                    put("match_character_description", e.matchCharacterDescription)
                    put("match_character_personality", e.matchCharacterPersonality)
                    put("match_character_depth_prompt", e.matchCharacterDepthPrompt)
                    put("match_scenario", e.matchScenario); put("match_creator_notes", e.matchCreatorNotes)
                    put("triggers", strArr(e.triggers)); put("ignore_budget", e.ignoreBudget)
                })
            }
        }))
    }
}
