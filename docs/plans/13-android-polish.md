# Android polish (feature set 14) Implementation Plan

**Goal:** make the app behave like a good Android citizen: finish a reply when the user leaves the app, tell them when it is done, accept files shared from other apps, offer a home-screen shortcut, and be gentle on memory and battery.

**Decisions**
- *Background generation* already survives as long as the process lives (the controller runs in the container scope, not the activity). The risk is the system killing the process, so while a reply is generating a **foreground service** (`GenerationService`, type `dataSync`, ongoing notification with the character name) keeps it alive. The service holds no state and does no work; it is started/stopped by `GenerationWatcher` from `ChatController.state`.
- A reply that finishes while the app is in the background posts a **"reply ready" notification** (character + first 120 chars) that opens that chat. Foreground is tracked with `ActivityLifecycleCallbacks` (started-activity count), no extra dependency. `POST_NOTIFICATIONS` is requested when the user switches the option on (Android 13+); without it the service still runs and the notification is just not shown.
- **Share target:** `ACTION_SEND` / `ACTION_SEND_MULTIPLE` for `image/png`, `application/json`, `text/plain`, `application/octet-stream`. `ShareImporter` classifies by content, not by mime (PNG signature → card; JSON with card keys → card; ST/Ooba/... chat → chat). A chat is saved under the character named in its header only if exactly one character has that name; otherwise it is reported as not placed. Plain text is not imported. Sharing is handled only after the lock screen is passed.
- **Widget:** one "Continue" widget (`ContinueWidget`) showing the most recently written chat's character name and last line; tap opens that chat. `RecentChat.find(paths)` reads the newest chat file across characters (pure, testable). It is refreshed when a reply ends.
- **Memory/battery:** `onTrimMemory(>= BACKGROUND)` clears Coil's memory cache; while Battery Saver is on, group auto-mode is stopped and auto-backups wait (both switchable via `PolishSettings.respectBatterySaver`).
- Settings: `PolishSettings(keepAlive=true, notifyReplies=true, respectBatterySaver=true)` in `AppSettings.polish`; a section on the existing Data/Tools-style screen `PolishScreen`.

**Out of scope (documented):** WorkManager-scheduled generation while the process is dead, resuming an interrupted stream after process death, per-chat notification channels, quick-reply/action buttons on the notification, interactive widgets, Direct Share shortcuts, text sharing into a draft, tablet-specific multi-window handling beyond what set 8 did.

## Tasks
1. `polish/` pure logic (watcher, share importer, recent chat, settings) + tests. Commit `feat(polish): watcher, share import, recent chat`
2. Android glue: service, notifier, manifest, share handling, widget, trim/battery hooks. Commit `feat(polish): service, share target, widget`
3. Polish screen + Settings entry. Commit `feat(polish): ui`
4. WORKLOG + ROADMAP. Stop and report.
