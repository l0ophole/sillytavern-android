# Handoff: what is left (as of commit after ef188e0)

State: sets 1-15 are done and pushed to `origin/main` (github.com/l0ophole/sillytavern-android). 839 JVM tests pass, lint is clean, debug APK builds. Set 15 (device verification) is **partly done**: the items below are what is left. Everything verified so far is recorded in `docs/ROADMAP.md` under "Done (feature set 15)".

## Remaining device checks (each needs a real or emulated Android device)
1. ~~Notification tap-through~~ done warm and cold on the phone (see ROADMAP); only with a single chat.
2. **Real `content://` share.** Share a character card PNG/JSON from a real app (Files, Drive, a browser download) to SillyTavern. Only a `file://` URI was tested because adb cannot grant URI read permission.
3. **TTS (system + cloud) and STT.** Needs RECORD_AUDIO grant and a speech service; check the permission prompt and that speech works.
4. **Image generation** (A1111/ComfyUI/NovelAI) against a reachable backend, plus the gallery and attachments.
5. ~~Tablet layout~~ done at 1600x2560 via `wm size` (see ROADMAP); real foldable and landscape not tried.
6. ~~Remaining Extensions API~~ done on the phone (see ROADMAP); `st.off`, storage across restarts and the 5 s timeout were not run.
7. **Other flows never run on a device**: group chats, World Info in a real generation, Quick Replies, regex scripts in a chat, translation/web search, content manager downloads, embeddings/vector memory, Battery Saver behaviour, `onTrimMemory`.
8. **A second physical device / other Android versions** (tested: Android 14 arm64 phone `B170D`, Android 15 x86_64 emulator).

## Known small issues (not fixed)
- Widget has no preview image (pin dialog shows a placeholder) and its snippet shows raw markdown `*`.
- After `adb shell am force-stop`, tapping the widget opens the app on its old screen (force-stop cancels widget intents; probably harmless, not proven).
- A slash command's return value is only visible if echoed (`/wc a b | /echo {{pipe}}`); by design.
- Cleartext HTTP is allowed app-wide (`usesCleartextTraffic`) because backends are user-chosen LAN servers.
- The emulator test profile may still contain duplicate "hi" messages from a crash fixed earlier.

## Not built / next big items (from the set 14 rulings)
- Resume a reply after process death (no WorkManager), notification actions, interactive widget, Direct Share, text sharing.
- **Release readiness** (suggested set 16): signed release build, R8/ProGuard rules (check QuickJS, Coil, kotlinx.serialization), launcher icon (still the default Android robot), README, versioning. Run the app from a **release** build on the phone: minification bugs only show there.
- Open question from the roadmap: which local-ML features are worth the on-device cost (no ONNX in set 10).

## How to run the device tests
- Build: `JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/rpf/tmp" ./gradlew --offline :app:assembleDebug` (`/tmp` has a small quota; use `/rpf/tmp`). Tests: `:app:testDebugUnitTest`, lint: `:app:lintDebug`.
- adb: `export PATH=$PATH:$HOME/Android/Sdk/platform-tools`; `adb devices`. The physical phone is authorized (720x1600, Android 14). Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`; package `app.sillytavern.android`, activity `.MainActivity`.
- Grant notifications: `adb shell pm grant app.sillytavern.android android.permission.POST_NOTIFICATIONS`.
- `scripts/device/ui.sh` drives the UI by visible text (uiautomator dump): `ui.sh tap "Settings" [nth]`, `ui.sh texts [n]`, `ui.sh shot name` (set `SHOT_DIR`). Gotchas: `&` shows as `&amp;` and quotes as `&quot;` in the dump (search a word without them); "Back" also matches body text, use `"‹ Back"`; `input text` needs `%s` for spaces and `\|` for a pipe; the keyboard moves the Send button (tap it while the keyboard is up, ~y=637, or press BACK first, ~y=1481); a long list needs `adb shell input swipe 360 1300 360 300 300`.
- Mock backend: `python3 scripts/device/mock_backend.py` (port 8765, OpenAI-style `/v1/models` and a slow SSE `/v1/chat/completions`). Emulator reaches it at `http://10.0.2.2:8765/v1`; the phone needs the host's LAN IP (e.g. `http://192.168.1.133:8765/v1`, check with `ip -4 addr`). Add it in Settings → Connections → Add (OpenAI-compatible, any key).
- Emulator: AVD `st35` (API 35 x86_64), created by hand under `~/.android/avd`; start headless with `~/Android/Sdk/emulator/emulator -avd st35 -no-window -gpu swiftshader_indirect -no-audio &`, wait for `adb shell getprop sys.boot_completed` = 1 and let it settle a few minutes before testing (ANR dialogs while cold are CPU load, not app bugs). Stop with `adb emu kill`.
- Device tests are the only way to find the bugs JVM tests cannot (set 15 found four: cleartext HTTP, ICU regex braces, notification logic, passcode hashing on the UI thread). Watch `adb logcat -d | grep -i "AndroidRuntime\|ANR in"` after each flow, and fix + commit anything found with a regression test where one is possible.

## Rules for the next session
- Work on `main`, commit small, push to `origin`. Commit messages end with the Co-Authored-By / Claude-Session lines the harness gives.
- Update `docs/ROADMAP.md` (Done / Not verified) and `docs/WORKLOG.md` after each batch of checks.
