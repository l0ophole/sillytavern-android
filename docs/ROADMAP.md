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
| 7 | Chat features | message bookmarks/checkpoints/branches, hide messages, chat import/export (ST JSONL, other formats), personas with avatars/lock, author's note, summarization | 2 | 9 |
| 8 | Appearance | themes, backgrounds, avatar styles, markdown/HTML rendering, i18n, moving UI, tablet layout | 1 | - |
| 9 | Media extensions | TTS (system + cloud), STT, image gen (A1111/Comfy/NovelAI), gallery, attachments, captioning (remote) | 7 | - |
| 10 | Local ML | embeddings + vector memory/data bank, expression classifier/sprites, ONNX Runtime Mobile | 7 | - |
| 11 | Utilities | translate, web search, assets/content manager, default content, token counter UI, stats | 2 | - |
| 12 | Extensions | extension system (Kotlin-defined + QuickJS scripting for third-party JS - decision pending) | 5 | - |
| 13 | Data & accounts | backups/restore, data maid, multi-user, optional passcode, SAF-based full export | 1 | - |
| 14 | Android polish | foreground service/notifications, widgets, share-target import, background generation, battery/memory tuning | 1 | - |

Open questions: how far to go on third-party JS extensions (set 12); which local-ML features are worth the on-device cost (set 10).
