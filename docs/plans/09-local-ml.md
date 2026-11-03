# Local ML (feature set 10) Implementation Plan

**Goal:** vector memory of the chat, a Data Bank of documents retrieved by similarity, and expression sprites chosen by an emotion classifier.

**Deviation from the roadmap: no ONNX Runtime in this set.** The ONNX Runtime AAR and a model file (MiniLM for embeddings, a GoEmotions classifier) cannot be fetched by the offline build, need a WordPiece tokenizer, and could not be run here (no device). Instead the features sit behind small interfaces (`Embedder`, `ExpressionClassifier`) with implementations that work and are testable now:
- embeddings: any OpenAI-compatible `/embeddings` server (OpenAI, Ollama's OpenAI endpoint, llama.cpp, vLLM, Together…), Ollama native `/api/embed`, or a built-in pure-Kotlin hashing embedder (word + character n-grams, no download, lexical similarity only);
- expressions: a built-in keyword classifier, or the main connection classifying the text with a prompt.
An ONNX implementation can be added later by implementing the same interfaces.

**Architecture** (new package `memory/`, pure Kotlin, no Android types):
- `MemorySettings.kt`: `MemorySettings(vector, dataBank, expressions)` stored in `AppSettings.memory`; secret key `memory:embeddings`.
- `Embedder.kt`: `Embedder`, `HashingEmbedder`, `RemoteEmbedder`; `Vectors` (cosine, normalize).
- `TextChunker.kt`: paragraph/sentence chunking with size and overlap.
- `VectorStore.kt`: per-collection binary file under `user/vectors/`, cosine top-K with score threshold; an index built by another embedder is dropped.
- `VectorMemory.kt`: indexes chat messages, retrieves past messages for the latest messages, formats `Past events` prompt (ST `vectors` extension).
- `DataBank.kt`: documents with global / character / chat scope under `user/data-bank/`, chunked and indexed, retrieved into the prompt.
- `Expressions.kt`: ST's 28 labels, `KeywordClassifier`, `LlmClassifier`, `SpriteRepository` (`user/sprites/<character>/<label>.<ext>`), fallback expression.
- Retrieval is async, so `ChatController` computes the memory prompts before building the request and passes them to `extensionPromptsFor`.

**Out of scope (documented):** ONNX/in-app models, HNSW (brute-force cosine is enough for chat-sized stores), web-link / YouTube / Fandom scraping into the Data Bank, PDF/EPUB/HTML text extraction (plain text and Markdown only), sprite packs from zip, talking-head/VRM/Live2D, image classifier "classify by image" mode, per-message sprite history.

## Tasks
1. Settings + embedders + chunker + vector store + tests. Commit `feat(memory): embeddings and vector store`
2. Chat vector memory + prompt injection + tests. Commit `feat(memory): vector memory`
3. Data Bank + tests. Commit `feat(memory): data bank`
4. Expressions: classifiers, sprites, controller hook, commands + tests. Commit `feat(memory): expressions and sprites`
5. UI: memory settings, data bank screen, sprite panel in chat. Commit `feat(memory): ui`
6. WORKLOG + ROADMAP. Stop and report (do not start set 11).
