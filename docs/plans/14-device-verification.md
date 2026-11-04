# Device verification (feature set 15)

**Goal:** run the app for the first time on an Android system image and fix whatever the 836 JVM tests could not see.

**Setup:** API 35 google_apis x86_64 image, AVD created by hand, headless with software GPU. A small Python server on the host speaks OpenAI-style `/v1/models` and slow SSE `/v1/chat/completions`; the emulator reaches it at `10.0.2.2`. Intents and taps are driven with `adb`.

**Checks:** cold start; install default character; open each Settings screen; share-target import; add a connection, fetch models; send a message; background the app mid-reply and look at the foreground service and notification.

**Out of scope:** physical device, widget pinning, extensions on Android, SAF pickers, lock screen, media features.
