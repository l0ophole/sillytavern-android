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

## Done (feature set 6: groups + tags) — plan: docs/plans/05-groups-tags.md
- Tags (`tags/`): ST tag model (`tags` + `tag_map` in `AppSettings`, keyed by `<file>.png` / group id), pure `TagOps` (case-insensitive reuse, rename guard, delete cascades, card-tag import) and `TagFilter` (include = AND, exclude, favorites, text query over names and tag names, CLOSED folders hidden unless selected). Card tags are imported into the map once per character (first time its key is absent) and when a new character is saved
- Groups (`group/`): ST `groups/<id>.json` (unknown keys kept), chats in `group chats/<chat_id>.jsonl` with ST's placeholder header; messages carry `original_avatar`. `GroupRepository` create/save/delete (deletes chats)/addChat
- Activation (`GroupActivation`): ports of natural (mentions, talkativeness rolls, banned last speaker, random fallback), list, pooled, manual, swipe/continue speaker, impersonate. `GroupCards.merge`: SWAP / APPEND / APPEND_DISABLED with join prefix/suffix and `<FIELDNAME>`
- `ChatController` group mode: `openGroup`, `newChat`/`chatFiles`/`openChatFile`/`deleteChatFile` for group chats, sequential per-member generation (`generating` stays true across the batch, stops on error or Stop), `speak(avatar)` (works for muted members), `muteMember`, `updateGroup`, `moveMember`, auto mode (`setAutoMode`, every `auto_mode_delay` s). Regenerate/swipe/continue use the message's own author; an author who left the group gives an error and leaves the message untouched. Prompts get `groupNames` (`Alice: text` on every history message; chat completion always, text completion only without an instruct template) and the speaker's card merged per the group's generation mode
- Commands: `/member-count /member-get (field=name|index|avatar) /member-enable /member-disable /member-add /member-remove /member-up /member-down`, `/trigger <member>` in a group
- Card fields: `fav` and `talkativeness` (`extensions.*`), editable in the character editor
- UI: mixed character/group list with favorites (★ first), tag filter chips (tap include, long-press exclude, folder icons), long-press multi-select with bulk favorite / add tag / remove tag / create group / delete; group editor (members up/down/mute/remove, strategy, card handling, join prefix/suffix, self responses, auto delay, favorite, tags, delete); tag manager (rename, folder type, delete, usage count; Settings → Tags); group chat uses `ChatScreen` with a member strip (tap = speak, long-press = mute) and menu entries for auto mode / edit group
- Tests: 496 JVM unit tests (all pass), lint has no errors, debug APK builds. Also made `CoreMacrosTest.idleDuration` independent of the wall clock (it failed any time after the minute it was written)

## Rulings / deviations (set 6)
- Executed on `main` without a worktree, native (no subagents); tests for the UI tasks do not exist (compile + lint only)
- Tag filter uses AND for several included tags (ST offers an AND/OR toggle)
- Groups have a plain 👥 placeholder instead of a composed avatar; `avatar_url` is stored but unused
- Dropped from the plan: `/member-peek` (no use for it yet) and the unused `GroupSession` file split; `GroupGenerationMode` (not `GenerationMode`) avoids clashing with the prompt enum
- World Info for a group turn uses the speaker's own sources + global/chat books; other members' character books are not scanned
- `{{char}}` outside a generation (commands, QR) is the first member of the group
- Character favorites rewrite the card PNG (`extensions.fav`), as ST does
- Natural-order mention matching follows ST (any word of a member name present in the input)
- In text completion with an instruct template, group speaker names come from the template's own name handling (which only knows the current speaker), not from `groupNames`

## Not verified (set 6)
- UI never run on a device/emulator (list filters, tag chips — disabled `FilterChip` inside a clickable box so taps reach the box but the chips look dimmed —, selection mode, group editor, member strip)
- Activation behaviour is checked against hand-written expectations from ST's source with seeded randomness, not against real SillyTavern output
- Group depth prompts, sprites/`hideMutedSprites`, queue display and group avatar composition are not implemented; tag import/export of other ST settings is not implemented
- Deleting a character does not remove it from groups (it shows as "missing"); group chats of a deleted group are removed, character chats are kept
- Auto mode keeps running while the chat screen is closed until another chat is opened


## Done (feature set 7: chat features) — plan: docs/plans/06-chat-features.md
- Extension prompts (`prompt/ExtensionPrompt.kt`, `PromptInput.extensionPrompts`): position 0 in prompt / 1 in chat at depth / 2 before prompt, role 0-2. Chat completion puts 0/2 right after/before the `main` prompt (front/back of the pre-history block when `main` is off) and 1 into the depth injections next to World Info; text completion fills `anchorAfter`/`anchorBefore` and the same depth injections. Macros are expanded in their content
- Author's Note (`chat/AuthorsNote.kt`, `ChatMeta.kt`): `note_prompt/interval/depth/position/role` in `chat_metadata` (ST keys), defaults in `AppSettings.note`; due when interval is 1, or `userMessages % interval == 0` (0 = never); character note (`AppSettings.characterNotes`, replace/before/after) for single-character chats; dialog from the chat menu with "save as default"
- Personas: avatar (`User Avatars/<id>.png`, picked image is re-encoded as PNG), title, description position (in prompt / top AN / bottom AN / at depth / not sent), depth, role, lorebook (scanned after the chat book, before global/character books), default persona, character locks (`Persona.characters`), chat lock (`chat_metadata.persona`), optional auto-lock. Chat open applies chat lock > character lock > default > current. Settings list shows avatars and ★ default; chat menu has a persona/lock dialog
- Branches and checkpoints (`chat/ChatBranching.kt`): message action "Branch from here" (copies `0..N`, selected swipe kept, opens `<chat> - Branch #n`, source message gets `extra.branches`), "Create checkpoint" (`<chat> - Checkpoint #n` or a typed name, stays in the chat, `extra.bookmark_link`, flag button on the bubble), "Back to main chat" via `main_chat`. Works for group chats (the new chat id is added to the group). Commands: `/branch-create /checkpoint-create /checkpoint-go /checkpoint-exit /checkpoint-get /checkpoint-list /checkpoint-parent`
- Hide/unhide per message in the message actions (same `extra.hidden` as `/hide`)
- Import/export (`chat/ChatIo.kt`): ST JSONL (Chub swipe objects flattened), Ooba, Agnai, CAI Tools (one chat per history), Kobold Lite; export as JSONL or `Name: text` text (system lines skipped, `display_text` used). Chats dialog has Import and a per-chat menu (export, delete). Imported files are `<name> - <stamp> imported.jsonl`
- Summary (`chat/Summary.kt`): stored in `extra.memory` of a message; injected from the newest summary found in the prompt history (so regenerating an older message never sees a later summary) with a template, position, depth, role; "Summarize now" and optional automatic summaries (every N chat lines, frozen switch) through the main API; `/summarize [text]`, `/getsummary`, `{{summary}}` macro; dialog from the chat menu
- Verification: 574 JVM unit tests pass (78 new), lint has no errors, debug APK builds
- Tests: new suites for the extension prompts, Author's Note, summary, persona locks/positions, branches/checkpoints/hide, import/export, summarization, persona lorebook ordering

## Rulings / deviations (set 7)
- Executed on `main` without a worktree, native (no subagents); UI tasks compile + lint only
- Persona ids stay UUIDs (ST uses the avatar file name), so persona sets are not interchangeable with ST's `settings.json`
- The summary prompt is sent as the final user message of a quiet generation (not a system/quiet-prompt injection); the model's reply is trimmed and stored as is
- Author's Note counts all user messages of the chat (hidden ones too), as ST does; it is not scanned by World Info
- A default persona is applied on every chat open without a lock (as ST), which overrides a persona picked by hand for that session
- Manual edits change the current summary in place; automatic ones add a new summary on the newest message and leave older ones

## Not verified (set 7)
- UI never run on a device/emulator (dialogs, avatar picker, import/export via the document picker, checkpoint flag)
- Import formats are checked against hand-written samples taken from ST's converters, not real exports; RisuAI chats and Chub-specific metadata are not imported
- No live model call for summaries (fake backend only); `{{words}}` is advisory, there is no word-limit forcing
- Not implemented: Extras/WebLLM summary sources, raw/non-blocking summary builders, persona avatar in message bubbles (set 8), persona import/export, Author's Note WI scanning, auto-summary while the chat screen is closed


## Done (feature set 8: appearance) — plan: docs/plans/07-appearance.md
- Markdown (`appearance/Markdown.kt`, `ui/MarkdownText.kt`): showdown-like blocks (headings, quotes, bullet/numbered lists, fenced code, rules, image lines) and inline (`**bold**`, `*italic*`, `_italic_`, `~~strike~~`, `` `code` ``, links, `"quotes"` as a colored run, backslash escapes); single newlines stay line breaks. Inline HTML limited to `b/strong/i/em/u/ins/s/del/strike/code/q/br/a`; `script/style/iframe/object/embed` are dropped with their content, other tags are stripped and the content kept; with "Allow basic HTML tags" off tags show as text. Links open only for http(s), mailto, tel
- Themes (`appearance/Appearance.kt`, `ThemeIo.kt`, `ui/AppTheme.kt`): system/light/dark/AMOLED, color overrides (text, action/italics, quote, underline, app/chat background, user/character message), font scale; import/export of SillyTavern theme files (ST keys, `rgba()`/hex colors, light/dark inferred from `blur_tint_color`), reset
- Chat look: message style flat/bubbles/document, avatar style round/portrait/square/rounded (ST `avatar_style`), character and persona avatars in messages (group chats use `original_avatar`), timestamps, message numbers, hide avatars, background image with dim (`backgrounds/` folder, add/select/delete in the Appearance screen)
- Tablet: screens under 600dp use the full width; wider ones center the chat column at `chatWidth`% (ST `chat_width`, min 560dp), input bar included
- i18n (`appearance/Translator.kt`, `assets/locales/{es,fr,de}.json`): ST-style English-text keys, English fallback. Applied to the chat screen and its menus, settings entry points, character list top bar, Appearance screen
- Verification: 639 JVM unit tests pass (65 new: markdown, theme io, translator + locale files, background repo), lint 0 errors, debug APK builds

## Rulings / deviations (set 8)
- Executed on `main`, native, no subagents; UI tasks compile + lint only
- "Moving UI" (draggable panels) not built: no meaning on a phone. ST's custom CSS box, blur/shadow sliders, reduced motion and zen/lab modes are not built
- Only the main screens are translated (not prompt manager, world info, regex, quick replies, tags, group editor, dialogs); es/fr/de strings were written by the assistant, not by native speakers
- No separate list-detail layout on tablets, only the width-capped column
- Imported ST themes keep the app's language, background, and markdown/HTML switches; the theme name is kept only as a label

## Not verified (set 8)
- Nothing run on a device/emulator: bubble layouts, avatar shapes, background dimming, AMOLED colors, the color text fields, markdown rendering of long streamed messages (re-parsed on every token), link taps
- Themes checked against hand-written samples shaped like ST's `Dark Lite.json`, not against all bundled themes
- Not implemented: `/bg` command, per-character backgrounds, locked backgrounds per chat, message-level `<style>`/tables/iframes, RTL layout testing, reasoning text is shown as plain text (not markdown)


## Done (feature set 9: media extensions) — plan: docs/plans/08-media.md
- Attachments (`media/MessageMedia.kt`, `MediaPrompt.kt`): ST `extra.image` / `inline_image` / `title` / `file` plus `extra.image_caption`; images are stored in `user/images/<character or group>/` and referenced by a path relative to the data root (paths that escape the root are refused). The prompt builders leave a marker that `ChatController.makeRequest` turns into image parts (OpenAI `image_url`, Anthropic `image` blocks, Gemini `inlineData`, Ollama `images`) or into `[Image: caption]` text; text-completion connections always get captions. Text files (≤1 MB) are inlined into the prompt as `[File: name] … [End of file]`. Images are scaled to a longest side (default 1568 px) when attached
- Captioning (`media/Captioner.kt`): the active chat connection answers a caption prompt with the image; optional auto-caption before the reply; `/caption [mes=N] [prompt]`; message action "Caption image"
- TTS (`media/TtsText.kt`, `TtsClient.kt`, `AndroidMedia.kt`): Android system voices, OpenAI-compatible `/audio/speech` and ElevenLabs; read everything / only quotes / only narration, skip code blocks and `*actions*`, voice per character (`Name=voice` lines), speed/pitch, auto-play of new replies, long text split at sentences, `/speak [voice=Name] text`, message action "Speak", menu "Stop speaking"
- STT (`media/SttClient.kt`, `AndroidMedia.kt`): Android `SpeechRecognizer` or a Whisper-compatible `/audio/transcriptions` server (record, tap again to transcribe); mic button in the input bar, optional auto-send
- Image generation (`media/ImageGen.kt`): AUTOMATIC1111/Forge `txt2img`, ComfyUI (workflow with `%prompt%`-style placeholders, queue + poll + `/view`), NovelAI (zip response), OpenAI-compatible `/images/generations`; `/sd [quiet=true] [negative=…] [you|me|scene|last|raw_last|face|background|free prompt]` and a "Generate image…" chat menu entry; the main model writes the keyword prompt from a per-mode instruction (or the card text is used when that is off); prefix with `{prompt}` placeholder, negative prompt. The picture is added as a system message from the character with `inline_image` and the prompt as `title`
- Gallery (`media/GalleryRepository.kt`, `ui/GalleryScreen.kt`): folders and grids per character/group, add from the device, save a copy, delete; reachable from Settings and the chat menu
- Settings screen "Speech, images & attachments"; API keys go to the encrypted secret store (`media:tts`, `media:stt`, `media:image`)
- Verification: 701 JVM unit tests pass (62 new), lint 0 errors, debug APK builds

## Rulings / deviations (set 9)
- Executed on `main`, native, no subagents; UI tasks compile + lint only
- Generated images are system messages (not in the prompt except as their prompt text `[Image: …]`), like ST
- Only the main connection captions images; no extras/local caption models (set 10)
- Not built: video/audio attachments, SD extras (hires fix, ADetailer, upscaler, LoRA lists, per-character prefixes, interactive mode, auto-generation every N messages), local TTS servers other than OpenAI-compatible (Coqui, XTTS, Silero, AllTalk, Edge), streaming TTS, local Whisper/Vosk, wake-word/continuous listening, image swipes, expression sprites (set 10)

## Not verified (set 9)
- Nothing ran on a device/emulator: system TTS, MediaPlayer, SpeechRecognizer, MediaRecorder, the RECORD_AUDIO prompt, image picker + downscaling, grid/viewer dialogs
- HTTP clients are tested against MockWebServer with request shapes written from the services' documentation, not against real A1111/ComfyUI/NovelAI/OpenAI/ElevenLabs/Whisper servers; vision payloads were never sent to a real model
