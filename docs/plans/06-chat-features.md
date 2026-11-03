# Chat features (feature set 7) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline). Steps use checkbox syntax.

**Goal:** Author's Note, extension-prompt injection (shared with summaries and persona positions), personas with avatars/positions/locks/default, branches + checkpoints, hide/unhide in the UI, chat import (ST JSONL, Ooba, Agnai, CAI Tools, Kobold Lite) and export (JSONL, text), and chat summarization (manual + automatic through the main API).

**Architecture:** `chat/ChatMeta.kt` gives typed access to `chat_metadata` keys (`note_prompt`, `note_interval`, `note_depth`, `note_position`, `note_role`, `main_chat`, `persona`). `prompt/ExtensionPrompt.kt` is a small model (`id, content, position IN_PROMPT=0/IN_CHAT=1/BEFORE_PROMPT=2, depth, role`) carried by `PromptInput.extensionPrompts`; both prompt builders place them (chat completion: before/after the `main` prompt, or at depth in the history; text completion: `anchorBefore`/`anchorAfter` or at depth). `chat/AuthorsNote.kt` (interval rule, character note, persona TOP_AN/BOTTOM_AN merge), `chat/Summary.kt` (latest `extra.memory`, prompt, template) and `chat/ChatBranching.kt` (snapshots, names) are pure. `chat/ChatIo.kt` is pure (import converters, text export). `ChatController` wires them; UI adds dialogs to the chat menu and message actions.

**Tech Stack:** Kotlin, kotlinx.serialization JSON, Compose M3. No new dependencies. All 496 existing tests keep passing.

**Behavior reference** (`/mnt/ide/teamssd/part1/projects/sillytavern_repo/SillyTavern/`): `public/scripts/authors-note.js`, `bookmarks.js`, `personas.js`, `extensions/memory/index.js`, `public/script.js` (`addPersonaDescriptionExtensionPrompt`), `public/scripts/openai.js` (`injectToMain`, `getPromptPosition`), `src/endpoints/chats.js` (import/export).

## Global Constraints

- Chat file stays ST compatible: chat metadata keys as above, `extra.memory` (summary), `extra.bookmark_link` (checkpoint), `extra.branches`, `extra.hidden`. Unknown keys preserved.
- Author's Note: interval counts user messages (`interval 1` = always, `0` = never); prompt only when `count % interval == 0`; character note (`useChara`, position replace/before/after) stored in settings per character file name.
- Persona description positions: 0 IN_PROMPT, 2 TOP_AN, 3 BOTTOM_AN, 4 AT_DEPTH, 5 NONE; TOP/BOTTOM_AN only apply when the Author's Note is due. Locks: chat lock = `chat_metadata.persona`; character connections on the persona; default persona; precedence chat > character > default > current.
- Out of scope: RisuAI/Chub chat import, Extras/WebLLM summary sources, raw-prompt summary builders, summary word-limit forcing, Author's Note WI scanning, persona avatar in message bubbles (set 8), `/checkpoint-*` beyond create/go/exit/get/list, persona import/export (ST `settings.json` personas).
- One commit per task; update WORKLOG and ROADMAP at the end; stop and report (do not start set 8).

## Review Focus

- Branching at message N copies messages `0..N` only, new file name unique, source message gets `extra.branches`; a checkpoint re-created on the same message replaces its link; opening a checkpoint of a group works in group mode.
- Author's Note with interval 3: due at user message counts 3, 6, ...; never when the note is blank; regenerate does not change the count.
- Summary is taken from the newest message in the *prompt history* carrying `extra.memory`, so regenerating an older message does not see a later summary.
- Persona lock never leaves a dangling id (deleted persona is skipped); a locked chat switches persona on open and does not rewrite the lock.
- Import never executes a path from the file; names are sanitised; a bad file gives an error, not a crash.

---

### Task 1: Extension prompts, Author's Note, summary injection

**Files:** Create `prompt/ExtensionPrompt.kt`, `chat/ChatMeta.kt`, `chat/AuthorsNote.kt`, `chat/Summary.kt`; modify `prompt/PromptBuilder.kt`, `prompt/PromptManagerBuilder.kt`, `data/Settings.kt` (`NoteSettings`, `characterNotes`, `SummarySettings`), `chat/ChatController.kt`, `chat/Macros.kt`. Tests: `chat/AuthorsNoteTest.kt`, `chat/SummaryTest.kt`, `prompt/ExtensionPromptTest.kt`.

- [ ] Tests first (interval rule, char note merge, summary lookup/template, placement in both builders at depth/before/after).
- [ ] Implement; controller computes `extensionPrompts` for every generation (summary id `summary`, note id `authorsNote`, persona depth id `persona`).
- [ ] Commit `feat(chat): extension prompts, author's note and summary injection`.

### Task 2: Personas (avatar, position, lock, default)

Files: `data/Settings.kt` (Persona fields, `defaultPersonaId`, `personaAutoLock`), `data/StoragePaths.kt`, new `chat/PersonaLock.kt` (pure resolution), controller (apply on open, lock/unlock), `ui/SettingsScreen.kt` persona dialog, chat menu "Persona lock". Tests `chat/PersonaLockTest.kt`, extra builder tests for position/depth.

- [ ] Tests, implement, commit `feat(persona): avatars, description position, locks and default persona`.

### Task 3: Branches, checkpoints, hide

Files: `chat/ChatBranching.kt`, controller (`branch`, `checkpoint`, `openCheckpoint`, `backToMain`, `setHidden`), `command/ChatCommands.kt` (+ host members), UI message actions (Hide/Unhide, Branch, Checkpoint, Open checkpoint), flag marker on bubbles, chat menu "Back to main chat". Tests `chat/ChatBranchingTest.kt`, controller tests.

- [ ] Tests, implement, commit `feat(chat): branches, checkpoints and hide controls`.

### Task 4: Chat import / export

Files: `chat/ChatIo.kt`, `chat/ChatRepository.kt` (`importText`), controller (`importChat`, `exportChat`), chats dialog (Import, Export JSONL/Text). Tests `chat/ChatIoTest.kt`.

- [ ] Tests (each format), implement, commit `feat(chat): chat import and export`.

### Task 5: Summarization

Files: `chat/Summary.kt` (prompt build, trigger rule), controller (`summarizeNow`, auto after AI reply, `setSummary`, freeze), `command/` `/summarize`, UI summary dialog. Tests for trigger and storage.

- [ ] Tests, implement, commit `feat(chat): chat summarization`.

### Task 6: UI wiring, docs, verification

- [ ] Author's Note dialog + defaults in settings, Summary settings, wire menus. `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- [ ] Update `docs/WORKLOG.md`, `docs/ROADMAP.md`; commit `docs: work log and roadmap for set 7`.
