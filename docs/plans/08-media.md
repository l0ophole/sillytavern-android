# Media extensions (feature set 9) Implementation Plan

**Goal:** image/text attachments (sent to vision models, or captioned to text), remote captioning, text-to-speech (Android system + OpenAI-compatible + ElevenLabs), speech-to-text (Android recognizer + Whisper-compatible API), image generation (A1111, ComfyUI, NovelAI, OpenAI-compatible) with `/sd`, and a per-character gallery.

**Architecture:** pure, unit-tested logic in `media/` (no Android types): `MediaSettings.kt` (settings model, in `AppSettings.media`), `MessageMedia.kt` (ST `extra.image` / `extra.inline_image` / `extra.title` / `extra.file` helpers + prompt markers), `MediaPrompt.kt` (turns prompt markers into image parts or caption text), `TtsText.kt` (what gets spoken) + `TtsClients.kt` (HTTP requests), `SttClients.kt` (Whisper multipart), `ImageGen.kt` (prompt templates, A1111/Comfy/NovelAI/OpenAI request + response parsing, zip extraction), `GalleryRepository.kt`. Android glue behind small interfaces (`SpeechPlayer`, `Recorder`) in `media/AndroidMedia.kt`. UI: `MediaSettingsScreen`, `GalleryScreen`, chat input attach/mic buttons, message image + action menu entries.

**Prompt images:** `PromptSupport.speaker` appends a marker `⟦img:<file>⟧` for messages with an image when `PromptInput.media` says images go to the model. `MediaPrompt.resolve` (run in `ChatController.makeRequest`) turns markers into `PromptMessage.images` (data URLs) for chat backends, or into `[caption]` text when the profile is text-completion / "caption instead" is on, and strips them otherwise. OpenAI, Anthropic and Gemini serializers send image parts.

**Out of scope (documented deviations):** video/audio attachments, the Data Bank (set 10), Stable Diffusion extras (hires fix, ADetailer, upscalers, LoRA lists, per-character prompt prefixes UI beyond a prefix field), Vosk/Whisper-local STT, Coqui/XTTS/Silero/AllTalk/Edge local TTS servers (an OpenAI-compatible URL covers most of them), streaming TTS, expression/sprite prompts, captioning through the ST "extras" server or local models (set 10).

## Tasks
1. Models + message media helpers + prompt markers/resolve + vision serializers + tests. Commit `feat(media): attachments and vision prompts`
2. Captioning (remote, via active connection) + `/caption`. Commit `feat(media): captioning`
3. TTS: text preparation, OpenAI/ElevenLabs clients, system TTS glue, voice map, `/speak`, auto-play. Commit `feat(media): text to speech`
4. STT: Whisper client, system recognizer glue, mic button. Commit `feat(media): speech to text`
5. Image generation: prompt generation, four sources, saving, `/sd`. Commit `feat(media): image generation`
6. Gallery repository + screen. Commit `feat(media): gallery`
7. UI wiring (settings screen, chat input, message images/actions, nav, manifest permissions). Commit `feat(media): ui`
8. WORKLOG + ROADMAP. Stop and report (do not start set 10).
