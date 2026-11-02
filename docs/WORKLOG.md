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
