# SillyTavern Android Port — Stack & MVP Plan

## Context
`docs/ANDROID.md` asks for an Android port of SillyTavern (v1.19.0: Node/Express server ~34k LOC + vanilla-JS frontend ~150k LOC) with full feature parity, built incrementally (MVP first), git-tracked, one commit per feature, a plan file per feature set, a work log, and a pause for user confirmation after each set. The user chose a **fully native UI** (no embedded Node, no WebView of the original frontend).

Caveat to confirm: third-party JS extensions and the slash-command/macro scripting engine can't run unchanged in a native UI. Parity there needs a Kotlin reimplementation of the built-in extensions + slash commands/macros, and either a sandboxed JS engine (QuickJS) for scripting or an explicit "third-party JS extensions out of scope" decision.

## Recommended stack (to confirm)
- **Language:** Kotlin (100%); Gradle Kotlin DSL, minSdk 26, targetSdk latest.
- **UI:** Jetpack Compose + Material 3, Navigation-Compose, adaptive layouts for tablets/foldables.
- **Architecture:** MVVM + unidirectional state, Hilt for DI, Coroutines/Flow.
- **Networking:** OkHttp + Ktor client; SSE streaming via OkHttp-SSE; kotlinx.serialization (JSON/JSONL).
- **Storage:** Keep SillyTavern-compatible on-disk formats (PNG cards with `chara`/`ccv3` tEXt chunks, JSONL chats, world-info JSON, preset JSON) in app-private storage for import/export parity; Room only for indexes/search; DataStore for settings; Android Keystore + EncryptedFile for API keys (replaces `secrets.json`); Storage Access Framework for import/export/backup.
- **Images:** Coil; own PNG chunk read/write (small, pure Kotlin).
- **Tokenizers:** jtokkit (tiktoken port) for OpenAI; sentencepiece via JNI/ONNX for Llama/Mistral/etc.; remote-count fallback.
- **Local ML (later phases):** ONNX Runtime Mobile for embeddings, emotion classification, captioning.
- **Vector search:** Room/SQLite + own cosine search (replaces vectra).
- **Scripting (later):** QuickJS (Kotlin binding) or Kotlin DSL for slash commands/macros.
- **Tests:** JUnit5 + Turbine + Compose UI tests; golden-file tests against SillyTavern fixtures (cards, chats, prompt-converter output).
- **Background:** Foreground service for long generations; WorkManager for backups.

## MVP scope (first feature set)
Goal: a usable single-user chat app.
1. Project scaffold, git init, `docs/` plan + `docs/WORKLOG.md` + `docs/ROADMAP.md`.
2. Storage layer: user data dir, settings, encrypted API-key store.
3. Character cards: import/export PNG & JSON (V2/V3), list/search, create/edit, avatars.
4. Chat: send/receive, streaming, swipes, regenerate, edit/delete messages, JSONL persistence, multiple chats per character, continue/impersonate.
5. Connections: OpenAI-compatible Chat Completions, Anthropic, and text-completion (llama.cpp/ooba/Ollama/KoboldCpp-style) backends with core sampler settings.
6. Prompt building: system prompt, character fields, persona, context-size trimming, token counting, basic macros (`{{char}}`, `{{user}}`).
7. Personas (basic), basic Chat Completion presets.
Deferred to later sets: groups, World Info, full Prompt Manager, instruct/context templates, themes, TTS, image gen, vectors, expressions, translate, extensions, multi-user, backups, slash commands, remaining backends.

## Roadmap approach (after MVP approval)
Build a feature dependency table (feature → requires / required-by) from `src/` endpoints and `public/scripts/extensions`, then group into small sets, e.g.: (2) World Info + advanced prompt manager + presets/templates, (3) groups + tags, (4) remaining backends (Google, OpenRouter, Mistral, NovelAI, Horde, …), (5) macros + slash commands + quick replies, (6) theming/backgrounds/UI customization, (7) TTS/STT, (8) image generation (A1111/Comfy/NovelAI), (9) local ML: vectors, expressions, caption, (10) translate, search, assets, gallery, (11) backups/data maid/stats, multi-user/auth, (12) extension system. Each set gets its own plan file (kept, never deleted), a commit per feature, a WORKLOG update, and a summary + confirmation prompt.

## Process rules (from ANDROID.md)
git for source control; add+commit per feature; one feature set at a time; plan saved to project dir before each set; summary + ask to proceed after each set (including MVP); maintain work-done / work-remaining log.

## Verification
Per feature: unit tests (golden fixtures from `SillyTavern/default/content`), Gradle build + lint, Compose UI tests, and manual run on an emulator against a real backend; compare card/chat import-export round-trips with the original app.

## First steps once approved
Save this as `docs/plans/00-stack-and-mvp.md` in the project, `git init` the Android project directory, scaffold the app, then implement MVP items in order.
