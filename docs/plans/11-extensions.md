# Extensions (feature set 12) Implementation Plan

**Decision (open question from the roadmap):** third-party extensions are supported through **sandboxed QuickJS scripts** (`app.cash.quickjs`, available on Maven Central), not through a browser DOM. SillyTavern extensions are written against jQuery, the DOM and `getContext()`; none of that exists here, so *unmodified* ST extensions will not run. What runs is a small, explicit, documented API (`st.*`) that covers what extensions actually do for the model: slash commands, macros, prompt injection, chat events, quiet generation and per-extension storage. Features of sets 1-11 stay built in (Kotlin); they are not re-exposed as toggleable extensions.

**Architecture** (new package `extensions/`):
- `ExtensionManifest.kt`: `manifest.json` `{id, name, version, description, author, script (default "index.js"), permissions[]}`; id is `[a-z0-9._-]{1,64}`. Permissions: `network` (`st.fetch`), `generate` (`st.generateQuiet`), `chat` (`st.sendMessage`, `st.addMessage`), `prompt` (`st.setPrompt`), `commands` (`st.registerCommand`, `st.runCommand`), `macros` (`st.registerMacro`); a call without its permission throws in JS.
- `ExtensionRepository.kt`: folder `extensions/<id>/` (manifest, script, `storage.json`); install from a zip (stream; zip-slip safe, size and entry caps, manifest required), list, remove; enabled ids in `AppSettings.extensions.enabled`; granted permissions are per extension and shown before enabling.
- `ScriptEngine.kt`: interface `ScriptEngine { eval(code); dispatch(kind, json): String; close() }` plus `QuickJsEngine` (one single-threaded executor per extension, memory limit, per-call timeout; a timed-out extension is marked faulted and disabled for the session because QuickJS cannot be interrupted).
- `Prelude.kt`: JS that builds the `st` object over one native bridge `__host.call(name, jsonArgs) -> jsonResult`; handlers live in JS (`st.on(event, fn)`, `st.registerCommand(name, fn, help)`, `st.registerMacro(name, fn)`). Synchronous API only.
- `ExtensionHost.kt`: owns engines, routes events (`chat_changed`, `message_sent`, `message_received`, `generation_started`, `generation_ended`), commands and macros into `CommandRegistry` / `MacroRegistry` (prefix-free, can't override built-ins), collects extension prompts for generation, keeps a per-extension error log.
- ChatController hooks: events fired at the existing seams (send, reply finished, chat opened, generation start/end); extension prompts merged next to the other extension prompts.
- UI: `ExtensionsScreen.kt` (list, enable/disable with permission confirmation, install zip, remove, per-extension log and settings JSON), Settings entry.
- `docs/EXTENSIONS.md`: authoring guide with the `st.*` reference and an example.

**Out of scope (documented):** DOM / jQuery / `getContext()` compatibility, ST's server plugins, installing extensions from git URLs, UI panels drawn by extensions, async/Promise APIs, running scripts of disabled extensions, an extension marketplace.

## Tasks
1. Gradle deps (quickjs android + jvm for tests), manifest + repository + settings + tests. Commit `feat(extensions): manifest and repository`
2. Script engine + prelude + tests (against quickjs-jvm). Commit `feat(extensions): QuickJS engine`
3. Host: commands, macros, events, prompts, storage, permissions, limits + tests; ChatController hooks. Commit `feat(extensions): host and chat hooks`
4. UI + EXTENSIONS.md. Commit `feat(extensions): ui and docs`
5. WORKLOG + ROADMAP. Stop and report (do not start set 13).
