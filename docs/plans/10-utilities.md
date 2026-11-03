# Utilities (feature set 11) Implementation Plan

**Goal:** message translation, web search into the prompt, a content manager with default content, a token counter screen and usage statistics.

**Architecture** (new package `tools/`, pure Kotlin except where noted):
- `ToolsSettings.kt`: `ToolsSettings(translate, search)` stored in `AppSettings.tools`; secret keys `tools:translate`, `tools:search`.
- `Translate.kt`: `TranslateMode` (OFF / RESPONSES / INPUTS / BOTH), providers GOOGLE (free `translate.googleapis.com` gtx endpoint), LIBRETRANSLATE, DEEPL, LLM (main connection, quiet generation). Translations of chat lines are display-only like ST: `extra.display_text` holds the text shown, `mes` stays what the model sees. Inputs are translated *into* the internal language (`mes`) and the original is kept in `display_text`. `/translate [to=xx] text`, message action "Translate" (toggle), auto mode in `ChatController`.
- `WebSearch.kt`: providers SEARXNG, BRAVE, TAVILY, SERPER (all JSON); results formatted into an extension prompt ("Web search results"); runs per generation on the latest user message (optional trigger phrases) and with `/websearch query`.
- `Assets.kt`: `AssetCatalog` parses a catalog JSON (array or `{assets:[…]}` of `{type, id|name|filename, url}`), `AssetInstaller` downloads into the right data folder (backgrounds, characters, worlds, themes → stored in `themes/`). Only http(s) URLs, file names sanitized, size cap.
- `DefaultContent.kt` (+ bundled `assets/default-content/`): Seraphina card, Eldoria world, ST's themes; installed from the manager screen, and offered when the character list is empty.
- `Stats.kt`: scans chat files → per character/group totals (chats, messages, words, swipes, first/last date) and overall totals.
- `TokenTools.kt`: counts for free text with tokenizer choice (approx / OpenAI o200k), and a breakdown of the open character card + chat.
- UI: `ToolsScreen.kt` (translate + search settings), `ContentScreen.kt` (catalog URL, default content), `TokenScreen.kt`, `StatsScreen.kt`; Settings entries.

**Out of scope (documented):** Google Cloud / Azure / Bing / Yandex / Lingva / Onboard / Oneringtranslator providers, DuckDuckGo/Kagi/SerpApi search, scraping pages behind search results, extension-style asset types (ambient/bgm/live2d/vrm), bundled backgrounds (size), per-model tokenizers beyond OpenAI, time-spent statistics.

## Tasks
1. Settings + translate + tests. Commit `feat(tools): translation`
2. Web search + tests. Commit `feat(tools): web search`
3. Assets catalog + default content + tests. Commit `feat(tools): content manager`
4. Stats + token tools + tests. Commit `feat(tools): stats and token counter`
5. UI. Commit `feat(tools): ui`
6. WORKLOG + ROADMAP. Stop and report (do not start set 12).
