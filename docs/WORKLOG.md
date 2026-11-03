# Work Log

## Done (MVP, feature set 1)
- Scaffold: Kotlin 2.2.20, AGP 8.13, Gradle 8.13, Compose M3, minSdk 26 / compileSdk 35
- Storage: `StoragePaths`, atomic writes, AES-GCM `SecretStore` (Android Keystore), JSON `SettingsRepository`
- Character cards: V1/V2/V3 parse, PNG `chara`/`ccv3` read/write, unknown fields preserved, repository (import/export PNG+JSON, edit, delete, search UI)
- Chat: SillyTavern-compatible JSONL, swipes, alternate greetings as swipes, multiple chats per character
- Macros: char/user/description/personality/scenario/persona/time/date/weekday/newline/trim/random/roll/comments
- Prompt builder: system block, persona, example chat, post-history, context trimming, normal/continue/impersonate; text-completion prompt
- Token counting: jtokkit (o200k) for OpenAI connections; chars/3.6 estimate otherwise
- Backends: OpenAI-compatible chat, Anthropic Messages, text completion (`/completions`), SSE streaming, cancellation, error surfacing
- ChatController: send, regenerate (new swipe), swipe, continue, impersonate, edit, delete, stop (keeps partial)
- UI: character list/search/import/export/delete, character editor with avatar, chat screen, settings (connections incl. samplers + keys, personas, presets)
- Tests: 35 JVM unit tests (storage, cards, PNG, chat, macros, prompt, backends via MockWebServer, controller)
- Lint clean of errors (two real issues found and fixed: StateFlow read in composition, literal BOM char)

## Done (feature set 2: prompt engine) — plan: docs/plans/01-prompt-engine.md
- ST-format models with lossless JSON import/export (unknown keys kept): instruct template, context template, system-prompt preset, Prompt Manager (chat-completion) preset, text-completion sampler preset; fixtures from SillyTavern/default/content/presets
- Story-string renderer (`{{#if}}` nesting, `{{trim}}`, missing vars empty, malformed tags never throw)
- Instruct formatter: sequences/suffixes, first/last sequences, names behavior, system-same-as-user, continue/impersonate, derived stop strings; plain `Name: text` fallback when instruct is off
- Prompt Manager engine: ordering, enable toggles, markers (card fields, examples, history), card/system-prompt overrides with forbid-overrides, absolute injection at depth (ordered, grouped by role), impersonate/continue nudges, new-chat prompt, send-if-empty, squash, names-in-content
- Settings: new preset collections (selected by name, as in ST), instruct on/off, migration of MVP presets into Prompt Manager presets, bundled defaults (4 instruct, 5 context, 4 sysprompt, 5 sampler presets)
- Text completion: instruct/context-formatted prompt + stop strings; sampler preset mapping (basic samplers override the connection, advanced ones sent only when non-default: top_a, tfs, typical_p, rep range, DRY, mirostat, smoothing, XTC, nsigma, dynatemp, seed, grammar, negative prompt, banned strings)
- Anthropic: all leading system messages are joined into `system` (Prompt Manager emits several)
- UI: Prompt Manager screen (select/duplicate/delete/reset/import/export, toggle, reorder, edit, add/delete custom prompts, preset options) and Templates & samplers screen (instruct, context, system prompt, sampler: select/edit/duplicate/delete/import/export)
- Tests: 88 JVM unit tests total (53 new), lint has no errors

## Rulings / deviations (set 2)
- Presets are identified by name rather than UUID (matches ST; import replaces same-name preset)
- System-prompt presets feed the main/post-history prompts only for text-completion connections; chat-completion uses the Prompt Manager preset's own main prompt
- Example chat is emitted as one block (not split on `<START>` into instruct turns); per-prompt unknown keys (e.g. `injection_trigger`) are not preserved on re-export (top-level ones are)
- `names_behavior` for chat completion supports none/default and "names in content"; the OpenAI `name` field mode is not implemented
- World Info markers are empty until set 3; legacy `presets` field and `PromptPreset` remain only for migration
- Tests for Tasks 2-6 were written together with the implementation, not watched failing first; Task 1's first run also failed to compile (nested-comment bug) before passing
- Final review was a self-review (I did not dispatch a reviewer subagent)

## Not verified (set 2)
- Same as MVP: UI never run on a device/emulator; bundled presets are loaded via classpath resources (`src/main/resources/presets`), untested on-device
- No real backend calls; instruct output is checked against hand-written expectations, not against SillyTavern's actual output for the same card/chat

## Done (feature set 3: World Info) — plan: docs/plans/02-world-info.md
- Models/JSON: ST world-file format (entries as object or array, bool/number quirks, unknown keys preserved), V2 `character_book` <-> world book conversion
- Scanner (port of `checkWorldInfo`): plain/regex/whole-word/case keys, scan depth (global + per entry), secondary-key logic (AND ANY / NOT ALL / NOT ANY / AND ALL), constants, `@@activate`/`@@dont_activate`, recursion (prevent/exclude/delay-until-recursion levels, max steps), min activations, inclusion groups (override, weights, scoring), probability, token budget (% + cap, ignore-budget), generation-type triggers, opt-in scanning of persona/card fields, per-entry character filter
- Timed effects: sticky / cooldown / delay, stored in the chat header (`chat_metadata.timedWorldInfo`); regenerate/continue/impersonate do not advance them
- Assembly: before/after (with `wi_format`), at-depth injections by role, example-message top/bottom, `{{outlet::name}}`; text completion gets `wiBefore`/`wiAfter` and depth injections
- Sources: global books, character-attached book (`extensions.world`), per-character extra books, chat-bound book, embedded `character_book` (when no book is attached); strategy evenly / character first / global first
- UI: World Info screen (list, global toggle, import/export, new/delete, global settings), book screen (search, enable, reorder, add), entry editor with all fields; lorebook picker in character editor (plus "import embedded lorebook") and chat menu
- Tests: see `./gradlew :app:testDebugUnitTest` (193), lint has no errors

## Rulings / deviations (set 3)
- Regex keys keep ST's per-message `\u0001` prefix, so `^` does not match at a message start (same as ST)
- Only the `@@activate` and `@@dont_activate` decorators are parsed; other `@@` lines stay in the content
- With sticky + cooldown on one entry the cooldown starts when the sticky window ends; `sticky`/`cooldown`/`delay` = 0 or null mean off
- Scanner expands macros in keys and content itself (budget is measured on expanded text)
- Author's-note anchors (`ANTop/ANBottom`) are collected but not emitted until set 7; `vectorized` is stored but ignored (set 10); persona lorebook and regex-script processing of WI content not done
- Implementation was written together with (not before) tests for Tasks 3-7; regex keys have no catastrophic-backtracking guard
- Final review was a self-review (no reviewer subagent)

## Not verified (set 3)
- UI not run on a device/emulator; no comparison against real SillyTavern output for the same book + chat

## Done (feature set 4: backends) — plan: docs/plans/03-backends.md
- Streaming core: `Chunk` (Text / Reasoning / ToolCall), `ChunkBackend`, per-call stateful `EventParser`, SSE and NDJSON formats; the old `Backend.generate(): Flow<String>` still works (text only)
- Provider registry (`Providers`): OpenAI, Claude, Google, OpenRouter, Mistral, Groq, DeepSeek, xAI, Cohere (compat endpoint), Perplexity, Together, Fireworks, NVIDIA NIM, Moonshot, custom OpenAI-compatible; text-completion servers (TabbyAPI, Aphrodite, llama.cpp, ooba, vLLM, generic); Ollama, KoboldCpp, KoboldAI classic, NovelAI, AI Horde. OpenRouter attribution headers; DeepSeek reasoner drops sampler params
- New backends: Google Gemini (native, safety off, system instruction, thinking config), Ollama native `/api/chat`, Kobold (stream + classic, sampler preset mapping), NovelAI (stop strings applied client-side), AI Horde (async submit + poll, DELETE on cancel)
- Reasoning: captured from `reasoning_content`/`reasoning` (OpenAI-compatible), Anthropic `thinking`, Gemini thought parts, Ollama `thinking`; requested via per-connection effort + budget; stored in `extra.reasoning` (ST's key); collapsed "Thinking" section in the message bubble
- Model list fetch for every backend type; "Fetch models" button in the connection dialog (picker not yet a list — see below)
- Tool calling plumbing: `ToolDef`/`ToolRegistry`/`ToolLoop` (max 5 rounds), wire formats for OpenAI, Anthropic, Gemini, Ollama, split-argument assembly. `ChatController` takes a `ToolRegistry` (default empty, so no behaviour change until set 5/12 registers tools)
- Tests: 257 JVM unit tests total, lint has no errors, debug APK builds

## Rulings / deviations (set 4)
- Reasoning is stored per message, not per swipe: paging through old swipes shows the reasoning of the last generated reply; a new generation clears it, continue keeps it
- Tool calls are only exercised by unit tests with scripted backends — no user-visible tool exists yet
- "Fetch models" shows the fetched count/error but the connection dialog still has a free-text model field; fetched ids are not yet selectable from a list (see Not verified / follow-ups)
- NovelAI: no token-id stop sequences, preset library or tokenizer; Horde: no worker/kudos selection, anonymous key by default, max_length capped at 512 and context at 16384
- Claude prefill/cache control, image inputs, Vertex AI and OAuth providers not implemented
- Tests for Tasks 1-7 were written together with (not before) the implementation; self-review only, no reviewer subagent
- `ConnectionProfile.provider` defaults to "custom"; old profiles map to a generic provider of their own type

## Not verified (set 4)
- Nothing run against a real service or on a device/emulator: all backends are tested against MockWebServer with hand-written payloads based on ST's source and public API docs, so real-world quirks (extra fields, error shapes, rate limits) may still surface
- The provider dropdown / reasoning chips / thinking bubble UI compile and pass lint but were never seen on a screen

## Deviations from plan 00 (to confirm)
- No Hilt yet: manual `AppContainer` (simpler; revisit when graph grows)
- Ktor client not added: OkHttp only
- Room not added: file storage only is sufficient for MVP
- Foreground service for background generation NOT done (generation stops if the process is killed)

## Not verified
- **The UI has not been run on a device or emulator** (none available: no system images installed). It compiles, passes lint, and the logic under it is unit-tested, but screens are untested visually.
- No real API calls were made against OpenAI/Anthropic/local servers; backends are tested against a mock server only.

## Remaining
See docs/ROADMAP.md.

## Done (feature set 5: macros + commands) — plan: docs/plans/04-macros-commands.md
- Macro engine (`macro/`): real parser (nested `{{a::{{b}}}}`, `::` args, legacy `{{random:a,b}}`, comments), registry, bounded multi-pass evaluation (8 passes), scoped `{{if}}…{{else}}…{{/if}}` (nesting-aware). `Macros.expand` is now a facade, so sets 2–3 are unchanged
- Macro set: user/char/card fields (`charPrompt`, `mesExamples`, `charDepthPrompt`, …), `group`/`notChar`, `space`/`newline` counts, `reverse`, `random`/`pick`/`roll`, `time`/`date`/`weekday`/`isotime`/`isodate`, `datetimeformat` (moment tokens), `time_UTC±N`, `idleDuration`, `timeDiff`, `maxPrompt`/`maxContext`/`maxResponse`, `model`, `input`, `original`, local+global variables (`set/add/inc/dec/get/has/deletevar` and `…globalvar`), all `instruct*` macros, chat macros (`lastMessage`, `lastUserMessage`, `lastCharMessage`, `lastMessageId`, `firstIncludedMessageId`, `firstDisplayedMessageId`, `lastSwipeId`, `currentSwipeId`, `allChatRange`)
- Variables: local in `chat_metadata.variables`, global in `AppSettings.globalVariables`; numeric strings stored as numbers (as ST)
- Slash commands (`command/`): parser (pipes, escaped `\|`, quoted/bare named args anywhere, `{: closures :}`, `//` comments), executor with scope (`/let`), `{{pipe}}`, `{{var::x}}`, `{{timesIndex}}`, loop cap 100 000 and call-depth cap 32. Library: `/pass /return /abort /break /echo /sleep /help /if /while /times /run /: /let /var /setvar… /len /upper /lower /trim /substr /replace /split /join /sort /roll`, math (`/add /sub /mul /div /mod /pow /min /max /abs /round /sqrt /rand`), chat (`/sys /send /sendas /trigger /gen /genraw /continue /regenerate /swipe /impersonate /delete /cut /hide /unhide /setinput /messages /newchat /renamechat /getchatname /world /persona`), Quick Reply (`/qr /qr-list /qr-set-list /qr-set-on/off /qr-chat-set(-off) /qr-create /qr-update /qr-delete`)
- Input box: text starting with `/` + a non-space runs as a script (`ChatController.submit`); unknown commands report an error and are NOT sent to the model; `/ x` and `//` are sent as chat
- `/sys` messages are narrator messages (`is_system`, `extra.type=narrator`) and are sent to the model as system messages; `/hide` sets `extra.hidden` and drops the message from prompts
- Regex scripts (`regex/`): ST script JSON (lenient read, unknown keys kept), port of `getRegexedString`/`runRegexScript` (placements, markdown-only/prompt-only, run-on-edit, depth window, trim strings, `$1`/`$<name>`/`{{match}}`, substitute modes), global (`regex/global.json`) and per-character (`extensions.regex_scripts`). Applied: user input and finished replies (stored text, unless markdown-/prompt-only), prompt copies with depth, World Info content, display (bubble + reasoning). 250 ms budget per script against catastrophic backtracking, inputs > 1 MB skipped
- Quick Replies (`quickreply/`): ST v2 set files in `QuickReplies/`, import (name collisions renamed)/export, global + chat (`chat_metadata.quickReply.set`) + character sets, button bar in chat, `injectInput`/`placeBeforeInput`/`disableSend` semantics, auto-execute (startup/user/AI/chat change/new chat, `automationId`)
- UI: Quick Replies screens, Regex scripts screen (with test box), per-character regex + QR set in the character editor, chat-menu QR set, global variables list in Settings
- Tests: 422 JVM unit tests (all pass), lint has no errors, debug APK builds

## Rulings / deviations (set 5)
- Executed on `main` without a worktree (as sets 2–4); tasks 2–4 share one commit; tests for tasks 5, 6, 8, 10 were written together with (not strictly before) the implementation
- Scoped macro content is trimmed (ST does so); macro flags (`!?~>`) are parsed and ignored; `{{name arg}}` with a space is only accepted for the scoped `if`
- `/var NAME=value` sets and `/var NAME` gets a local variable; QR set commands use ST's names (`/qr-set-on/off` global, `/qr-chat-set` per chat) instead of the plan's `/qr-set`
- Regex scripts are parsed with a lenient hand-written reader instead of `@Serializable`; JS named groups with underscores are renamed for `java.util.regex`; `/s` → DOTALL, `/y` and `/u` ignored
- Auto-execute only runs slash scripts (plain-text QRs are skipped) and is non-reentrant, to avoid message loops
- `/gen` uses the visible chat plus the prompt as a final user message; neither `/gen` nor `/genraw` runs World Info
- Final review was a self-review (no reviewer subagent)

## Not verified (set 5)
- UI never run on a device/emulator (QR bar, regex dialogs, notices compile and pass lint only)
- Behavior is checked against hand-written expectations from ST's source, not against real SillyTavern output (macro edge cases, regex replacement semantics, `idleDuration`/`timeDiff` wording)
- Commands that mutate the chat while a reply is streaming (e.g. an auto-run `/cut`) can shift the streaming target message; not guarded
- Not implemented: autocomplete/help popup, command debugger, QR context-menu links, `/popup`, `/api`, `/model`, group commands (set 6), JS in QRs/regex (set 12), preset-scoped regex scripts
