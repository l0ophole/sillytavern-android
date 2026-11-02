# Backends (feature set 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the remaining SillyTavern generation backends (Google Gemini, OpenAI-compatible providers, Ollama, KoboldCpp/KoboldAI, NovelAI, AI Horde), model-list fetching, reasoning/thinking capture and display, and tool-calling plumbing.

**Architecture:** `Backend.generate(): Flow<String>` stays (back-compat); a new `ChunkBackend : Backend` exposes `chunks(): Flow<Chunk>` (`Text`, `Reasoning`, `ToolCall`). `HttpBackend` is refactored so each backend supplies a per-call `EventParser` (stateful, so streamed tool-call fragments can be assembled) and a `StreamFormat` (SSE or NDJSON). A `Providers` table maps a provider id (openrouter, mistral, …) to a `BackendType` + default URL + quirks. `ModelLister` fetches model ids. `ToolLoop` runs request → tool calls → registry → follow-up request. `ChatController` stores reasoning in `extra.reasoning` (ST's key) and the chat bubble shows it collapsed.

**Tech Stack:** Kotlin, kotlinx.serialization JSON, OkHttp + MockWebServer tests, Compose M3. No new dependencies.

**Spec:** `docs/ROADMAP.md` row 4. Behavior reference: `SillyTavern/src/endpoints/backends/chat-completions.js`, `src/endpoints/backends/text-completions.js`, `src/endpoints/novelai.js`, `src/endpoints/horde.js`, `src/constants.js` (`CHAT_COMPLETION_SOURCES`, `TEXTGEN_TYPES`), `public/scripts/openai.js`. ST repo: `/mnt/ide/teamssd/part1/projects/sillytavern_repo/SillyTavern`.

## Global Constraints

- Package `app.sillytavern.android.api` (+ small additions in `data/`, `chat/`, `ui/`); minSdk 26; no new dependencies; JUnit4 + MockWebServer.
- Existing `BackendType` enum names (`OPENAI, ANTHROPIC, TEXT_COMPLETION`) and persisted `ConnectionProfile` JSON keep working (new fields have defaults; new enum values are appended).
- Existing 193 tests keep passing. Backends are tested against MockWebServer only — no real API calls.
- Reasoning is stored per message in `extra.reasoning` (string), as ST does.
- One commit per task; update `docs/WORKLOG.md` and ROADMAP at the end; stop and report (do not start set 5).
- Out of scope: ST server-side features (proxy/CORS, per-source token-count endpoints), Claude prefill/cache controls, image/audio inputs, OAuth providers, Horde worker/kudos selection UI beyond a model name, NovelAI preset library, Vertex AI auth.

## Review Focus

- Stream that ends without `[DONE]` / `message_stop` / `done:true`: flow completes normally with what was received.
- Empty/missing `choices`, `candidates`, `message` in an event (keep-alive, usage-only chunks): ignored, no crash.
- Tool-call arguments split across many stream events, and two parallel calls with different `index`: assembled separately and in order.
- Reasoning-only response (no text) and text without reasoning: both work; reasoning never leaks into the message text.
- HTTP error body that is not JSON, or Gemini's `{"error":{"message"}}` / Ollama's `{"error":"…"}`: surfaced as `BackendException` with the message.
- Model-list response with unexpected shape / empty list: returns empty list, never throws.
- Horde: job faulted / queue wait, cancel during polling issues a DELETE and stops.

---

### Task 1: Chunk model, parser refactor, reasoning in OpenAI/Anthropic

**Files:**
- Modify: `api/Backends.kt`; `data/Settings.kt` (add `ReasoningSettings`, `ConnectionProfile.reasoning`)
- Test: `test/.../api/BackendTest.kt` (extend)

**Interfaces:**
- Produces: `sealed interface Chunk { data class Text(val text: String); data class Reasoning(val text: String); data class ToolCall(val id: String, val name: String, val arguments: String) }`.
- Produces: `interface ChunkBackend : Backend { fun chunks(req: GenerationRequest): Flow<Chunk>; override fun generate(req) = chunks(req).filterIsInstance<Chunk.Text>().map { it.text } }`.
- Produces: `interface EventParser { fun parse(event: JsonObject): List<Chunk>; fun finish(): List<Chunk> = emptyList(); fun isDone(event: JsonObject): Boolean = false }`; `enum class StreamFormat { SSE, NDJSON }`; `HttpBackend` now has `protected abstract fun parser(streaming: Boolean): EventParser`, `protected open val streamFormat = StreamFormat.SSE`, `protected open fun nonStreamParse` removed (non-stream = one `parse` call on the whole body).
- Produces: `@Serializable data class ReasoningSettings(val effort: String = "auto", val budgetTokens: Int = 0)`; effort in `auto|min|low|medium|high|max`. `auto` sends nothing.
- OpenAI parser: text from `delta.content`/`message.content`; reasoning from `delta.reasoning_content` or `delta.reasoning` (also `message.*`). Anthropic parser: `thinking_delta` → Reasoning; `text_delta` → Text; non-stream `content[]` blocks `thinking`/`text`. Anthropic request adds `thinking:{type:"enabled",budget_tokens}` when `reasoning.budgetTokens > 0` (then temperature omitted, `max_tokens` ≥ budget+1).

- [ ] Step 1: tests `openAiReasoningContentStreams` (reasoning delta then text delta → chunks Reasoning("hm"), Text("ok"); `generate()` yields only "ok"), `openAiReasoningFieldAlias`, `anthropicThinkingDelta`, `anthropicThinkingRequestBody`, `streamWithoutDoneCompletes`, `keepAliveEventsIgnored`.
- [ ] Step 2: run `./gradlew :app:testDebugUnitTest --tests '*BackendTest'` → FAIL (compile).
- [ ] Step 3: refactor + implement; the three existing backends return `ChunkBackend`s.
- [ ] Step 4: run → PASS (old tests unchanged).
- [ ] Step 5: commit `feat(api): chunked backend stream with reasoning`.

### Task 2: Provider registry and OpenAI-compatible quirks

**Files:** Create `api/Providers.kt`; modify `data/Settings.kt` (`ConnectionProfile.provider: String = "custom"`), `api/Backends.kt`; test `api/ProvidersTest.kt`.

**Interfaces:**
- Produces: `data class Provider(val id: String, val label: String, val type: BackendType, val baseUrl: String, val keyRequired: Boolean = true, val reasoningStyle: ReasoningStyle = NONE, val extraHeaders: Map<String,String> = emptyMap())`; `enum ReasoningStyle { NONE, OPENAI_EFFORT, OPENROUTER, DEEPSEEK }`; `object Providers { val all: List<Provider>; fun byId(id: String): Provider; fun forProfile(p: ConnectionProfile): Provider }`.
- Registry (id → base URL): openai `https://api.openai.com/v1`, openrouter `https://openrouter.ai/api/v1`, mistral `https://api.mistral.ai/v1`, groq `https://api.groq.com/openai/v1`, deepseek `https://api.deepseek.com`, xai `https://api.x.ai/v1`, cohere `https://api.cohere.ai/compatibility/v1`, perplexity `https://api.perplexity.ai`, together `https://api.together.xyz/v1`, fireworks `https://api.fireworks.ai/inference/v1`, nvidia `https://integrate.api.nvidia.com/v1`, moonshot `https://api.moonshot.ai/v1`, custom (OPENAI type, no key required); anthropic; google; ollama `http://127.0.0.1:11434`; koboldcpp `http://127.0.0.1:5001`; kobold `http://127.0.0.1:5000`; novelai `https://api.novelai.net`; horde `https://aihorde.net/api`; tabby/aphrodite/llamacpp/ooba/vllm (TEXT_COMPLETION, local URLs, no key).
- OpenRouter sends headers `HTTP-Referer` and `X-Title: SillyTavern Android`; reasoning body per style (OPENAI_EFFORT: `reasoning_effort`; OPENROUTER: `reasoning:{effort}` or `{max_tokens}` when budget>0; DEEPSEEK: nothing, parser already reads `reasoning_content`; deepseek omits sampler params when model contains `reasoner`).

- [ ] Step 1: tests `registryHasUniqueIds`, `byIdFallsBackToCustom`, `openRouterSendsAttributionHeaders`, `openRouterReasoningBody` (effort high → `reasoning.effort=="high"`; budget 2000 → `reasoning.max_tokens==2000`), `openAiReasoningEffort`, `autoEffortSendsNothing`.
- [ ] Step 2–4: run FAIL → implement → PASS. Step 5: commit `feat(api): provider registry for OpenAI-compatible services`.

### Task 3: Google Gemini backend

**Files:** Create `api/GeminiBackend.kt`; register in `Backends.forType`; add `BackendType.GOOGLE`; test `api/GeminiBackendTest.kt`.

**Interfaces:** `class GeminiBackend(client): HttpBackend` — URL `{base}/models/{model}:streamGenerateContent?alt=sse` (stream) or `:generateContent`; header `x-goog-api-key`. Pure function `GeminiBackend.toContents(messages): Pair<String?, List<JsonObject>>` (leading system → `systemInstruction.parts[0].text`; assistant → `model`; merge adjacent same-role; ensure first is user). `generationConfig`: temperature, topP, topK (if >0), maxOutputTokens, stopSequences; `thinkingConfig:{includeThoughts:true, thinkingBudget}` when budget>0 or effort≠auto (min→0/128? use map min=512, low=1024, medium=8192, high=24576, max=32768 when budget==0). `safetySettings` all five categories `BLOCK_NONE`. Parser: `candidates[0].content.parts[]`: `thought:true` → Reasoning, `functionCall` → ToolCall (id = name+index), else Text; `promptFeedback.blockReason` or finishReason SAFETY with no text → throw `BackendException("Blocked: …")`.
Default base URL `https://generativelanguage.googleapis.com/v1beta`.

- [ ] Tests: `toContentsMapsRoles`, `streamsTextAndThoughts`, `nonStreaming`, `requestHasSafetyAndConfig`, `blockedPromptThrows`, `errorBodySurfaced`.
- [ ] RED → implement → GREEN → commit `feat(api): Google Gemini backend`.

### Task 4: Text backends — Ollama, KoboldCpp/KoboldAI, NovelAI, Horde

**Files:** Create `api/OtherBackends.kt`; `BackendType.OLLAMA, KOBOLD, NOVELAI, HORDE`; test `api/OtherBackendsTest.kt`.

**Interfaces:**
- `OllamaBackend` (chat, NDJSON): `POST {base}/api/chat`, `{model, messages, stream, options{temperature, top_p, top_k, min_p, repeat_penalty, num_predict, num_ctx, stop, + preset fields seed,tfs_z,typical_p,mirostat*,presence/frequency penalty}, think?}`; parse `message.content`, `message.thinking` → Reasoning, `message.tool_calls`; done on `done:true`. `think` sent as bool when effort≠auto (`min` → false).
- `KoboldBackend` (SSE or plain): KoboldCpp stream `POST {base}/api/extra/generate/stream` events `data: {"token":"…"}`; non-stream/classic `POST {base}/api/v1/generate` → `results[0].text`. Body: `prompt, max_length, max_context_length, temperature, top_p, top_k, rep_pen, stop_sequence, sampler preset: min_p, typical, tfs, top_a, rep_pen_range, mirostat, mirostat_tau, mirostat_eta, sampler_seed, grammar, genkey`. Uses `GenerationRequest.textPrompt`.
- `NovelAiBackend`: `POST {base}/ai/generate-stream` (Bearer) body `{input, model, parameters{temperature, max_length, min_length:1, top_k, top_p, tail_free_sampling, repetition_penalty, stop_sequences, use_string:true, return_full_text:false, generate_until_sentence:false}}` → SSE `data: {"token":"…"}`; non-stream `/ai/generate` → `{output}`.
- `HordeBackend` (custom flow, no streaming): `POST {base}/v2/generate/text/async` header `apikey` (blank → `0000000000`), body `{prompt, params{max_length, max_context_length, temperature, top_p, top_k, rep_pen, stop_sequence}, models:[model if set], trusted_workers:false}` → `{id}`; poll `GET {base}/v2/generate/text/status/{id}` every `pollMillis` (constructor param, default 2000) until `done` → `generations[0].text`; `faulted` → exception; coroutine cancel → `DELETE {base}/v2/generate/text/status/{id}`.
- All four: a text-prompt backend for Kobold/NovelAI/Horde — `ChatController.textMode` must be true for `KOBOLD, NOVELAI, HORDE` (instruct/context path), false for `OLLAMA` (chat messages).

- [ ] Tests: one request-shape + response-parse test per backend (`ollamaStreamsNdjsonWithThinking`, `ollamaDoneStops`, `koboldCppStreamsTokens`, `koboldClassicNonStream`, `novelAiStreams`, `hordeSubmitsPollsAndReturns`, `hordeFaultedThrows`, `hordeCancelDeletesJob`), plus `textModeForTypes`.
- [ ] RED → implement → GREEN → commit `feat(api): Ollama, Kobold, NovelAI and Horde backends`.

### Task 5: Model list fetching

**Files:** Create `api/ModelLister.kt`; test `api/ModelListerTest.kt`.

**Interfaces:** `data class ModelInfo(val id: String, val label: String = id)`; `object ModelLister { suspend fun fetch(profile: ConnectionProfile, apiKey: String?, client: OkHttpClient = Backends.defaultClient): List<ModelInfo> }` (runs on IO; HTTP errors → `BackendException`; unexpected shape → empty). Endpoints: OPENAI-type `GET {base}/models` → `data[].id` (OpenRouter also `name` as label); ANTHROPIC `GET /models?limit=1000` headers `x-api-key`, `anthropic-version` → `data[].id` (`display_name` label); GOOGLE `GET /models` → `models[].name` stripped of `models/`, only those whose `supportedGenerationMethods` contains `generateContent`; OLLAMA `GET /api/tags` → `models[].name`; TEXT_COMPLETION `GET {base}/models` → `data[].id` (fallback empty); KOBOLD `GET /api/v1/model` → `result`; HORDE `GET /v2/status/models?type=text` → `[].name` sorted by `count` desc; NOVELAI fixed list (`kayra-v1`, `clio-v1`, `llama-3-erato-v1`, `glm-4-6`).

- [ ] Tests: one per type with MockWebServer + `unexpectedShapeIsEmpty`, `httpErrorThrows`, `googleFiltersNonGenerative`.
- [ ] RED → implement → GREEN → commit `feat(api): model list fetching`.

### Task 6: Tool calling

**Files:** Create `api/Tools.kt`; modify `prompt/PromptBuilder.kt` (`PromptMessage` gains `toolCalls: List<Chunk.ToolCall> = emptyList()`, `toolCallId: String? = null`; `Role.TOOL` added), `api/Backends.kt`, `GeminiBackend.kt`, Ollama; `GenerationRequest.tools: List<ToolDef> = emptyList()`; test `api/ToolsTest.kt`.

**Interfaces:**
- `data class ToolDef(val name: String, val description: String, val parameters: JsonObject)` (JSON-schema object); `interface ToolRegistry { fun definitions(): List<ToolDef>; suspend fun execute(call: Chunk.ToolCall): String }`; `object EmptyToolRegistry`.
- `object ToolLoop { fun run(backend: ChunkBackend, req: GenerationRequest, registry: ToolRegistry, maxRounds: Int = 5): Flow<Chunk> }` — passes tools in `req`, forwards Text/Reasoning chunks, when ToolCalls arrive appends an assistant message (with `toolCalls`) + one `Role.TOOL` message per result and re-requests; stops at `maxRounds` or when a round has no calls. Empty registry → single plain request.
- Wire formats: OpenAI (`tools:[{type:function,function:{name,description,parameters}}]`; streamed `delta.tool_calls[{index,id,function:{name,arguments}}]` assembled by index; assistant message `tool_calls`; tool message `{role:tool,tool_call_id,content}`), Anthropic (`tools:[{name,description,input_schema}]`; `content_block_start` `tool_use` + `input_json_delta` assembled; messages with `tool_use` / `tool_result` blocks), Gemini (`tools:[{functionDeclarations}]`; `functionCall` / `functionResponse` parts), Ollama (`tools`, `message.tool_calls[].function`). Text-completion-style backends ignore tools. `toAnthropicMessages` / `toContents` keep working for prompts without tool messages.

- [ ] Tests: `openAiAssemblesSplitToolCallArguments`, `openAiParallelCallsByIndex`, `anthropicToolUseAssembled`, `geminiFunctionCall`, `ollamaToolCalls`, `requestsIncludeToolDefs` (per backend), `loopRunsToolAndReRequests` (fake ChunkBackend: round 1 returns ToolCall, round 2 text; assert second request contains assistant+tool messages and output text), `loopStopsAtMaxRounds`, `emptyRegistryNoLoop`.
- [ ] RED → implement → GREEN → commit `feat(api): tool calling plumbing`.

### Task 7: Controller + UI

**Files:** Modify `chat/ChatController.kt`, `chat/ChatMessage.kt` (`val reasoning: String?` accessor, `withReasoning`), `ui/SettingsScreen.kt`, `ui/ChatScreen.kt`, `App.kt` (tool registry hook `toolRegistry: ToolRegistry = EmptyToolRegistry`); test `chat/ChatControllerTest.kt` (extend).

- Controller: use `ChunkBackend.chunks` when available, else wrap `generate`; accumulate `Reasoning` into the target message's `extra.reasoning` (cleared at start of each (re)generation, kept on continue); Text as before; pass through `ToolLoop` when `toolRegistry.definitions()` is non-empty; `textMode` = type in `TEXT_COMPLETION, KOBOLD, NOVELAI, HORDE`; `counterFor` handles new types with the default estimator.
- UI connection dialog: provider dropdown (sets type + base URL + clears model), reasoning effort chips + budget field, “Fetch models” button (dialog with filterable list → sets model), Horde/NovelAI hints. Chat bubble: collapsed “Thinking…” section (tap to expand) when `extra.reasoning` is non-blank.
- [ ] Tests: `reasoningStoredInExtra`, `reasoningDoesNotLeakIntoText`, `regenerateClearsReasoning`, `kolboldUsesTextPrompt`.
- [ ] RED → implement → GREEN; `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → all pass; commit `feat(ui): providers, model picker and reasoning display`.

### Task 8: Docs

- [ ] Update `docs/WORKLOG.md` (done / rulings / not verified), mark ROADMAP row 4 DONE, commit `docs: work log and roadmap for set 4 (backends)`. Report to user; stop.
