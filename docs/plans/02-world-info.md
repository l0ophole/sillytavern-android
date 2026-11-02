# World Info (feature set 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lorebooks compatible with SillyTavern's World Info: keyed/regex/constant entries, secondary-key logic, recursion, scan depth, token budget, inclusion groups, probability, timed effects, all insertion positions, global/character/chat-attached books, ST JSON + embedded `character_book` import/export.

**Architecture:** Pure-Kotlin `worldinfo/` package. `WorldBook`/`WorldEntry` models (ST file format, lossless) → `WorldInfoScanner` (a port of `checkWorldInfo`: takes books + chat text + settings, returns a `WorldInfoResult` with entries grouped by position) → `WorldInfoAssembler` (turns the result into strings/injections). `WorldBookRepository` stores books as files. `PromptManagerBuilder` and `PromptBuilder.buildText` consume the result through the `worldInfoBefore/After` markers, story-string vars `wiBefore/wiAfter`, depth injections and example-message anchors. `ChatController` gathers active books and runs the scan before each generation.

**Tech Stack:** Kotlin, kotlinx.serialization, JUnit4, Compose M3 (existing). No new dependencies (regex = `java.util.regex`).

**Spec:** `docs/ROADMAP.md` row 3 ("lorebooks (keys, regex, recursion, depth, budget), char-attached books, global books, import/export"). Behavior reference: `SillyTavern/public/scripts/world-info.js` (`checkWorldInfo`, `WorldInfoBuffer`, `WorldInfoTimedEffects`, `filterByInclusionGroups`, `convertCharacterBook`, `newWorldInfoEntryDefinition`) and `openai.js` (`formatWorldInfo`, `wi_format`). Format fixture: `SillyTavern/default/content/Eldoria.json`. ST repo is at `/mnt/ide/teamssd/part1/projects/sillytavern_repo/SillyTavern`.

## Global Constraints

- Package `app.sillytavern.android.worldinfo`; minSdk 26; Kotlin 2.2.20; JUnit4; no new dependencies.
- World file JSON uses ST's exact camelCase entry keys (`key`, `keysecondary`, `selectiveLogic`, `delayUntilRecursion`, …) under `{"entries": {"<uid>": {...}}}`; unknown keys on both the book and each entry are preserved on re-export.
- Books are stored as `worlds/<name>.json` under `StoragePaths.root` (ST's `worlds` dir name); book identity is its name.
- Constants copied from ST: `DEFAULT_DEPTH = 4`, `DEFAULT_WEIGHT = 100`, `MAX_SCAN_DEPTH = 1000`; positions `before=0, after=1, ANTop=2, ANBottom=3, atDepth=4, EMTop=5, EMBottom=6, outlet=7`; logic `AND_ANY=0, NOT_ALL=1, NOT_ANY=2, AND_ALL=3`; role `system=0, user=1, assistant=2`; character strategy `evenly=0, characterFirst=1, globalFirst=2`.
- ST global defaults: `depth=2, minActivations=0, minActivationsDepthMax=0, budget=25 (%), budgetCap=0, includeNames=true, recursive=false, overflowAlert=false, caseSensitive=false, matchWholeWords=false, useGroupScoring=false, characterStrategy=1, maxRecursionSteps=0`.
- Order of entries is by `order` **descending** (ST `sortFn`); budget is `round(budget% * maxContext / 100)` (min 1), capped by `budgetCap` when > 0.
- One commit per task; update `docs/WORKLOG.md` and ROADMAP at the end; stop and ask the user to confirm before starting set 4.
- Existing 88 tests keep passing.
- Out of scope: vector/`vectorized` matching (set 10, stored but ignored), `@@decorators` besides `@@activate`/`@@dont_activate`, persona-attached lorebook, Author's Note anchors (`ANTop/ANBottom` entries are collected but emitted nowhere until set 7), group-chat character filters (set 6), slash commands (set 5), regex-script processing of WI content (set 5).

## Review Focus

- Regex key with invalid syntax (`/[unclosed/i`) or catastrophic pattern: treated as no-match, never throws; a plain key that merely starts with `/` (e.g. `/dev/null`) still matches as text.
- Entry with empty `key` and not constant: never activates; entry with blank/whitespace-only key strings: ignored, not matched against everything.
- Recursion loop (entry A's content contains a key of B and vice versa): terminates; `maxRecursionSteps` and the budget also stop it.
- Budget smaller than the first entry: ST activates nothing beyond the overflow rule; `ignoreBudget` entries still go in; empty-content entries never reach the prompt.
- Chat shorter than scan depth / empty chat / depth 0: no crash; constants still fire on an empty chat.
- Book file with `entries` as a JSON array (some exporters), numeric-string vs int `uid`, `delayUntilRecursion` as `true`/`false`/number, `position` missing: imports without error.
- Same book attached as global *and* to the character: entries counted once.
- Imported card whose `character_book` has no `extensions` on entries: defaults apply (`position` from `before_char`/`after_char`).

---

### Task 1: Models and ST JSON round-trip

**Files:**
- Create: `app/src/main/java/app/sillytavern/android/worldinfo/WorldModels.kt`, `WorldJson.kt`
- Create: `app/src/test/resources/worldinfo/Eldoria.json` (copy of ST fixture)
- Test: `app/src/test/java/app/sillytavern/android/worldinfo/WorldJsonTest.kt`

**Interfaces:**
- Produces: `data class WorldEntry(uid: Int, key: List<String>, keysecondary: List<String>, comment: String, content: String, constant: Boolean, selective: Boolean = true, selectiveLogic: Int = 0, order: Int = 100, position: Int = 0, disable: Boolean, ignoreBudget: Boolean, excludeRecursion: Boolean, preventRecursion: Boolean, delayUntilRecursion: Int = 0, probability: Int = 100, useProbability: Boolean = true, depth: Int = 4, outletName: String, group: String, groupOverride: Boolean, groupWeight: Int = 100, scanDepth: Int?, caseSensitive: Boolean?, matchWholeWords: Boolean?, useGroupScoring: Boolean?, role: Int = 0, sticky: Int?, cooldown: Int?, delay: Int?, matchPersonaDescription / matchCharacterDescription / matchCharacterPersonality / matchCharacterDepthPrompt / matchScenario / matchCreatorNotes: Boolean, triggers: List<String>, characterFilterNames: List<String>, characterFilterTags: List<String>, characterFilterExclude: Boolean, displayIndex: Int, extra: JsonObject)`.
- Produces: `data class WorldBook(name: String, entries: List<WorldEntry>, extra: JsonObject)`.
- Produces: `object WorldJson { fun decode(name: String, text: String): WorldBook; fun encode(book: WorldBook): String; fun fromCharacterBook(name: String, characterBook: JsonObject): WorldBook; fun toCharacterBook(book: WorldBook): JsonObject }`.
- Decode accepts `entries` as object or array; `delayUntilRecursion` `true`→1, `false`→0; `characterFilter: {isExclude, names, tags}` maps to the three filter fields; unknown per-entry keys go to `extra` and are re-emitted.
- `fromCharacterBook` mirrors ST `convertCharacterBook` (see source): `keys→key`, `secondary_keys→keysecondary`, `insertion_order→order`, `enabled→!disable`, `position` `before_char`→0 else 1 unless `extensions.position` set, `extensions.*` snake_case keys mapped as in ST; `toCharacterBook` is the inverse (ST `convertWorldInfoToCharacterBook`).

- [ ] **Step 1: Write failing tests** in `WorldJsonTest`: `eldoriaDecodes` (entry count equals the fixture's `entries` size; first entry's `key` non-empty, `content` non-blank), `unknownKeysSurviveRoundTrip` (decode→encode→parse both; every original entry key present with equal value), `entriesArrayAccepted`, `delayUntilRecursionBoolAndNumber` (`true`→1, `false`→0, `3`→3), `missingFieldsGetDefaults` (`{"entries":{"0":{"key":["a"],"content":"x"}}}` → order 100, depth 4, probability 100, selective true, position 0), `characterBookConverts` (`keys:["a"], insertion_order:7, enabled:false, position:"after_char"` → `key==["a"], order==7, disable==true, position==1`), `characterBookRoundTrip` (`toCharacterBook(fromCharacterBook(x))` keeps keys/content/order/enabled).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*WorldJsonTest'` — expect FAIL (classes missing).
- [ ] **Step 3: Implement** models and `WorldJson` using manual `JsonObject` mapping (not generated serializers) so unknown keys and the bool/number quirks are handled in one place.
- [ ] **Step 4: Run** the same command — expect PASS.
- [ ] **Step 5: Commit** `feat(worldinfo): ST-format world book models and JSON/character_book conversion`

### Task 2: Key matching and scan buffer

**Files:**
- Create: `app/src/main/java/app/sillytavern/android/worldinfo/KeyMatcher.kt`, `ScanBuffer.kt`
- Test: `app/src/test/java/app/sillytavern/android/worldinfo/KeyMatcherTest.kt`

**Interfaces:**
- Consumes: `WorldEntry` (Task 1).
- Produces: `data class WorldInfoSettings(depth: Int = 2, minActivations: Int = 0, minActivationsDepthMax: Int = 0, budget: Int = 25, budgetCap: Int = 0, includeNames: Boolean = true, recursive: Boolean = false, overflowAlert: Boolean = false, caseSensitive: Boolean = false, matchWholeWords: Boolean = false, useGroupScoring: Boolean = false, characterStrategy: Int = 1, maxRecursionSteps: Int = 0)` (`@Serializable`, in `WorldModels.kt`).
- Produces: `object KeyMatcher { fun parseRegex(key: String): Regex?; fun matches(haystack: String, key: String, entry: WorldEntry, settings: WorldInfoSettings): Boolean }`. `parseRegex` accepts `/pattern/flags` with flags from `gimsuy` (map `i,m,s` to Kotlin options, ignore the others), rejects patterns with an unescaped inner `/`, returns null on invalid syntax. Regex keys ignore case/whole-word options. Plain keys: lowercase both sides unless `entry.caseSensitive ?: settings.caseSensitive`; whole-word (`entry.matchWholeWords ?: settings.matchWholeWords`): single-word key uses `(?:^|\W)key(?:$|\W)`, multi-word key falls back to `contains`.
- Produces: `class ScanBuffer(messagesNewestFirst: List<String>, global: GlobalScanData, settings: WorldInfoSettings)` with `fun text(entry: WorldEntry, state: ScanState): String`, `fun addRecurse(text: String)`, `fun hasRecurse(): Boolean`, `fun advance()`, `fun depth(): Int`, plus `enum class ScanState { INITIAL, RECURSION, MIN_ACTIVATIONS }` and `data class GlobalScanData(personaDescription, characterDescription, characterPersonality, characterDepthPrompt, scenario, creatorNotes: String = "", trigger: String = "normal")`. `text` = newest `entry.scanDepth ?: depth()` messages (clamped to `MAX_SCAN_DEPTH`, `0` → empty) joined by `\n`, each prefixed with `\u0001` (so `^` in regex keys anchors per message), plus the `match*` global fields the entry opts into, plus the recursion buffer (omitted in `MIN_ACTIVATIONS` state).

- [ ] **Step 1: Write failing tests** in `KeyMatcherTest`: `plainCaseInsensitive` (`"Dragon"` matches `"a dragon"`), `caseSensitiveEntryOverridesSetting`, `wholeWordSingle` (`"cat"` not in `"concatenate"`, is in `"a cat."`), `wholeWordMultiWordUsesContains`, `regexKey` (`/dr.g[oa]n/i` matches `"DRAGON"`), `regexAnchorPerMessage` (buffer of two messages, key `/^hello/` matches second message start), `invalidRegexIsNoMatch` (`/[unclosed/` → false, no throw), `slashKeyWithInnerSlashIsPlain` (`"/dev/null"` matches text containing it), `scanDepthLimitsMessages` (depth 1 sees only newest), `scanDepthZeroEmpty`, `entryScanDepthOverrides`, `globalFieldsOnlyWhenOptedIn`, `recurseBufferExcludedInMinActivations`.
- [ ] **Step 2: Run** `--tests '*KeyMatcherTest'` — expect FAIL.
- [ ] **Step 3: Implement** both files; wrap regex evaluation in try/catch returning false; cache compiled regexes in a small `HashMap` inside `KeyMatcher`.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `feat(worldinfo): key matcher and scan buffer`

### Task 3: Scanner core (activation, recursion, budget, probability, groups)

**Files:**
- Create: `app/src/main/java/app/sillytavern/android/worldinfo/WorldInfoScanner.kt`
- Test: `app/src/test/java/app/sillytavern/android/worldinfo/WorldInfoScannerTest.kt`

**Interfaces:**
- Consumes: Task 1 models, Task 2 `KeyMatcher`/`ScanBuffer`/`WorldInfoSettings`; `TokenCounter` (existing, `count(String): Int`).
- Produces: `data class ScanEntry(val book: String, val entry: WorldEntry)` (identity key `"$book.${entry.uid}"`).
- Produces: `class WorldInfoScanner(settings: WorldInfoSettings, counter: TokenCounter, random: kotlin.random.Random = Random.Default)` with `fun scan(entries: List<ScanEntry>, chatNewestFirst: List<String>, maxContext: Int, global: GlobalScanData, timed: TimedState = TimedState()): ScanResult`; `data class ScanResult(val activated: List<ScanEntry>, val timed: TimedState)` where `activated` is in activation order. `TimedState` is defined in Task 5 as an empty-default class stub here (`class TimedState`), so this task compiles; Task 5 fills it.
- Behavior, in this order inside each loop (port of `checkWorldInfo`): skip disabled; skip when `entry.triggers` non-empty and not containing `global.trigger`; character filter is applied by the caller (Task 6), not here; `delayUntilRecursion` suppression (initial pass skips; in recursion passes entry fires only when its level ≤ current delay level, levels processed in ascending order); `excludeRecursion` skipped during recursion passes when `settings.recursive`; `@@activate` / `@@dont_activate` first-line decorators; `constant` activates; no keys → skip; primary key match, then secondary logic exactly as ST (`AND_ANY`: any secondary matches; `NOT_ALL`: some secondary absent; `NOT_ANY`: none match; `AND_ALL`: all match; secondary only considered when `selective` and non-empty); keys are macro-expanded by the caller before scan (scanner takes plain strings). After collecting candidates: sort by input order, apply inclusion groups (comma-separated `group` names; already-activated group → drop all others; `groupOverride` highest-`order` wins; else weighted random on `groupWeight`; group scoring when `entry.useGroupScoring ?: settings.useGroupScoring` keeps only top-scoring entries by number of matched keys per ST `getScore`), then probability (`useProbability && probability < 100` rolls `random.nextDouble()*100 <= probability`; failures are remembered and never retried), then budget (cumulative token count of activated content + new content ≥ budget → overflow; non-`ignoreBudget` entries after overflow dropped; `ignoreBudget` entries still pass). Recursion: when `settings.recursive`, no overflow, and new entries without `preventRecursion` exist, their content joins the recurse buffer and another pass runs; stops at `maxRecursionSteps` (>0). Min activations (only when `maxRecursionSteps == 0`): while activated count < `minActivations` and not overflowed, `buffer.advance()` and rescan until depth exceeds `minActivationsDepthMax` (>0) or the chat length.
- Content with blank text is still "activated" (counted) but filtered at assembly (Task 4).

- [ ] **Step 1: Write failing tests** in `WorldInfoScannerTest` (helper `entry(keys, content, ...)`, `ApproxTokenCounter`): `constantFiresOnEmptyChat`, `primaryKeyActivates`, `noKeysNeverActivates`, `blankKeyIgnored` (`key=[" "]` never activates), `secondaryAndAny`, `secondaryNotAll`, `secondaryNotAny`, `secondaryAndAll`, `disabledSkipped`, `triggerFilter` (`triggers=["continue"]`, scan with `trigger="normal"` → none), `recursionChainsEntries` (A content contains B's key; `recursive=true` activates both, `false` only A), `recursionLoopTerminates` (A↔B, completes with both active), `preventRecursionStopsChain`, `excludeRecursionSkippedOnRecursivePass`, `delayUntilRecursionWaits` (level 1 fires only after another entry activated), `maxRecursionStepsLimits`, `minActivationsDeepensScan` (key only in message 3, depth 2, minActivations 1 → activates), `groupWeightPicksOne` (seeded `Random`; exactly one of the group), `groupOverrideWins`, `groupAlreadyActivatedBlocksOthers`, `groupScoringKeepsBest`, `probabilityZeroNeverFires` (`probability=0, useProbability=true`), `probabilityFailureNotRetriedInRecursion`, `budgetStopsLaterEntries` (maxContext small), `ignoreBudgetStillAdded`, `budgetCapApplies`, `inputOrderPreservedInActivated` (activated list follows the input list order; `order`-based sorting is applied by the caller/assembler), `scanDepthZeroChatStillConstants`.
- [ ] **Step 2: Run** `--tests '*WorldInfoScannerTest'` — expect FAIL.
- [ ] **Step 3: Implement** `WorldInfoScanner` following the loop above; keep helpers (`filterGroups`, `score`, `budgetOf`) private in the same file.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `feat(worldinfo): scanner with recursion, groups, probability, budget`

### Task 4: Assembler and prompt integration

**Files:**
- Create: `app/src/main/java/app/sillytavern/android/worldinfo/WorldInfoAssembler.kt`
- Modify: `prompt/PromptBuilder.kt` (`PromptInput` + `buildText`), `prompt/PromptManagerBuilder.kt`, `chat/Macros.kt` (`{{outlet::name}}`), `model/PromptManagerPreset.kt` only if `wiFormat` is not already present
- Test: `app/src/test/java/app/sillytavern/android/worldinfo/WorldInfoAssemblerTest.kt`, extend `PromptManagerBuilderTest.kt`, `PromptTest.kt`

**Interfaces:**
- Consumes: `ScanResult` (Task 3).
- Produces: `data class WorldInfoPrompt(val before: String = "", val after: String = "", val depth: List<DepthInjection> = emptyList(), val exampleTop: List<String> = emptyList(), val exampleBottom: List<String> = emptyList(), val noteTop: List<String> = emptyList(), val noteBottom: List<String> = emptyList(), val outlets: Map<String, String> = emptyMap())`; `data class DepthInjection(val depth: Int, val role: Int, val content: String)`.
- Produces: `object WorldInfoAssembler { fun assemble(activated: List<ScanEntry>, expand: (String) -> String): WorldInfoPrompt }` — sorts by `order` descending then, per ST, builds each bucket by `unshift` (so within a bucket **lowest order ends up first**; replicate exactly: iterate sorted-descending and prepend), joins with `\n`; macro-expands content with `expand`; skips blank content; `atDepth` entries grouped by `(depth, role)`; `outlet` entries without `outletName` skipped.
- Produces: `PromptInput.worldInfo: WorldInfoPrompt = WorldInfoPrompt()`.
- Integration: `worldInfoBefore`/`worldInfoAfter` marker content = `pm.wiFormat.replace("{0}", text)` (default `"{0}"`, blank text → no message); text-completion `wiBefore`/`wiAfter` story-string vars get the same; depth injections are merged into the existing absolute-injection path (role from entry `role`, ordered after Prompt Manager injections at the same depth, joined with `\n`); `exampleTop/Bottom` are prepended/appended to the `dialogueExamples` content (and example blocks in text mode); `{{outlet::name}}` in any prompt text expands from `outlets` (unknown → empty).

- [ ] **Step 1: Write failing tests**: `AssemblerTest.beforeAfterJoinedInEmissionOrder` (orders 10,20 in `before` → content of order 20 appears **first**? Assert against ST: sorted desc [20,10], unshift each → `[10,20]`, joined `"c10\nc20"`), `depthGroupedByDepthAndRole`, `blankContentSkipped`, `outletWithoutNameSkipped`, `macrosExpanded` (`{{char}}`); `PromptManagerBuilderTest.worldInfoMarkersFilled` (before text appears between `main` and `charDescription` in default preset order, after text after `charDescription`/before examples), `worldInfoDepthInjectedAtDepth` (depth 1 role system lands before last history message), `wiFormatApplied` (`wiFormat = "[Lore]\n{0}"`), `emptyWorldInfoChangesNothing` (existing expectations unchanged); `PromptTest.textCompletionStoryStringGetsWiVars`; `MacrosTest`-style `outletMacroExpands`.
- [ ] **Step 2: Run** `--tests '*WorldInfoAssemblerTest' --tests '*PromptManagerBuilderTest' --tests '*PromptTest'` — expect FAIL.
- [ ] **Step 3: Implement** assembler and wire into `PromptInput`, `PromptManagerBuilder.content()` (`worldInfoBefore`/`worldInfoAfter` cases), `PromptBuilder.buildText` vars/examples, and `Macros` (extend `MacroContext` with `outlets: Map<String,String> = emptyMap()`; `PromptSupport.macroContext` fills it).
- [ ] **Step 4: Run** full suite `./gradlew :app:testDebugUnitTest` — expect PASS (88 prior + new).
- [ ] **Step 5: Commit** `feat(worldinfo): assemble activated entries into prompts`

### Task 5: Timed effects (sticky / cooldown / delay)

**Files:**
- Create: `app/src/main/java/app/sillytavern/android/worldinfo/TimedEffects.kt`
- Modify: `WorldInfoScanner.kt` (use `TimedState`), `chat/ChatRepository.kt` (persist in chat header `chat_metadata.timedWorldInfo`, same key as ST)
- Test: `app/src/test/java/app/sillytavern/android/worldinfo/TimedEffectsTest.kt`

**Interfaces:**
- Produces: `class TimedState(val sticky: Map<String, Effect> = emptyMap(), val cooldown: Map<String, Effect> = emptyMap())` with `data class Effect(start: Int, end: Int)` (message indexes, as ST `timedWorldInfo`); `fun toJson(): JsonObject`, `companion fun fromJson(JsonObject?): TimedState`. `object TimedEffects { fun advance(state: TimedState, entries: List<ScanEntry>, chatLength: Int): TimedState; fun record(state: TimedState, activated: List<ScanEntry>, chatLength: Int): TimedState; fun isActive(kind, entry, state, chatLength): Boolean }` — semantics from `WorldInfoTimedEffects`: `sticky=N` keeps entry active (bypassing key checks and probability re-roll) for N messages after activation, then starts `cooldown` (if set); `cooldown=N` suppresses re-activation for N messages; `delay=N` suppresses until chat has ≥ N messages. `ScanResult.timed` returns the updated state; scans flagged dry-run (swipe/regenerate re-scan of the same message index) must not advance state twice — `scan(..., dryRun = false)` parameter, state only returned changed when `!dryRun`.

- [ ] **Step 1: Write failing tests**: `delaySuppressesEarlyChat` (`delay=3`, chat length 2 → inactive; 3 → can fire), `stickyKeepsActiveWithoutKeys` (fires at msg 5, `sticky=2`, no key in msgs 6–7 still active, gone at 8), `cooldownSuppressesAfterSticky`, `cooldownAloneSuppresses`, `stickySkipsProbabilityReroll`, `dryRunDoesNotAdvance`, `jsonRoundTrip`, `corruptJsonGivesEmptyState`.
- [ ] **Step 2: Run** `--tests '*TimedEffectsTest'` — expect FAIL.
- [ ] **Step 3: Implement** `TimedEffects`/`TimedState`; extend the scanner's per-entry checks (`isSticky`, `isCooldown && !isSticky`, `isDelay`) in the position given by the ST loop; persist state via `Chat.header`.
- [ ] **Step 4: Run** full suite — expect PASS.
- [ ] **Step 5: Commit** `feat(worldinfo): sticky/cooldown/delay timed effects`

### Task 6: Repository, settings, and source resolution

**Files:**
- Create: `worldinfo/WorldBookRepository.kt`, `worldinfo/WorldInfoSources.kt`
- Modify: `data/StoragePaths.kt` (add `worlds`), `data/Settings.kt`, `App.kt` (`AppContainer.worlds`)
- Test: `app/src/test/java/app/sillytavern/android/worldinfo/WorldRepositoryTest.kt`, `data/SettingsMigrationTest.kt` (extend)

**Interfaces:**
- Produces: `class WorldBookRepository(paths: StoragePaths)` — `list(): List<String>`, `load(name): WorldBook?`, `save(book)` (atomic write), `delete(name)`, `import(fileName: String, text: String): Result<String>` (accepts world JSON **or** a character card JSON/`character_book` wrapper; name from file name, collisions replace), `export(name): String?`. Sanitize names with `StoragePaths.sanitizeFileName`.
- Produces: `AppSettings.worldInfo: WorldInfoSettings = WorldInfoSettings()`, `AppSettings.globalWorldBooks: List<String> = emptyList()`, `AppSettings.characterExtraBooks: Map<String, List<String>> = emptyMap()` (keyed by character file name, as ST `charLore`); `PromptManagerPreset.wiFormat` already exists.
- Produces: `object WorldInfoSources { fun resolve(card: CharacterCard, characterFileName: String, chatBook: String?, settings: AppSettings, repo: WorldBookRepository): List<ScanEntry> }` — order per ST `getSortedEntries`: chat book first, then per `characterStrategy` (global/character/evenly) with each group sorted by `order` descending; character books = `card.raw.extensions.world` plus `characterExtraBooks[characterFileName]`, plus the card's embedded `character_book` (converted with `fromCharacterBook`, name `"<char> (embedded)"`); a book already present as global (or chat) is skipped for the character set; missing/unreadable books are skipped silently. Entry `characterFilter` names/tags are applied here against `characterFileName` and `card.tags` (`isExclude` inverts). Key strings are NOT expanded here.

- [ ] **Step 1: Write failing tests**: `saveLoadRoundTrip`, `importWorldJson`, `importCardWithCharacterBook` (extracts book, name from card), `importGarbageFails`, `importSameNameReplaces`, `deleteRemoves`, `fileNamesSanitized` (name `../x` stays inside `worlds`), `resolveDedupesGlobalAndCharacter`, `resolveStrategyOrders` (characterFirst vs globalFirst vs evenly by order), `embeddedCharacterBookIncluded`, `characterFilterByNameAndExclude`, `chatBookFirst`, `missingBookSkipped`; `SettingsMigrationTest.oldSettingsGetWorldInfoDefaults` (settings JSON without the new fields loads with ST defaults).
- [ ] **Step 2: Run** `--tests '*WorldRepositoryTest' --tests '*SettingsMigrationTest'` — expect FAIL.
- [ ] **Step 3: Implement** repository, sources, settings fields (all defaulted), `AppContainer.worlds`.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `feat(worldinfo): world book storage, settings, and source resolution`

### Task 7: ChatController wiring

**Files:**
- Create: `worldinfo/WorldInfoRunner.kt`
- Modify: `chat/ChatController.kt`, `App.kt`, `prompt/PromptBuilder.kt` (none expected)
- Test: `app/src/test/java/app/sillytavern/android/chat/ChatControllerTest.kt` (extend), `worldinfo/WorldInfoRunnerTest.kt`

**Interfaces:**
- Produces: `object WorldInfoRunner { fun run(entries: List<ScanEntry>, history: List<ChatMessage>, input: PromptInput, settings: WorldInfoSettings, mode: GenerationMode, timed: TimedState, dryRun: Boolean): Pair<WorldInfoPrompt, TimedState> }` — builds `chatNewestFirst` from non-system messages (`includeNames` ⇒ `"Name: text"`, using persona/char names), global data from `persona.description`, card description/personality/`depth_prompt`/scenario/creator notes, trigger string from mode (`normal`, `continue`, `impersonate`; regenerate/swipe use `normal`), macro-expands key strings and content via `Macros`, calls `WorldInfoScanner` with `maxContext = profile.sampler.maxContextTokens`, then `WorldInfoAssembler`.
- `ChatController.runGeneration` resolves sources (`WorldInfoSources.resolve`), runs the scanner on `history` (excluding the empty target message), stores the returned `TimedState` in the chat header unless `dryRun` (regenerate/swipe/continue of an existing message index are dry-run), and passes `worldInfo` into `PromptInput`. WI token cost is subtracted from the history budget automatically because the injected text is part of the fixed prompt parts.
- `ChatController` gains constructor param `worlds: () -> WorldBookRepository` (test default: empty temp repository).

- [ ] **Step 1: Write failing tests**: `RunnerTest.includeNamesPrefixesScanText` (key `"Alice: hello"` matches only when includeNames), `RunnerTest.keysMacroExpanded` (`{{char}}` key), `RunnerTest.triggerContinue`, `ControllerTest.worldInfoReachesRequest` (global book with a keyed entry, user message containing key → the captured request's messages include the entry content), `ControllerTest.noBooksNoChange`, `ControllerTest.regenerateDoesNotAdvanceTimedState`, `ControllerTest.timedStatePersistedInChatFile`.
- [ ] **Step 2: Run** `--tests '*WorldInfoRunnerTest' --tests '*ChatControllerTest'` — expect FAIL.
- [ ] **Step 3: Implement** runner and controller wiring.
- [ ] **Step 4: Run** full suite `./gradlew :app:testDebugUnitTest` — expect PASS.
- [ ] **Step 5: Commit** `feat(chat): run world info scan before generation`

### Task 8: UI (book list, entry editor, attachments, settings)

**Files:**
- Create: `ui/WorldInfoScreen.kt`, `ui/WorldEntryEditor.kt`
- Modify: `MainActivity.kt` (routes `worlds`, `worlds/{name}`), `ui/SettingsScreen.kt` (entry point + WI global settings section), `ui/CharacterEditScreen.kt` (attach book to character: sets `extensions.world`; "import embedded lorebook as world book" action), `ui/ChatScreen.kt` (overflow action: chat-bound book picker, stored in chat header `chat_metadata.world_info`)
- Test: `app/src/test/java/app/sillytavern/android/worldinfo/WorldEditingTest.kt` (pure helpers only)

**Interfaces:**
- Produces: pure helpers in `worldinfo/WorldEditing.kt`: `fun WorldBook.addEntry(): WorldBook` (next free uid, ST defaults), `fun WorldBook.updateEntry(WorldEntry): WorldBook`, `fun WorldBook.deleteEntry(uid: Int): WorldBook`, `fun WorldBook.duplicateEntry(uid: Int): WorldBook`, `fun WorldBook.moveEntry(uid: Int, delta: Int)`, `fun parseKeys(text: String): List<String>` (comma-separated, trims, drops blanks, respects commas inside `/regex/` keys), `fun formatKeys(keys: List<String>): String`.
- UI: book list (create, rename via duplicate+delete, import via SAF file picker, export via SAF create-document, delete with confirm, toggle "global active" per book), book view (searchable entry list showing comment/keys, enabled switch, constant indicator, position and order), entry editor (comment, content, primary keys, secondary keys + logic, constant, selective, position + depth + role + outlet name, order, probability, scan depth, case/whole-word overrides (tri-state), recursion flags, group + override + weight, sticky/cooldown/delay, triggers, ignore-budget, `match*` flags), global WI settings (all `WorldInfoSettings` fields with ST labels), character strategy dropdown.

- [ ] **Step 1: Write failing tests** in `WorldEditingTest`: `addEntryUsesNextFreeUid`, `duplicateKeepsContentNewUid`, `deleteRemoves`, `moveClamps`, `parseKeysTrimsAndDropsBlank` (`"a, b ,,c"` → `[a,b,c]`), `parseKeysKeepsCommaInsideRegex` (`"/a,b/i, c"` → `["/a,b/i","c"]`), `formatKeysRoundTrip`.
- [ ] **Step 2: Run** `--tests '*WorldEditingTest'` — expect FAIL.
- [ ] **Step 3: Implement** helpers, then the screens/routes (UI is compile- and lint-checked only; it is not run on a device).
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` — expect tests PASS, lint no errors, APK built.
- [ ] **Step 5: Commit** `feat(ui): world info screens and attachments`

### Task 9: Bundled content, docs, final verification

**Files:**
- Modify: `docs/WORKLOG.md`, `docs/ROADMAP.md` (mark set 3 DONE)
- Test: `app/src/test/java/app/sillytavern/android/worldinfo/EndToEndTest.kt`

- [ ] **Step 1: Write failing end-to-end test** `eldoriaActivatesThroughPromptBuilder`: load `Eldoria.json`, pick a non-constant entry's first key, build a `PromptInput` with a user message containing that key, run `WorldInfoRunner` then `PromptManagerBuilder.build`; assert the entry's content is in the output exactly once and a message without any key does not contain it.
- [ ] **Step 2: Run** `--tests '*EndToEndTest'` — expect FAIL until wiring is complete; fix any integration gaps found.
- [ ] **Step 3: Run** `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` — expect all pass; record the total test count.
- [ ] **Step 4: Update** WORKLOG (done list, rulings/deviations, "not verified": UI not run on device, no comparison against real ST output for the same inputs) and ROADMAP.
- [ ] **Step 5: Commit** `docs: work log and roadmap for set 3 (world info)`
