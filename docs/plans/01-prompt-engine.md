# Prompt Engine (feature set 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the MVP's fixed prompt assembly with SillyTavern's prompt engine: instruct/context templates, system-prompt presets, a full Prompt Manager (ordering, toggles, injection depth), and text-completion sampler presets, all importable/exportable in SillyTavern's JSON formats.

**Architecture:** New ST-format-compatible data classes (kotlinx.serialization, unknown keys ignored) live in `prompt/model/`. Pure-Kotlin engines (`StoryStringRenderer`, `InstructFormatter`, `PromptManagerBuilder`) turn cards + persona + history + presets into either `List<PromptMessage>` (chat completion) or a single text prompt (text completion). `PromptBuilder` becomes a thin dispatcher; `ChatController` and `Backends` consume its output. Presets are stored in `AppSettings`, with migration of the MVP `PromptPreset`.

**Tech Stack:** Kotlin, kotlinx.serialization, JUnit4, MockWebServer, Compose M3 (existing).

**Spec:** `docs/ROADMAP.md` row 2 ("instruct/context templates, system-prompt presets, full Prompt Manager (ordering, toggles, injection depth), text-completion samplers/presets import/export"); behavior reference is `SillyTavern/public/scripts/instruct-mode.js`, `power-user.js` (story string), `openai.js` (prompt manager), `PromptManager.js`; format reference is `SillyTavern/default/content/presets/{instruct,context,sysprompt,openai,textgen}`.

## Global Constraints

- Package root `app.sillytavern.android`; minSdk 26; Kotlin 2.2.20; JUnit4 (existing test setup), no new dependencies.
- Preset JSON keys are SillyTavern's exact snake_case names (e.g. `input_sequence`, `story_string`, `prompt_order`); unknown keys are preserved on re-export, never dropped.
- Storage stays file/JSON based in app-private storage; no Room.
- One commit per task; update `docs/WORKLOG.md` at the end; stop and ask the user to confirm before starting set 3.
- Existing 35 tests keep passing (those that assert old `PromptBuilder` behavior are updated in the task that changes the behavior).
- Out of scope (later sets): World Info content (markers `worldInfoBefore/After` render empty until set 3), group chats, slash commands, extra backends.

## Review Focus

- Instruct template with all-empty sequences (e.g. `Default.json`/blank): output must still be a valid prompt, no `null`/stray `\n`.
- Story string with unknown `{{#if foo}}` variable or unbalanced `{{/if}}`: renders without throwing; unknown vars are empty.
- Prompt Manager order referencing an identifier with no prompt definition (imported preset drift): skipped, no crash.
- Injection depth larger than chat length, and depth 0: inserted at start / end respectively, never index errors.
- Context trimming when a single enabled prompt exceeds the whole budget: chat history still keeps the last message.
- Legacy `settings.json` containing only the MVP `PromptPreset` list: loads and migrates, no lost text.

---

### Task 1: ST-format preset models and JSON round-trip

**Files:**
- Create: `app/src/main/java/app/sillytavern/android/prompt/model/InstructTemplate.kt`, `ContextTemplate.kt`, `SystemPromptPreset.kt`, `PromptManagerPreset.kt`, `SamplerPreset.kt`, `PresetJson.kt`
- Create: `app/src/test/resources/presets/` (copies of `ChatML` instruct+context, `Roleplay - Simple` sysprompt, openai `Default`, textgen `Universal-Creative`)
- Test: `app/src/test/java/app/sillytavern/android/prompt/PresetJsonTest.kt`

**Interfaces:**
- Produces: `@Serializable data class InstructTemplate(name, inputSequence, outputSequence, systemSequence, lastOutputSequence, firstOutputSequence, firstInputSequence, lastInputSequence, lastSystemSequence, inputSuffix, outputSuffix, systemSuffix, stopSequence, wrap: Boolean, macro: Boolean, namesBehavior: String, sequencesAsStopStrings: Boolean, systemSameAsUser: Boolean, skipExamples: Boolean, storyStringPrefix, storyStringSuffix, userAlignmentMessage, activationRegex)` with `@SerialName` snake_case keys and ST defaults.
- Produces: `ContextTemplate(name, storyString, exampleSeparator, chatStart, useStopStrings, namesAsStopStrings, storyStringPosition: Int, storyStringDepth: Int, storyStringRole: Int, alwaysForceName2, trimSentences, singleLine)`.
- Produces: `SystemPromptPreset(name, content, postHistory)`.
- Produces: `PromptDefinition(identifier, name, role: String = "system", content: String = "", systemPrompt: Boolean, marker: Boolean, injectionPosition: Int = 0, injectionDepth: Int = 4, injectionOrder: Int = 100, forbidOverrides: Boolean)`, `PromptOrderEntry(identifier, enabled)`, `PromptManagerPreset(name, prompts: List<PromptDefinition>, order: List<PromptOrderEntry>, impersonationPrompt, newChatPrompt, newExampleChatPrompt, continueNudgePrompt, groupNudgePrompt, sendIfEmpty, namesBehavior: Int, squashSystemMessages: Boolean, scenarioFormat, personalityFormat, wiFormat)`. Import picks the `prompt_order` entry with `character_id == 100001` (falling back to 100000, then first).
- Produces: `TextGenSamplerPreset(name, temp, topP, topK, topA, tfs, typicalP, minP, repPen, repPenRange, freqPen, presencePen, dryMultiplier, dryBase, dryAllowedLength, dryPenaltyLastN, drySequenceBreakers: String, mirostatMode, mirostatTau, mirostatEta, smoothingFactor, smoothingCurve, xtcThreshold, xtcProbability, nsigma, dynatemp, minTemp, maxTemp, dynatempExponent, banEosToken, skipSpecialTokens, seed, grammarString, negativePrompt, guidanceScale, bannedTokens)`.
- Produces: `PresetJson.encode/decode` for each type: `fun <T> decode(serializer, text): T`, `fun <T> encode(serializer, value, extra: JsonObject): String`. Each model keeps unknown keys in a `val extra: JsonObject = JsonObject(emptyMap())` populated via a `JsonTransformingSerializer`, so re-export is lossless.

- [ ] **Step 1: Write failing tests** in `PresetJsonTest`: `instructChatMlDecodes` (assert `inputSequence == "<|im_start|>user"`, `namesBehavior == "force"`, `outputSuffix == "<|im_end|>\n"`), `contextChatMlDecodes` (storyString contains `{{#if description}}`), `sysPromptDecodes`, `openAiPresetPicksOrder100001` (order starts with `main`, contains `chatHistory`; `prompts` has a `marker=true` entry `chatHistory`), `textGenDecodes` (`minP == 0.1`, `temp == 1.5`), `unknownKeysSurviveRoundTrip` (decode openai Default, encode, parse both as JSON; every original top-level key present with equal value).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*PresetJsonTest'` — expect FAIL (classes missing).
- [ ] **Step 3: Implement** the models and `PresetJson` as specified above; all fields defaulted so partial files decode.
- [ ] **Step 4: Run** the same command — expect PASS.
- [ ] **Step 5: Commit** `feat(prompt): ST-format instruct/context/sysprompt/prompt-manager/sampler preset models`

### Task 2: Story-string renderer (context template)

**Files:**
- Create: `app/src/main/java/app/sillytavern/android/prompt/StoryStringRenderer.kt`
- Test: `app/src/test/java/app/sillytavern/android/prompt/StoryStringRendererTest.kt`

**Interfaces:**
- Produces: `object StoryStringRenderer { fun render(template: String, vars: Map<String, String>): String }` — supports `{{#if name}}…{{/if}}` (non-nested-safe: nesting supported via a small recursive scan; truthy = non-blank value), `{{name}}` substitution (missing → empty), and `{{trim}}` which removes itself and all adjacent whitespace/newlines on both sides. Variables used by ST: `system, description, personality, scenario, persona, wiBefore, wiAfter, anchorBefore, anchorAfter, mesExamples, mesExamplesRaw, char, user`.

- [ ] **Step 1: Write failing tests**: `ifBlockOmittedWhenBlank`, `ifBlockKeptWhenSet` (`"{{#if a}}A:{{a}}\n{{/if}}x"` with a="1" → `"A:1\nx"`), `trimEatsSurroundingNewlines` (`"a\n\n{{trim}}\n\nb"` → `"ab"`), `unknownVarIsEmpty`, `unbalancedEndIfDoesNotThrow`, `chatMlDefaultStoryString` (render ChatML story string from test resource with description="D", persona="P" → `"D\nP"`).
- [ ] **Step 2: Run** `--tests '*StoryStringRendererTest'` — expect FAIL.
- [ ] **Step 3: Implement** `render`; unbalanced tags are emitted literally rather than throwing.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `feat(prompt): context-template story string renderer`

### Task 3: Instruct formatter (text completion)

**Files:**
- Create: `app/src/main/java/app/sillytavern/android/prompt/InstructFormatter.kt`
- Modify: `PromptBuilder.kt` (remove `toTextPrompt`; kept until Task 5 wires callers, so mark `@Deprecated` here)
- Test: `app/src/test/java/app/sillytavern/android/prompt/InstructFormatterTest.kt`

**Interfaces:**
- Consumes: Task 1 models, Task 2 `StoryStringRenderer`, `PromptMessage`, `Role`, `GenerationMode`.
- Produces: `class TextPromptParts(val storyVars: Map<String,String>, val exampleBlocks: List<String>, val history: List<PromptMessage>, val postHistory: String)`.
- Produces: `object InstructFormatter { fun format(parts: TextPromptParts, instruct: InstructTemplate?, context: ContextTemplate, userName: String, charName: String, mode: GenerationMode): FormattedPrompt }` and `data class FormattedPrompt(val text: String, val stopStrings: List<String>)`.
- Behavior (from `instruct-mode.js` `formatInstructModeChat`): when `instruct == null` or not `wrap`-ing, fall back to `Name: text` lines (the MVP behavior). Otherwise each message = sequence + (`names_behavior` `force`/`always` ⇒ `"Name: "` prefix; `none` ⇒ none; `remove` ⇒ none) + text + suffix; `system_same_as_user` uses the input sequence for system; `first_*`/`last_*` sequences override for the first/last message of that role; story string wrapped in `story_string_prefix`/`suffix`; `example_separator` and `chat_start` inserted between story string, examples, and history; the prompt ends with the output sequence (plus `"CharName:"` when names forced), except in CONTINUE mode where the last assistant message is left open (no suffix). `stopStrings` = instruct `stop_sequence` + (when `sequences_as_stop_strings`) input/system sequences trimmed of whitespace + (when `names_as_stop_strings`) `"\nUser:"`, deduped, blanks dropped. `macro=true` means sequences are macro-expanded (`{{char}}`/`{{user}}`) before use.

- [ ] **Step 1: Write failing tests**: `chatMlTwoTurns` (exact expected string for system story + user "Hi" + assistant "Yo" ending with `<|im_start|>assistant\nAnn:` for `force` names), `noInstructFallsBackToNameLines`, `emptySequencesProduceNoStrayNewlines`, `lastOutputSequenceUsedOnFinalPrompt` (Alpaca-style fixture built inline), `continueLeavesAssistantOpen`, `impersonateEndsWithUserTurn`, `stopStringsDeduped`, `macroInSequenceExpanded`.
- [ ] **Step 2: Run** `--tests '*InstructFormatterTest'` — expect FAIL.
- [ ] **Step 3: Implement** `InstructFormatter.format` per the behavior above.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `feat(prompt): instruct-mode formatter with stop strings`

### Task 4: Prompt Manager engine

**Files:**
- Create: `app/src/main/java/app/sillytavern/android/prompt/PromptManagerBuilder.kt`
- Test: `app/src/test/java/app/sillytavern/android/prompt/PromptManagerBuilderTest.kt`

**Interfaces:**
- Consumes: `PromptManagerPreset`, `SystemPromptPreset`, `PromptInput`-like data (card, persona, history, budgets, counter, mode).
- Produces: `object PromptManagerBuilder { fun build(i: PromptInput, pm: PromptManagerPreset, sys: SystemPromptPreset?): List<PromptMessage> }` and extends `PromptInput` with `val promptManager: PromptManagerPreset`, `val systemPrompt: SystemPromptPreset?` (defaulted so existing call sites compile).
- Behavior (from `openai.js` `populateChatCompletion`): walk `order` entries that are `enabled` and have a definition; markers `charDescription, charPersonality (uses personalityFormat), scenario (scenarioFormat), personaDescription, dialogueExamples (prefixed by newExampleChatPrompt), chatHistory, worldInfoBefore/After (empty now)` expand to content; non-marker prompts expand macros in `content`; `main` content is replaced by the sysprompt preset `content` when present, `jailbreak` by its `postHistory`, and a card's `systemPrompt`/`postHistoryInstructions` override both unless `forbidOverrides`; entries with `injectionPosition == 1` (absolute) are not placed in the walk but inserted into chat history at `injectionDepth` from the end (ties ordered by `injectionOrder`, same-depth same-role messages joined); empty content entries are skipped; impersonate/continue add their nudge prompts after history (`impersonationPrompt`, `continueNudgePrompt`); `sendIfEmpty` used when history is empty; history trimming reuses the MVP rule (newest kept, last always kept) after subtracting all other enabled prompts' tokens; `namesBehavior`/`squashSystemMessages` applied last (squash merges consecutive system messages).

- [ ] **Step 1: Write failing tests**: `orderDeterminesSequence`, `disabledEntrySkipped`, `unknownIdentifierSkipped`, `markerExpandsCardFields`, `absoluteInjectionAtDepth1PutsBeforeLastMessage`, `depthLargerThanHistoryInsertsAtStart`, `depth0AppendsAtEnd`, `cardSystemPromptOverridesMain`, `forbidOverridesBlocksCardOverride`, `oversizedPromptsStillKeepLastMessage`, `squashMergesConsecutiveSystem`, `impersonateAddsNudgeAfterHistory`.
- [ ] **Step 2: Run** `--tests '*PromptManagerBuilderTest'` — expect FAIL.
- [ ] **Step 3: Implement** `PromptManagerBuilder.build`; shared helpers (macro context, trimming) extracted from `PromptBuilder` into `PromptBuilderSupport.kt` rather than duplicated.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `feat(prompt): Prompt Manager engine with ordering, toggles, injection depth`

### Task 5: Settings integration, migration, and ChatController wiring

**Files:**
- Modify: `data/Settings.kt` (add to `AppSettings`: `promptManagerPresets`, `activePromptManagerId`, `instructTemplates`, `activeInstructId`, `contextTemplates`, `activeContextId`, `systemPrompts`, `activeSystemPromptId`, `samplerPresets`, `activeSamplerPresetId`; add `instructEnabled: Boolean` and a per-connection `samplerPresetId` is NOT needed — sampler preset is global), `prompt/PromptBuilder.kt`, `chat/ChatController.kt`, `api/Backends.kt` (`GenerationRequest` gains `stopStrings`)
- Create: `data/DefaultPresets.kt` (builds ST default presets in code: Default prompt manager preset matching `openai/Default.json` order, ChatML instruct+context, Blank/"Roleplay - Simple" sysprompt, "Universal-Light"-like sampler)
- Test: `data/SettingsMigrationTest.kt`, update `PromptTest.kt`, `ChatControllerTest.kt`

**Interfaces:**
- Produces: `PromptBuilder.build(i: PromptInput): List<PromptMessage>` delegates to `PromptManagerBuilder` using `activePromptManager` (so old call sites unchanged); `PromptBuilder.buildText(i: PromptInput, instruct: InstructTemplate?, context: ContextTemplate, mode): FormattedPrompt`.
- Produces: `AppSettings.activeInstruct/activeContext/activeSystemPrompt/activePromptManager/activeSamplerPreset` accessors (same style as `activePreset`).
- Migration: `AppSettings` decoded from JSON containing only `presets` (MVP `PromptPreset`) maps `mainPrompt→main`, `jailbreak→jailbreak`, `nsfwPrompt→nsfw` into a `PromptManagerPreset` named after the old preset; `presets` stays readable but is no longer written.

- [ ] **Step 1: Write failing tests**: `legacySettingsMigrates` (JSON with old `presets` list → one PromptManagerPreset whose `main` content equals old `mainPrompt`), `defaultPromptManagerMatchesStOrder` (order of identifiers equals the `order` of test-resource `openai Default`), `controllerUsesInstructForTextCompletion` (ChatControllerTest with text-completion profile + ChatML active: captured request body prompt starts with `<|im_start|>system` and sends the stop strings), update old PromptTest expectations to new default output.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest` — expect new tests FAIL.
- [ ] **Step 3: Implement** settings fields, `DefaultPresets`, migration (in `SettingsRepository.load`), `PromptBuilder` delegation, `GenerationRequest.stopStrings` merging profile stops + formatter stops, and `ChatController.runGeneration` using instruct only for `BackendType.TEXT_COMPLETION`.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest` — expect all PASS.
- [ ] **Step 5: Commit** `feat(prompt): wire prompt engine into settings and chat; migrate legacy presets`

### Task 6: Text-completion sampler request mapping

**Files:**
- Modify: `api/Backends.kt` (`TextCompletionBackend.buildRequest`), `GenerationRequest` (carry `sampler: TextGenSamplerPreset?`)
- Test: `api/BackendTest.kt`

**Interfaces:**
- Consumes: `TextGenSamplerPreset` (Task 1); `ChatController` passes `s.activeSamplerPreset` for text-completion profiles.
- Behavior: preset values override the profile's basic sampler for `temperature, top_p, top_k, min_p, repetition_penalty, frequency_penalty, presence_penalty`; additionally sends (only when non-default) `top_a, tfs, typical_p, repetition_penalty_range, dry_multiplier, dry_base, dry_allowed_length, dry_penalty_last_n, dry_sequence_breakers (JSON array parsed from the string), mirostat_mode, mirostat_tau, mirostat_eta, smoothing_factor, smoothing_curve, xtc_threshold, xtc_probability, nsigma, dynatemp (as dynatemp_low/high/exponent), ban_eos_token, skip_special_tokens, seed (only if >= 0), grammar, negative_prompt, guidance_scale, banned_strings/custom_token_bans` and `stop` from `stopStrings`.

- [ ] **Step 1: Write failing tests** in `BackendTest` with MockWebServer: `defaultPresetSendsOnlyBasicFields` (body has no `dry_multiplier`), `nonDefaultSamplersAreSent` (min_p=0.1, dry_multiplier=0.8, xtc_probability=0.5 appear with those values), `seedMinusOneOmitted`, `breakersParsedAsArray`, `stopStringsSent`.
- [ ] **Step 2: Run** `--tests '*BackendTest'` — expect FAIL.
- [ ] **Step 3: Implement** the mapping in `TextCompletionBackend.buildRequest`; non-default detection compares to `TextGenSamplerPreset()` defaults.
- [ ] **Step 4: Run** `--tests '*BackendTest'` — expect PASS.
- [ ] **Step 5: Commit** `feat(api): full text-completion sampler mapping`

### Task 7: Prompt engine UI, import/export

**Files:**
- Create: `ui/PromptManagerScreen.kt` (list with enable switch, up/down reorder, edit dialog for name/role/content/injection position+depth+order, add custom prompt, delete custom, reset to default), `ui/PresetsScreen.kt` (tabs: Instruct, Context, System Prompt, Sampler; each with select, edit fields, duplicate, delete, import, export), `data/PresetIo.kt` (SAF read/write using `PresetJson`)
- Modify: `ui/SettingsScreen.kt` (entry points, instruct enable toggle), `MainActivity.kt` navigation
- Test: `data/PresetIoTest.kt` (import of each fixture creates a preset with the file's `name`; export→import is equal; importing a malformed file returns an error message and leaves settings unchanged)

- [ ] **Step 1: Write failing test** `PresetIoTest` as described.
- [ ] **Step 2: Run** `--tests '*PresetIoTest'` — expect FAIL.
- [ ] **Step 3: Implement** `PresetIo` (pure functions over `SettingsRepository`, injectable streams), then the two screens and navigation; reuse existing Compose helpers from `ui/Common.kt`.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest :app:lintDebug` — expect tests PASS and no new lint errors.
- [ ] **Step 5: Commit** `feat(ui): prompt manager and preset screens with ST import/export`

### Task 8: Docs and verification

**Files:** Modify `docs/WORKLOG.md`, `docs/ROADMAP.md`.

- [ ] **Step 1:** Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`; expect BUILD SUCCESSFUL.
- [ ] **Step 2:** Update WORKLOG (done list with test count, deviations, and "not verified": UI still not run on a device, no real-backend calls) and mark set 2 done in ROADMAP.
- [ ] **Step 3: Commit** `docs: work log for set 2 (prompt engine)`; then summarize to the user and ask to proceed to set 3.
