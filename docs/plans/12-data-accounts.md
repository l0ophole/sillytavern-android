# Data & accounts (feature set 13) Implementation Plan

**Goal:** back up and restore everything, tidy leftover files, several local profiles, and an optional passcode.

**Decisions**
- A *user* is a separate data root. `default` keeps the existing `filesDir/data` (no migration); other users live in `filesDir/users/<handle>/data`. The registry is `filesDir/accounts.json`. Switching users rebuilds `AppContainer` and recreates the activity, so no repository ever holds paths of two users.
- Multi-user here means local profiles on one device, not ST's server accounts: no network login, no admin/roles. The passcode is a per-user lock against casual access (PBKDF2-HMAC-SHA256, random salt, 120k iterations, stored in `accounts.json`); data on disk is **not** encrypted by it. The unlock state lives in memory and is asked again on every cold start.
- Backups are zips of the user's data root with a `st-android-backup.json` header. `secrets.enc.json` is **excluded**: it is bound to this device's Keystore key and would be undecryptable elsewhere, so API keys must be re-entered after a restore on another device. Restore is zip-slip safe with entry/size caps, extracts to a staging folder, validates the header, then swaps folders and restarts the container. The live data is kept as `data.before-restore` until the next successful start.
- SAF: export writes to a `CreateDocument` URI and import reads an `OpenDocument` URI (any provider: Drive, SD card, USB). Auto-backups go to app-private `filesDir/backups/<handle>/` (rotating, keep N) and can be shared out with the same export button.

**Architecture** (package `data/`, pure Kotlin except the screens)
- `Backup.kt`: `BackupService(paths)`: `write(out)`, `inspect(in)`, `restore(in)`; `AutoBackup(dir, keep, now)` with `runIfDue(intervalHours)` and `list()`.
- `DataMaid.kt`: scan → `MaidFinding(kind, path, bytes)` for stray `*.tmp`, chat folders of deleted characters, avatars no persona uses, empty folders; `clean(findings)` deletes selected, never outside the data root.
- `Accounts.kt`: `AccountRegistry(file, filesDir)` with `list/create/delete/rename/setActive/active`, `Passcode` hashing/verify, `pathsFor(handle)`.
- `DataSettings(autoBackup=false, intervalHours=24, keep=5)` in `AppSettings.data`.
- `App.kt`: `AppContainer(app, account)`, `SillyTavernApp.switchTo(handle)`; auto-backup on start.
- UI: `DataScreen.kt` (export, import, auto-backup, data maid), `AccountsScreen.kt` (users, passcode, lock now), `LockScreen` in `MainActivity` before `AppNav`; Settings entries.

**Out of scope (documented):** encrypting data at rest, per-user API-key migration, admin roles / user avatars / passwords sent over a network, ST server-backup import (`/backups` zips from a desktop install are importable only as a plain data folder zip with the same layout), cloud sync, selective (per-category) restore, scheduled backups while the app is closed.

## Tasks
1. Backup/restore + auto-backup + tests. Commit `feat(data): backup and restore`
2. Data maid + tests. Commit `feat(data): data maid`
3. Accounts + passcode + container switching + tests. Commit `feat(accounts): users and passcode`
4. UI. Commit `feat(data): ui`
5. WORKLOG + ROADMAP. Stop and report (do not start set 14).
