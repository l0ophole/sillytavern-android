# Appearance (feature set 8) Implementation Plan

**Goal:** themes (system/light/dark/AMOLED + custom colors + ST theme import/export), chat backgrounds, avatar styles and message styles, markdown + basic HTML rendering in messages, UI translation (ST-style English-key locale files), tablet layout (centered chat column with a width setting).

**Architecture:** pure, unit-tested logic in `appearance/` (`Markdown.kt` block/inline parser → model; `ThemeIo.kt` ST theme JSON ↔ `AppearanceSettings`; `Translator.kt` key→text lookup with `{0}` style fallback to the English key). `data/Settings.kt` gets `AppearanceSettings`. UI: `ui/MarkdownText.kt` (model → Compose), `ui/AppearanceScreen.kt`, themed `SillyTheme`, `ChatScreen` bubble rework (avatars, styles, background, width cap). Backgrounds live in `backgrounds/` (ST dir name) managed by `appearance/BackgroundRepository.kt`.

**Out of scope (documented deviations):** "moving UI" (draggable panels: no meaning on a phone), ST's CSS custom-CSS box, blur/shadow sliders, full translation of every screen (the Translator is applied to the chat screen, settings and appearance screens; other screens stay English), HTML beyond inline formatting tags (no `<style>`, `<script>`, iframes, tables).

## Tasks
1. Markdown/HTML parser + tests. Commit `feat(appearance): markdown parser`
2. AppearanceSettings, ST theme import/export, color parsing + tests. Commit `feat(appearance): settings and ST theme io`
3. Translator + locale assets (es, fr, de) + tests. Commit `feat(appearance): i18n`
4. UI: themed app, MarkdownText, bubbles (avatars/styles/timestamps/numbers), backgrounds, tablet width, AppearanceScreen, nav. Commit `feat(appearance): theme, backgrounds, avatars, markdown rendering and layout`
5. WORKLOG + ROADMAP. Stop and report (do not start set 9).
