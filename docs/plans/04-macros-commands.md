# Macros + Commands (feature set 5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the subset macro expander with a full SillyTavern macro engine (nesting, scoped `{{if}}`, variables, chat/instruct macros), add a slash-command engine with a core command library, Quick Replies, and regex scripts.

**Architecture:** `chat/Macros.kt` is split into `macro/` : a tokenizer/parser producing a small AST (`MacroParser`), a registry of macro definitions (`MacroRegistry`), and an evaluator (`MacroEngine`). `Macros.expand(String, MacroContext)` stays as the facade so set 2/3 callers keep compiling. Variables live in `VariableStore` (local = `chat_metadata.variables` in the chat header, global = `AppSettings.globalVariables`). `command/` holds the slash parser (pipes, named args, `{: :}` closures, `{{var}}` substitution), a scoped executor and the command library; commands act on the chat through a `CommandHost` interface that `ChatController` implements. `quickreply/` and `regex/` are independent stores with ST-compatible JSON, wired into `ChatController` / `PromptBuilder`.

**Tech Stack:** Kotlin, kotlinx.serialization JSON, kotlinx.coroutines (executor is `suspend`), Compose M3. No new dependencies. JUnit4 tests.

**Spec:** `docs/ROADMAP.md` row 5. Behavior reference (ST repo `/mnt/ide/teamssd/part1/projects/sillytavern_repo/SillyTavern/public/scripts/`): `macros/definitions/*.js`, `macros/engine/*.js`, `slash-commands/SlashCommandParser.js`, `slash-commands/SlashCommandClosure.js`, `slash-commands.js`, `variables.js`, `extensions/quick-reply/src/*.js`, `extensions/regex/engine.js`.

## Global Constraints

- Packages under `app.sillytavern.android`: `macro`, `command`, `quickreply`, `regex` (new); small edits in `chat/`, `prompt/`, `worldinfo/`, `data/`, `ui/`. minSdk 26, no new dependencies.
- `Macros.expand(input: String, ctx: MacroContext): String` and the existing `MacroContext` constructor parameters keep working; all 257 existing tests keep passing.
- Unknown macros are left in the text untouched (as today); a failing macro never throws out of `expand`.
- Persisted formats stay ST-compatible: chat-local variables in `chat_metadata.variables` (object of string/number values), regex scripts in ST's script JSON, Quick Reply sets in ST's v2 set JSON (`version: 2`, `qrList`, `idIndex`).
- Commands are executed in-process only; no network side effects other than the generation commands (`/gen`, `/genraw`, `/trigger`, `/continue`, `/regenerate`, `/impersonate`).
- Out of scope: macro flags `!`/`?`/`~`/`>` beyond parsing them as ignored prefixes, the autocomplete/help popup, command debugger, `/qr-*` context-menu links, QR import of `contextList` links, `/popup`, `/api`, `/model` switching, group-chat commands (`/member-*`, set 6), JS-in-QR/regex (set 12), `{{lastGenerationType}}`/`{{hasExtension}}` beyond fixed values.
- One commit per task; update `docs/WORKLOG.md` and ROADMAP at the end; stop and report (do not start set 6).

## Review Focus

- Unbalanced / unterminated `{{` or `{{/if}}` without an opener: text is left as-is, no exception, no infinite loop.
- Macro that expands to text containing `{{...}}` (user-typed persona, card field): expanded at most a bounded number of passes (no recursion bomb, `{{setvar::a::{{getvar::a}}}}` terminates).
- `|` inside quoted args, closures and `\|` escape: not treated as a pipe; a `|` inside a macro `{{...}}` is not a pipe either.
- Unknown `/command`: shows an error system message and does **not** send the text to the model; text that merely starts with `/` but has no letter after it (`/ shrug`, `//`) is sent as chat.
- Infinite `/while` or deep `/run` recursion: stopped by iteration cap (default 100 000 loops → configurable constant) and call-depth cap (32), with a visible error.
- Regex script with invalid pattern or catastrophic backtracking-prone pattern: invalid is skipped silently (logged in the result); the engine never throws, and a per-script input larger than 1 MB is skipped.
- Regex scripts must not alter the stored chat unless they are neither markdown-only nor prompt-only.
- `{{getvar::missing}}` returns `""`; math on non-numeric strings returns `0` (as ST), never throws.

---

### Task 1: Macro parser and engine core

**Files:**
- Create: `macro/MacroParser.kt`, `macro/MacroEngine.kt`, `macro/MacroRegistry.kt`
- Modify: `chat/Macros.kt` (becomes a thin facade delegating to `MacroEngine`; keeps `MacroContext`, `Macros.expand`)
- Test: `test/.../macro/MacroEngineTest.kt`

**Interfaces:**
- Produces: `sealed interface MacroNode { data class Text(val s: String); data class Call(val name: String, val args: List<List<MacroNode>>, val raw: String); }`; `object MacroParser { fun parse(input: String): List<MacroNode> }` — `{{name}}`, `{{name::a::b}}`, legacy `{{name:a}}` (single colon, only for `random`, `roll`, `getvar`-family names listed in the registry as `legacyColon`), nested calls inside args, `{{// comment}}`, leading flag characters `!?~>` stripped; unterminated `{{` becomes literal Text.
- Produces: `class MacroDef(val names: List<String>, val minArgs: Int = 0, val maxArgs: Int = minArgs, val scoped: Boolean = false, val legacyColon: Boolean = false, val eval: (MacroCall) -> String?)` and `class MacroCall(val ctx: MacroContext, val args: List<String>, val scopeContent: String?)`; `object MacroRegistry { fun register(def: MacroDef); fun find(name: String): MacroDef? }` (case-insensitive).
- Produces: `object MacroEngine { fun expand(input: String, ctx: MacroContext): String }`; evaluation: args are expanded first (left to right), a macro returning `null` or called with too few args stays literal (original `raw` text, args already expanded), at most 3 re-expansion passes over the result of the whole string (as the old engine), `{{trim}}` post-pass kept.
- Produces: scoped macros: `{{name args}}…{{/name}}` pairs matched by nesting; the content between is passed as `scopeContent` (unexpanded; the macro decides). `{{if cond}}A{{else}}B{{/if}}` is defined in Task 2 using this.
- Migrates the macros existing in the old `Macros.resolve` (user, char, description, personality, scenario, persona, newline, trim, time, date, isotime, isodate, weekday, outlet, `//`, random, roll) into the registry unchanged in behavior.

- [ ] Step 1: tests in `MacroEngineTest`: `plainTextUntouched`, `nestedMacroInArg` (`{{random::{{user}}::{{user}}}}` with user "Al" → "Al"), `unterminatedBraceKept` (`"a {{user b"` unchanged), `unknownMacroKept`, `commentRemoved`, `legacyRandomColon`, `trimRemovesSurroundingWhitespace`, `scopedPairMatchedByNesting` (parse tree for `{{x}}1{{x}}2{{/x}}{{/x}}`), `recursionBounded` (a `MacroContext.outlets` value containing `{{outlet::a}}` terminates), `oldMacrosKeepBehavior` (re-run the old assertions from `ChatTest`/`PromptTest` — they must still pass untouched).
- [ ] Step 2: run `./gradlew :app:testDebugUnitTest --tests '*MacroEngineTest'` → FAIL (compile).
- [ ] Step 3: implement parser (single left-to-right scan with a stack; no regex on the whole input), registry, engine; `Macros.expand` delegates.
- [ ] Step 4: run `./gradlew :app:testDebugUnitTest` → all PASS.
- [ ] Step 5: commit `feat(macro): parser, registry and engine core`.

### Task 2: Core, text, random and time macros

**Files:**
- Create: `macro/CoreMacros.kt` (registered from an `init`-style `MacroDefinitions.registerAll()` called lazily by `MacroRegistry`)
- Modify: `chat/Macros.kt` (`MacroContext` gains fields below, all defaulted)
- Test: `test/.../macro/CoreMacrosTest.kt`

**Interfaces:**
- Consumes: Task 1 `MacroDef`, `MacroCall`.
- Produces: `MacroContext` new defaulted fields: `charPrompt: String = ""` (system prompt override), `charInstruction: String = ""`, `mesExamplesRaw: String = ""`, `mesExamples: String = ""`, `depthPrompt: String = ""`, `creatorNotes: String = ""`, `firstMessage: String = ""`, `charVersion: String = ""`, `model: String = ""`, `maxPrompt: Int = 0`, `maxResponse: Int = 0`, `lastGenerationType: String = ""`, `isMobile: Boolean = true`, `input: String = ""`, `originalText: String? = null` (for `{{original}}`), `systemPromptText: String = ""`, `chat: ChatMacroView? = null` (Task 4), `vars: VariableStore? = null` (Task 3), `zone: java.time.ZoneId = ZoneId.systemDefault()`.
- Macros (names exact, ST definitions): `user`, `char`, `group`/`groupNotMuted`/`notChar` (single-chat: char; `notChar` = user), `charPrompt`, `charInstruction`, `charDescription`/`description`, `charPersonality`/`personality`, `charScenario`/`scenario`, `persona`, `mesExamplesRaw`, `mesExamples`, `charDepthPrompt`, `charCreatorNotes`/`creatorNotes`, `charFirstMessage`/`greeting`, `charVersion`, `model`, `original` (one-shot: returns `originalText` once then empty), `isMobile` ("true"), `space` (with optional count arg → that many spaces), `newline` (optional count), `noop` (""), `trim`, `input`, `maxPrompt`/`maxPromptTokens`, `maxContext`/`maxContextTokens`, `maxResponse`/`maxResponseTokens`, `reverse::text`, `//`, `random`, `random::a::b`, `random:a,b`, `pick::a::b` (deterministic per (chat id, position) hash — use `ctx.random` seeded by caller; test with fixed `Random`), `roll::NdM` (also `roll:` legacy, plain number), `banned` (""), `outlet::name`, `time`, `date`, `weekday`, `isotime`, `isodate`, `datetimeformat::fmt` (moment-style tokens `YYYY MM DD HH mm ss A a dddd MMMM` mapped to `DateTimeFormatter`), `time_UTC±N`, `idleDuration`/`idle_duration` (human text from `ctx.idleSince: Instant?`; "just now" if null), `timeDiff::a::b` ("2 hours ago"/"in 3 days" style via helper `humanizeDiff`), `if`/`else` scoped: `{{if cond}}A{{else}}B{{/if}}` where `cond` is a literal "true"/non-empty/non-"false"/non-"0" truth test, an optional `!` prefix inverts, and `{{if {{getvar::x}}}}` works because args expand first; `else` is split out of `scopeContent` at the matching nesting level.
- `<USER>`/`<BOT>`/`<GROUP>` legacy replacements stay in `MacroEngine.expand`.

- [ ] Step 1: tests (fixed `LocalDateTime`, fixed `Random(1)`): `userCharPersonaBasics`, `spaceAndNewlineCounts` (`{{newline::2}}` → "\n\n"), `reverseMacro`, `ifElseTrueFalse`, `ifNegation`, `ifWithNestedMacroCondition`, `ifWithoutCloseStaysLiteral`, `randomColonAndDoubleColon`, `rollDice` (`{{roll:2d6}}` between 2 and 12 and equals fixed-Random value), `datetimeformatTokens` (`YYYY-MM-DD HH:mm` → "2026-11-02 18:31"), `timeUtcOffset` (`{{time_UTC+2}}`), `timeDiffHumanized`, `maxContextAlias`, `originalOnlyOnce`.
- [ ] Step 2: run `--tests '*CoreMacrosTest'` → FAIL.
- [ ] Step 3: implement `CoreMacros.kt`; fill the new `MacroContext` fields where `PromptManagerBuilder.macroContext(i)` and `PromptBuilder` build contexts (card fields, profile model, token limits).
- [ ] Step 4: run full suite → PASS.
- [ ] Step 5: commit `feat(macro): core, text, random and time macros`.

### Task 3: Variables (local/global) and instruct macros

**Files:**
- Create: `macro/VariableStore.kt`, `macro/VariableMacros.kt`, `macro/InstructMacros.kt`
- Modify: `data/Settings.kt` (`AppSettings.globalVariables: Map<String, String> = emptyMap()`), `chat/ChatController.kt` (build a `VariableStore` over chat header + settings)
- Test: `test/.../macro/VariableMacrosTest.kt`

**Interfaces:**
- Produces: `class VariableStore(private val getLocal: () -> JsonObject, private val setLocal: (JsonObject) -> Unit, private val getGlobal: () -> Map<String,String>, private val setGlobal: (Map<String,String>) -> Unit)` with `fun get(name: String, global: Boolean, index: String? = null): String`, `fun set(name: String, value: String, global: Boolean, index: String? = null)`, `fun has(name: String, global: Boolean): Boolean`, `fun delete(name: String, global: Boolean)`, `fun add(name: String, value: String, global: Boolean)` (numeric add if both numeric, else string append; JSON-array local vars append the item), `fun inc(name, global): String`, `fun dec(name, global): String`. Values are stored as JSON primitives; a numeric-looking string is stored as number (as ST); get returns the string form without a trailing `.0`.
- Produces: `fun VariableStore.Companion.inMemory(): VariableStore` for tests/commands outside a chat.
- Macros: `setvar::n::v` (returns ""), `addvar`, `incvar` (returns new value), `decvar`, `getvar::n`, `hasvar::n`/`varexists`, `deletevar::n`/`flushvar`; the same eight with `global` in the name (`setglobalvar`, … `flushglobalvar`, `globalvarexists`); `getvar::n::index` reads index of a JSON array/object variable.
- Instruct macros (names from `instruct-macros.js`, primary + aliases): `instructSystemPrefix`, `instructSystemSuffix`, `instructUserPrefix`/`instructInput`, `instructUserSuffix`, `instructAssistantPrefix`/`instructOutput`, `instructAssistantSuffix`/`instructSeparator`, `instructFirstAssistantPrefix`/`instructFirstOutputPrefix`, `instructLastAssistantPrefix`/`instructLastOutputPrefix`, `instructSystemInstructionPrefix`, `instructStoryStringPrefix`, `instructStoryStringSuffix`, `instructUserFiller`, `instructStopSequence`; `MacroContext.instruct: InstructTemplate? = null` (returns "" when null or instruct off). Check exact JSON field names against `prompt/model/InstructTemplate.kt` before wiring.

- [ ] Step 1: tests: `setGetLocal`, `numericAddAndInc`, `stringAddAppends`, `globalVsLocalIsolated`, `missingVarEmpty`, `deleteAndHas`, `setvarInsideSameString` (`{{setvar::a::5}}{{getvar::a}}` → "5"), `selfReferentialTerminates` (`{{setvar::a::{{getvar::a}}x}}` twice), `localVarsPersistInChatHeader` (round-trip via `ChatRepository` save/load, key `chat_metadata.variables`), `instructMacroAliases`, `instructMacroEmptyWhenOff`.
- [ ] Step 2: run `--tests '*VariableMacrosTest'` → FAIL.
- [ ] Step 3: implement; `ChatController` exposes `variableStore()` backed by `_state` chat header (`chat_metadata.variables`) and `SettingsRepository.update` for globals — add a `updateSettings: ((AppSettings) -> AppSettings) -> Unit` constructor parameter (default no-op) wired in `AppContainer`.
- [ ] Step 4: run full suite → PASS.
- [ ] Step 5: commit `feat(macro): variables and instruct macros`.

### Task 4: Chat-state macros

**Files:**
- Create: `macro/ChatMacros.kt`
- Modify: `chat/Macros.kt` (`ChatMacroView` type), `prompt/PromptManagerBuilder.kt` and `prompt/PromptBuilder.kt` (pass the view)
- Test: `test/.../macro/ChatMacrosTest.kt`

**Interfaces:**
- Produces: `data class ChatMacroView(val messages: List<ChatMessage>, val firstIncludedIndex: Int = 0)` (messages = the visible chat, including the one being generated only if non-empty).
- Macros: `lastMessage` (text of last message), `lastUserMessage`, `lastCharMessage`, `lastMessageId` (index, `""` when none), `firstIncludedMessageId`, `firstDisplayedMessageId` (= 0 when chat non-empty), `lastSwipeId` (count of swipes of last message minus one, 1-based like ST: check `chat-macros.js`), `currentSwipeId`, `allChatRange` (`0-<last>` or `""`).
- Empty chat → empty string for all, never throws.

- [ ] Step 1: tests: `lastMessagesPicked`, `emptyChatGivesEmpty`, `swipeIds`, `lastUserSkipsAssistant`, `systemMessagesIgnoredForLastChar`.
- [ ] Step 2: run `--tests '*ChatMacrosTest'` → FAIL.
- [ ] Step 3: implement (read `chat-macros.js` for exact swipe-id semantics and mirror them in the test expectations).
- [ ] Step 4: run full suite → PASS.
- [ ] Step 5: commit `feat(macro): chat-state macros`.

### Task 5: Slash command parser

**Files:**
- Create: `command/CommandAst.kt`, `command/CommandParser.kt`
- Test: `test/.../command/CommandParserTest.kt`

**Interfaces:**
- Produces: `data class CommandCall(val name: String, val named: Map<String, Arg>, val unnamed: List<Arg>, val rawText: String)`; `sealed interface Arg { data class Literal(val s: String); data class Closure(val body: CommandScript); data class Macro(val s: String) }`; `data class CommandScript(val pipeline: List<CommandCall>)`; `object CommandParser { fun isCommand(text: String): Boolean; fun parse(text: String): CommandScript; }` throwing `CommandParseError(message, position)`.
- Syntax (ST): leading `/`; command name `[A-Za-z0-9_:.-]+`; named args `key=value` where value is bare word, `"quoted"` (with `\"` escape) or `{: closure :}`; remaining text is the unnamed argument (a `{: :}` closure as the unnamed arg is allowed); pipes `|` split commands (unless escaped `\|`, inside quotes, inside a closure, or inside `{{ }}`); a line starting with `//` is a comment command (`/#` too) and yields no call; `{{...}}` stays in the string for later expansion; `isCommand` is true only for `/` followed by a letter/digit/`#`/`:`/`/`-comment… but false for `/ `, `//` alone excepted `//` followed by text is a comment command (treated as command, swallowed).
- Multi-line scripts: newlines inside a closure are kept; at top level a newline does not end the command (ST: only `|` does).

- [ ] Step 1: tests: `simpleCommandWithArg`, `namedArgsBareAndQuoted`, `pipeChain`, `escapedPipeKept`, `pipeInsideQuotesKept`, `pipeInsideMacroKept`, `closureArg` (`/if left=1 right=1 rule=eq else={: /echo no :} /echo yes`), `nestedClosures`, `commentCommand`, `isCommandRejectsSlashSpace`, `unterminatedQuoteThrows`, `unterminatedClosureThrows`, `emptyAfterSlashThrows`.
- [ ] Step 2: run `--tests '*CommandParserTest'` → FAIL.
- [ ] Step 3: implement a hand-written recursive-descent parser (no regex over the full text).
- [ ] Step 4: run → PASS.
- [ ] Step 5: commit `feat(command): slash command parser`.

### Task 6: Executor, scope and flow-control commands

**Files:**
- Create: `command/CommandExecutor.kt`, `command/CommandRegistry.kt`, `command/FlowCommands.kt`
- Test: `test/.../command/ExecutorTest.kt`

**Interfaces:**
- Consumes: Task 5 AST, Task 1 `Macros.expand`, Task 3 `VariableStore`.
- Produces: `interface CommandHost` (stub here; extended in Tasks 7–8): `val variables: VariableStore; fun macroContext(): MacroContext; suspend fun echo(text: String, severity: String)`.
- Produces: `class CommandContext(val host: CommandHost, val scope: CommandScope, val executor: CommandExecutor, val named: Map<String, String>, val unnamed: String, val closures: Map<String, CommandScript>, val pipe: String)`; `class CommandDef(val name: String, val aliases: List<String>, val help: String, val run: suspend (CommandContext) -> String)`; `object CommandRegistry { fun register(def: CommandDef); fun find(name: String): CommandDef?; fun all(): List<CommandDef> }`.
- Produces: `class CommandScope(val parent: CommandScope?) { fun let(name, value); fun get(name): String?; fun set(name, value): Boolean }` (scoped `/let`, `/var` write-through to local/global store).
- Produces: `class CommandExecutor(val host: CommandHost, val limits: Limits = Limits())` with `suspend fun run(script: CommandScript, scope: CommandScope = CommandScope(null), pipe: String = ""): CommandResult` and `suspend fun runText(text: String): CommandResult`; `data class CommandResult(val pipe: String, val error: String? = null, val aborted: Boolean = false)`; `data class Limits(val maxLoopIterations: Int = 100_000, val maxCallDepth: Int = 32)`.
- Execution: each call's args are macro-expanded with `host.macroContext()` plus two extra substitutions: `{{pipe}}` → previous pipe value and scoped `{{var::name}}`-style scope reads via `{{var::x}}`/`{{globalvar::x}}` (registered on the executor-level, only inside commands); unnamed arg empty → previous pipe value is used (ST rule); unknown command → `CommandResult(error = "Unknown command: /x")`; a thrown exception in a command → error result, pipeline stops; `/abort` stops everything with `aborted = true`.
- Flow/utility commands (names exact): `/pass`, `/return`, `/echo` (`severity`, `color` ignored), `/abort`, `/break`, `/if left= right= rule= else= {closure}` (rules `eq neq lt gt lte gte not in nin`, string and numeric compare, a single `left` w/o rule is truthiness), `/while left= right= rule= {closure}`, `/times N {closure}` (sets `{{timesIndex}}` via scope var `timesIndex`), `/let name value`, `/var name=… value` (get/set, `index=`), `/setvar key= value`, `/getvar name`, `/addvar`, `/incvar`, `/decvar`, `/flushvar`, plus `/setglobalvar /getglobalvar /addglobalvar /incglobalvar /decglobalvar /flushglobalvar`, `/run name-or-closure` (runs closure variable or Quick Reply set in Task 10), `/call`, `/sleep ms` (alias `/delay`), `/:` (call closure variable), `/help` (lists names + help text), `/len`, `/upper`, `/lower`, `/trim`, `/substr start= end=`, `/replace pattern= replacer=` (plain or `/regex/flags`), `/split find= `, `/join glue=`, `/sort`, `/reverse`? (skip), math: `/add /sub /mul /div /mod /pow /min /max /abs /round /sqrt /rand from= to=` (accept space-separated numbers or a JSON array; non-numeric → 0, divide by zero → 0), `/roll` (same as macro), `/fuzzy` (skip).

- [ ] Step 1: tests: `pipePassesResult` (`/pass hi | /upper` → "HI"), `emptyArgUsesPipe`, `unknownCommandErrors`, `ifTrueRunsClosureElse`, `ifRuleComparisons` (table of rule/left/right → expected), `whileCountsUp` (with `/incvar`), `whileCappedByLimit` (infinite loop with `Limits(maxLoopIterations = 50)` → error "loop limit"), `timesLoop`, `letScopeShadowing`, `closureScopeSeesParentLet`, `runDepthCapped` (self-recursive closure), `abortStops`, `mathNonNumericIsZero`, `divideByZeroIsZero`, `pipeMacroSubstitution` (`{{pipe}}`), `varSetGetBothScopes`, `commandErrorStopsPipeline`.
- [ ] Step 2: run `--tests '*ExecutorTest'` → FAIL.
- [ ] Step 3: implement.
- [ ] Step 4: run → PASS.
- [ ] Step 5: commit `feat(command): executor, scope and flow/variable/math commands`.

### Task 7: Chat commands and ChatController integration

**Files:**
- Create: `command/ChatCommands.kt`
- Modify: `command/CommandExecutor.kt` (`CommandHost` extended), `chat/ChatController.kt` (implements `CommandHost`, `submit(text)`), `chat/ChatMessage.kt` (`narrator(text)` factory, `isHidden`), `prompt/PromptBuilder.kt` + `prompt/PromptManagerBuilder.kt` + `worldinfo/WorldInfoRunner.kt` (history filter: drop `isSystem` messages unless `extra.type == "narrator"`)
- Test: `test/.../command/ChatCommandsTest.kt`, extend `chat/ChatControllerTest.kt`

**Interfaces:**
- `CommandHost` gains: `suspend fun sendUser(text: String, trigger: Boolean)`, `suspend fun addSystemMessage(text: String, name: String? = null)`, `suspend fun addMessageAs(name: String, text: String, isUser: Boolean)`, `suspend fun generateReply()`, `suspend fun continueLast()`, `suspend fun regenerate()`, `suspend fun swipeBy(delta: Int)`, `suspend fun impersonate(): String`, `suspend fun deleteMessages(range: IntRange)`, `suspend fun hideMessages(range: IntRange, hidden: Boolean)`, `suspend fun setInput(text: String)`, `fun chatText(range: IntRange?): String`, `suspend fun generateQuiet(prompt: String, systemOverride: String?): String` (one-off completion with no history, used by `/genraw`; `/gen` uses the chat history with `prompt` appended as an instruction), `suspend fun newChat()`, `suspend fun renameChat(name: String)`, `fun chatName(): String`, `suspend fun setChatBook(name: String?)`.
- `ChatController.submit(text: String)`: if `CommandParser.isCommand(text)` → run through `CommandExecutor` (result pipe, if non-empty and the command did not print, is shown as a system message — ST shows the final pipe only for `/echo`; so: do **not** print the pipe except for `/echo` and `/sys`); an error shows as `state.error`; else → `send(text)`. `ChatScreen`'s send action calls `submit`.
- Commands: `/sys` (alias `/sendas`-style narrator: `/sys text` adds `is_system=true`, `extra.type="narrator"` message from name "System"; `/sysname name=`? skip), `/sendas name=X text` (assistant-style message under another name), `/send text` (adds a user message **without** generating; `/trigger` generates a reply), `/trigger`, `/gen prompt`, `/genraw prompt` (returns text, does not add to chat), `/continue` (alias `/cont`), `/regenerate`, `/swipe` (`left`/`right` via arg or `/swipe-left`?: only `/swipe left|right`), `/impersonate` (alias `/imp`), `/delete N` (alias `/del`, deletes last N messages), `/cut N-M` (range or index, `/cut 3`), `/hide N-M`, `/unhide N-M`, `/message N` hmm skip, `/messages` skip → implement `/getmessage N`? not in ST; instead use ST's `/messages names=on|off range` returning chat text (alias none), `/setinput text`, `/input` skip, `/go` skip, `/newchat` (alias `/newchat`), `/renamechat name`, `/getchatname`, `/world name` / `/world silent=true` set chat-bound lorebook, `/persona name` (switch persona by name via `updateSettings`), `/sendas`. Index args are absolute message ids (0-based, as ST). 
- Hidden messages: `/hide` sets `is_system=true` (ST behavior) and they are dropped from prompt; `/unhide` clears it; narrator messages stay in prompt.

- [ ] Step 1: tests (`ChatCommandsTest` with a fake host recording calls + a `ChatControllerTest` extension with a scripted backend): `sysAddsNarratorMessage`, `narratorIncludedInPrompt` (prompt contains the narrator text), `hiddenExcludedFromPrompt`, `sendWithoutGenerate`, `trigger`, `sendasName`, `deleteLastN`, `cutRange`, `hideUnhideRange`, `setInput`, `genRawDoesNotTouchChat`, `genReturnsText` pipes into `/setvar`, `unknownCommandNotSentToModel`, `slashSpaceSentAsChat`, `commandResultSurfacedAsErrorOnFailure`, `outOfRangeIndexIgnored`.
- [ ] Step 2: run `--tests '*ChatCommandsTest' --tests '*ChatControllerTest'` → FAIL.
- [ ] Step 3: implement; `ChatController` keeps its existing public API (`send`, `regenerate`, …) — the host methods call them.
- [ ] Step 4: run full suite → PASS.
- [ ] Step 5: commit `feat(command): chat commands and controller integration`.

### Task 8: Regex script engine

**Files:**
- Create: `regex/RegexScript.kt`, `regex/RegexEngine.kt`, `regex/RegexRepository.kt`
- Modify: `data/StoragePaths.kt` (`regexDir = File(root, "regex")`, in `ensure()`), `data/Settings.kt` (`AppSettings.regexEnabled: Boolean = true`)
- Test: `test/.../regex/RegexEngineTest.kt`, `RegexRepositoryTest.kt`

**Interfaces:**
- Produces: `@Serializable data class RegexScript(val id: String = UUID…, val scriptName: String = "", val findRegex: String = "", val replaceString: String = "", val trimStrings: List<String> = emptyList(), val placement: List<Int> = emptyList(), val disabled: Boolean = false, val markdownOnly: Boolean = false, val promptOnly: Boolean = false, val runOnEdit: Boolean = true, val substituteRegex: Int = 0, val minDepth: Int? = null, val maxDepth: Int? = null)` with ST's JSON key names (`id`, `scriptName`, `findRegex`, …) and `ignoreUnknownKeys`; placements `object Placement { const val USER_INPUT = 1; AI_OUTPUT = 2; SLASH_COMMAND = 3; WORLD_INFO = 5; REASONING = 6 }`.
- Produces: `data class RegexParams(val isMarkdown: Boolean = false, val isPrompt: Boolean = false, val isEdit: Boolean = false, val depth: Int? = null, val macros: ((String) -> String)? = null)`; `object RegexEngine { fun apply(text: String, placement: Int, scripts: List<RegexScript>, p: RegexParams = RegexParams()): String; fun compile(findRegex: String): Regex? }`.
- Behavior (port of `getRegexedString`/`runRegexScript` in `extensions/regex/engine.js`): skip `disabled`; skip if `placement` lacks the requested one; `markdownOnly` runs only when `isMarkdown`, `promptOnly` only when `isPrompt`; when neither flag set, runs only when the call is neither markdown-display nor prompt, i.e. (`!markdownOnly && !promptOnly`) scripts run for every call in ST — mirror ST exactly: check the source and test it; `isEdit && !runOnEdit` skips; depth window `minDepth..maxDepth` (null = unbounded, only checked when `p.depth != null`); `substituteRegex`: 0 none, 1 raw macro expansion of the pattern, 2 escape regex metacharacters of the expanded value; `compile` accepts `/pattern/flags` (flags `g i m s u y`, `g` → replace all else first match only) and rejects/returns null for invalid; JS replacement syntax mapped to Java: `$1`..`$9`, `$&`/`{{match}}` → whole match, `$<name>`, `$$`; `trimStrings` removed from each captured group value before substitution; Java `Regex` created with `UNICODE_CASE`; input over 1 MB or invalid pattern → script skipped.
- Produces: `class RegexRepository(paths: StoragePaths) { fun global(): List<RegexScript>; fun saveGlobal(list: List<RegexScript>); fun importJson(text: String): RegexScript; fun exportJson(s: RegexScript): String; fun scopedOf(card: CharacterCard): List<RegexScript> /* extensions.regex_scripts */ ; fun withScoped(card, list): CharacterCard }` — global scripts in `regex/global.json` as a JSON array; import accepts a single script object or an array.

- [ ] Step 1: tests: `simpleReplace`, `globalFlagReplacesAll`, `noGlobalFlagFirstOnly`, `captureGroupsInReplacement`, `namedGroup`, `matchMacro`, `trimStringsRemoved`, `placementFiltering`, `markdownOnlyRunsOnlyForDisplay`, `promptOnlyRunsOnlyForPrompt`, `depthWindow`, `runOnEditFalse`, `disabledSkipped`, `invalidPatternSkipped`, `substituteRegexEscaped` (user name `a.b` with mode 2 does not match `aXb`), `oversizedInputSkipped`, `stSampleScriptRoundTrip` (add `src/test/resources/regex/sample.json`, a hand-written ST-format script), `scopedScriptsFromCard`, `importArrayAndSingle`.
- [ ] Step 2: run `--tests '*Regex*'` → FAIL.
- [ ] Step 3: implement (re-read `engine.js` `runRegexScript` for the exact flag logic before coding).
- [ ] Step 4: run → PASS.
- [ ] Step 5: commit `feat(regex): script model, engine and storage`.

### Task 9: Wire regex into prompt, storage and display

**Files:**
- Modify: `prompt/PromptBuilder.kt`, `prompt/PromptManagerBuilder.kt` (`PromptInput.regexScripts: List<RegexScript> = emptyList()`; history messages passed through `RegexEngine.apply(…, isPrompt = true, depth = <distance from end, 0 = newest>)` with USER_INPUT or AI_OUTPUT by author), `worldinfo/WorldInfoRunner.kt` (WORLD_INFO placement on entry content), `chat/ChatController.kt` (apply on send and on finished reply), `ui/ChatScreen.kt` (display pass with `isMarkdown = true`), reasoning display (REASONING placement)
- Test: `test/.../regex/RegexIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 8; `ChatController` gets `regex: () -> List<RegexScript>` (global + scoped of the open card, computed per call, honoring `AppSettings.regexEnabled`; default `{ emptyList() }`).
- Stored text rule: on user send, apply USER_INPUT scripts with `isMarkdown=false, isPrompt=false` (only scripts that are neither markdown- nor prompt-only effectively modify stored text); on finished reply apply AI_OUTPUT likewise (not per streamed chunk — once in `finally`, only if the reply is non-empty); `edit()` applies scripts with `isEdit = true`.
- Prompt rule: prompt-only scripts and ordinary scripts apply to history copies; stored chat untouched.
- Display rule: `ChatScreen` calls `RegexEngine.apply(m.text, placement, scripts, RegexParams(isMarkdown = true))` inside `remember(m.text, scripts)`.

- [ ] Step 1: tests: `promptOnlyAffectsPromptNotStored`, `markdownOnlyNotInPrompt`, `ordinaryScriptChangesStoredUserText`, `ordinaryScriptChangesStoredReply`, `depthLimitsPromptRewrite` (maxDepth=1 only last two messages), `worldInfoPlacementRewritesEntry`, `disabledGlobalSwitchOff` (`regexEnabled=false`), `streamingChunksNotRegexed` (partial text visible raw until finished).
- [ ] Step 2: run `--tests '*RegexIntegrationTest'` → FAIL.
- [ ] Step 3: implement.
- [ ] Step 4: run full suite → PASS.
- [ ] Step 5: commit `feat(regex): apply scripts to prompt, stored text and display`.

### Task 10: Quick Replies — model, storage, execution

**Files:**
- Create: `quickreply/QuickReplyModels.kt`, `quickreply/QuickReplyRepository.kt`, `quickreply/QuickReplyRunner.kt`, `command/QuickReplyCommands.kt`
- Modify: `data/StoragePaths.kt` (`quickReplies = File(root, "QuickReplies")`), `data/Settings.kt` (`AppSettings.quickReplies: QuickReplySettings = QuickReplySettings()`), `chat/ChatController.kt` (auto-exec hooks)
- Test: `test/.../quickreply/QuickReplyTest.kt`

**Interfaces:**
- Produces (ST v2 JSON, `@Serializable`, keys exact): `QuickReply(id: Int, label: String = "", title: String = "", message: String = "", icon: String = "", showLabel: Boolean = false, isHidden: Boolean = false, executeOnStartup: Boolean = false, executeOnUser: Boolean = false, executeOnAi: Boolean = false, executeOnChatChange: Boolean = false, executeOnGroupMemberDraft: Boolean = false, executeOnNewChat: Boolean = false, automationId: String = "", preventAutoExecute: Boolean = true, contextList: JsonArray = [])`; `QuickReplySet(version: Int = 2, name: String, disableSend: Boolean = false, placeBeforeInput: Boolean = false, injectInput: Boolean = false, color: String = "rgba(0, 0, 0, 0)", onlyBorderColor: Boolean = false, qrList: List<QuickReply> = emptyList(), idIndex: Int = 0)`; `QuickReplySettings(globalSets: List<String> = emptyList(), globalEnabled: Boolean = true, chatEnabled: Boolean = true)` (per-chat set name in `chat_metadata.quickReply = {"set": name}`; per-character set in `extensions.quickReply` hmm → store as `AppSettings.characterQuickReplySets: Map<String,String>` keyed by card file name).
- Produces: `class QuickReplyRepository(paths) { fun list(): List<String>; fun load(name): QuickReplySet?; fun save(set); fun delete(name); fun importJson(text): QuickReplySet /* name collisions get " (2)" */; fun exportJson(set): String }` (file `<name>.json`, names sanitized).
- Produces: `class QuickReplyRunner(executor: () -> CommandExecutor, host-input access)`: `suspend fun run(set: QuickReplySet, qr: QuickReply, currentInput: String): QuickReplyOutcome` — expands `{{input}}` as the current input, executes `message` as a script if it starts with `/` (multi-line OK), otherwise sends it as chat text; `injectInput` prepends the current input; `placeBeforeInput` and `disableSend` (puts text into the input box instead of sending) per ST semantics; returns `data class QuickReplyOutcome(val textToSend: String?, val inputText: String?, val error: String?)`.
- Active sets for a chat = global (if enabled) + chat set + character set, deduped by name, hidden QRs excluded from the bar.
- Auto-execute: `ChatController` fires, via a `QuickReplyAuto` helper, `executeOnStartup` (once per app start, in `AppContainer`), `executeOnUser` (after a user message is added), `executeOnAi` (after a reply finishes), `executeOnChatChange` (in `open`), `executeOnNewChat` (in `newChat`); `automationId` filter: a QR with a non-empty `automationId` runs only if the chat/character `automationId` matches (store `chat_metadata.automationId`); `preventAutoExecute` is **ignored for chat-triggered auto-exec** and only blocks execution while a character/card import payload contains the set (not implemented → just stored).
- Commands (`QuickReplyCommands.kt`): `/qr set.label-or-index` (alias `/qr`, with `set=` named arg), `/qr-list set`, `/qr-set <name>` (chat set on, `/qr-set-off`), `/qr-chat-set name`, `/qr-chat-set-off name`, `/qr-set-list`, `/qr-create set= label= message`, `/qr-update set= label= newlabel= message`, `/qr-delete set= label`, `/run set.label` falls through to QR when no closure variable of that name exists. 

- [ ] Step 1: tests: `setJsonRoundTripKeepsAllFields` (use a hand-written ST v2 sample in `src/test/resources/quickreply/sample.json`), `importNameCollisionRenamed`, `runPlainMessageSends`, `runSlashMessageExecutes`, `inputMacroExpanded`, `injectInputPrepends`, `disableSendFillsInput`, `activeSetsDeduped`, `hiddenNotInBar`, `autoExecOnUserAndAi`, `autoExecAutomationIdFilter`, `qrCommandRunsByLabel`, `qrCreateUpdateDelete`, `unknownQrErrors`, `recursiveQrCapped` (QR that runs itself stops at call-depth 32).
- [ ] Step 2: run `--tests '*QuickReplyTest'` → FAIL.
- [ ] Step 3: implement.
- [ ] Step 4: run full suite → PASS.
- [ ] Step 5: commit `feat(quickreply): sets, storage, execution and commands`.

### Task 11: UI — command input, Quick Reply bar/editor, Regex screen

**Files:**
- Create: `ui/QuickReplyScreen.kt`, `ui/RegexScreen.kt`
- Modify: `ui/ChatScreen.kt` (QR bar above the input; send → `controller.submit`; display-regex pass), `ui/SettingsScreen.kt` (entries "Quick Replies", "Regex scripts", "Global variables" read-only list), `MainActivity.kt` (routes `quickreplies`, `quickreplies/{name}`, `regex`), `App.kt` (`QuickReplyRepository`, `RegexRepository`, `QuickReplyAuto` wiring)
- Test: none beyond lint/compile; add the small pure helpers' tests to `QuickReplyTest`/`RegexRepositoryTest` where logic exists.

**Interfaces:**
- Chat screen: horizontally scrolling chip row of visible QRs from active sets (label or icon); tap runs `QuickReplyRunner`; outcome `textToSend` → `submit`, `inputText` → input field; a snackbar shows `error`.
- QR screen: list sets, create/import/export (SAF create/open document, like the World Info screen), set editor (name, `disableSend`, `placeBeforeInput`, `injectInput`, list of QRs with label/message/title/icon, auto-exec checkboxes, hidden), global-set multiselect, per-chat set picker in the chat menu.
- Regex screen: global list with enable toggle, add/edit (name, find, replace, trim strings, placement checkboxes, flags, depth, substitute mode), import/export, "test" box that runs the engine on sample text and shows the result; scoped scripts editable from the character editor (new "Regex scripts" section using `withScoped`); master `regexEnabled` switch.

- [ ] Step 1: implement screens following the patterns in `WorldInfoScreen.kt` / `PresetsScreen.kt`.
- [ ] Step 2: run `./gradlew :app:compileDebugKotlin :app:lintDebug` → no errors.
- [ ] Step 3: run `./gradlew :app:assembleDebug` → debug APK builds.
- [ ] Step 4: commit `feat(ui): command input, quick replies and regex screens`.

### Task 12: Full verification and docs

**Files:** `docs/WORKLOG.md`, `docs/ROADMAP.md`
- [ ] Step 1: run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`; expect all green; record the test count.
- [ ] Step 2: end-to-end JVM test `command/EndToEndTest.kt`: a chat with a scripted backend, `submit("/setvar key=mood happy")`, then a user message whose prompt contains `{{getvar::mood}}`; a QR with `/sys {{getvar::mood}}` run via `QuickReplyRunner`; a prompt-only regex. Passing proves the pieces compose.
- [ ] Step 3: mark set 5 DONE in the ROADMAP table; add "Done / Rulings / Not verified (set 5)" sections to WORKLOG in the style of sets 3–4 (state honestly: UI never run on a device; no comparison with real ST output; self-review only unless a reviewer is run).
- [ ] Step 4: commit `docs: work log and roadmap for set 5 (macros + commands)`.
