# Roadmap to feature parity (SillyTavern v1.19.0)

Sets are small and ordered by dependency. Each set gets a plan in docs/plans/ before implementation.

| # | Set | Features | Requires | Required by |
|---|-----|----------|----------|-------------|
| 1 | MVP | cards, chat, 3 backends, prompt, personas, presets | - | all |
| 2 | Prompt engine (DONE) | instruct/context templates, system-prompt presets, full Prompt Manager (ordering, toggles, injection depth), text-completion samplers/presets import/export | 1 | 3,4,5 |
| 3 | World Info (DONE) | lorebooks (keys, regex, recursion, depth, budget), char-attached books, global books, import/export | 2 | 6 |
| 4 | Backends (DONE) | Google, OpenRouter, Mistral, Cohere, Groq, DeepSeek, etc.; KoboldAI, NovelAI, Horde, Ollama native, tabby/aphrodite; model list fetch; reasoning/thinking display; tool calling | 2 | - |
| 5 | Macros + commands (DONE) | full macro set, variables, slash-command engine, Quick Replies, regex scripts | 2 | 6,12 |
| 6 | Groups + tags (DONE) | group chats, activation strategies, tags/folders, character bulk ops, favorites | 3,5 | - |
| 7 | Chat features (DONE) | message bookmarks/checkpoints/branches, hide messages, chat import/export (ST JSONL, other formats), personas with avatars/lock, author's note, summarization | 2 | 9 |
| 8 | Appearance (DONE) | themes, backgrounds, avatar styles, markdown/HTML rendering, i18n, moving UI, tablet layout | 1 | - |
| 9 | Media extensions (DONE) | TTS (system + cloud), STT, image gen (A1111/Comfy/NovelAI), gallery, attachments, captioning (remote) | 7 | - |
| 10 | Local ML (DONE, no ONNX) | embeddings + vector memory/data bank, expression classifier/sprites, ONNX Runtime Mobile | 7 | - |
| 11 | Utilities (DONE) | translate, web search, assets/content manager, default content, token counter UI, stats | 2 | - |
| 12 | Extensions (DONE, own API) | sandboxed QuickJS extensions with permissions (commands, macros, prompts, events, fetch, storage); no ST browser-extension compatibility | 5 | - |
| 13 | Data & accounts (DONE, local profiles) | backups/restore, data maid, multi-user, optional passcode, SAF-based full export | 1 | - |
| 14 | Android polish (DONE, no WorkManager) | foreground service/notifications, widgets, share-target import, background generation, battery/memory tuning | 1 | - |

Open questions: which local-ML features are worth the on-device cost (set 10).

## Done (feature set 14: Android polish) — plan: docs/plans/13-android-polish.md
- `polish/Polish.kt`: `PolishSettings` (keepAlive, notifyReplies, respectBatterySaver) in `AppSettings.polish`; `GenerationWatcher` turns `ChatUiState` changes into sink calls (running / idle / replyReady only when the app is backgrounded, a new character message exists and there was no error); `RecentChatFinder`; `ShareImporter` (classifies by content: PNG/JSON card, ST/Ooba/CAI/Agnai/Kobold chat); `PowerPolicy`
- `polish/AndroidPolish.kt`: `GenerationService` (foreground, `dataSync`, ongoing notification), `AndroidGenerationSink` (notification channels, "reply ready" notification that opens the chat), `AppForeground` (started-activity counter), `ContinueWidget` (latest chat, tap opens it)
- `MainActivity`: `singleTask`; SEND / SEND_MULTIPLE share target (after the lock screen), notification/widget routes into the nav host; `onTrimMemory` clears Coil's memory cache; Battery Saver stops group auto-mode and delays the start-up auto-backup
- Settings → Android screen (toggles, notification permission request, pin-widget button)
- Verification: 836 JVM unit tests pass (8 new), lint 0 errors, debug APK builds

## Rulings / deviations (set 14)
- Executed on `main`, native, no subagents
- No WorkManager: a reply is kept alive by the foreground service, not resumed after process death
- A shared chat is placed only when exactly one character has the header's name; shared plain text is rejected
- Widget is a plain RemoteViews widget refreshed when a reply ends and by the system on add; no periodic updates

## Not verified (set 14)
- Nothing ran on a device/emulator: the foreground service start (incl. Android 12+ background-start limits, 14+ type rules), notification permission flow, share sheet entry, widget rendering/pinning, `singleTask` + recreate behaviour
- Battery Saver receiver and `onTrimMemory` are untested; the watcher is tested only against a fake sink
- Not built: resume after kill, notification actions, interactive widget, Direct Share, text sharing
