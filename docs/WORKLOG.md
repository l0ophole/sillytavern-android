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
