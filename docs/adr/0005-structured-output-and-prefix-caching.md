# ADR 0005: Structured Output, SystemInstruction, and Prefix Caching

Status: Accepted, 2026-11-18. Tracked under Issue #145.

## Context

`NanoSummarizer`, `NanoActionExtractor`, and `NanoBucketer` run sequentially over the same note transcript inside `SortingWorker.executePipeline` (Stages 2–4). The baseline `MlKitGenAiPromptClient` opened and closed a fresh `GenerativeModel` client on every status check and inference call, embedded raw transcripts into each prompt string, and relied exclusively on unconstrained plain-text prompts parsed with regexes.

On Gemini Nano via AICore (`com.google.mlkit:genai-prompt`), that design had two drawbacks:
1. Plain-text line formats (`Title:/Summary:`, `ACTION: ... | DUE: ...`, `BUCKET:/TAGS:`) remain susceptible to markdown/framing variance, causing unnecessary Tier 2 demotions even when guided JSON decoding is supported on the device.
2. Re-sending the same system framing and transcript up to three times across separate `GenerativeModel` instances prevented AICore from reusing model warmup state and KV-cache prefix computations across Stages 2–4.

At the same time, AICore feature support varies by device and OS build: structured output (`isStructuredOutputFeatureAvailable()`), system prompts (`isSystemPromptAvailable()`), and prefix caching (`isCachingFeatureAvailable()`) are independent capability checks, and constrained decoding (`GenerateTypedContentRequest`) requires schema metadata generated at compile time (`GenerableProvider` via `ServiceLoader`).

## Decision

1. **Compile-time typed schemas behind the `ai/` seam (`GenerativeAi.kt`)**:
   - Define `@Generable` / `@Guide` schema data classes (`StructuredTitleSummary`, `StructuredActionExtraction`, `StructuredActionItem`, `StructuredBucketAssignment`) inside `GenerativeAi.kt` and generate their `GenerableProvider` ServiceLoader registrations via `com.google.mlkit:genai-schema-compiler` (KSP).
   - Keep all `com.google.mlkit.genai.prompt.*` and `com.google.mlkit.genai.schema.*` types strictly isolated inside `GenerativeAi.kt`. `GenAiPromptClient` and `GenAiPromptSession` expose `PromptRequest` and `KClass<T>`-typed `generateTyped(...)` methods with backwards-compatible defaults.

2. **Two-step Tier 3 execution with automatic fallback (`NanoSummarizer`, `NanoActionExtractor`, `NanoBucketer`)**:
   - When `isStructuredOutputAvailable()` is `true`, each Tier 3 stage attempts `GenerateTypedContentRequest` (`generateTyped`) first and normalizes the result with domain checks (non-blank title/summary with 60-character word-boundary truncation; `PlatformDateParser` due-date resolution; case-insensitive validation of `bucketKind` against the active `BucketProfile`).
   - If structured output is unsupported, fails with a non-busy error (for example `STRUCTURED_OUTPUT_RESPONSE_ERROR` or `STRUCTURED_OUTPUT_MAX_TOKENS_ERROR`), or yields an invalid/blank domain value, the stage automatically falls back within Tier 3 to plain-text generation (`generateText`) + regex parsing.
   - If regex parsing also fails, or when the capability status is `UNAVAILABLE` or `DOWNLOADABLE`, the stage logs demotion and falls back to Tier 2 (`ExtractiveSummarizer`, `HeuristicActionExtractor`, `HeuristicBucketer`).
   - `CapabilityBusyException` (including `GenAiException.ErrorCode.BUSY` and `PER_APP_BATTERY_USE_QUOTA_EXCEEDED`) and `CancellationException` are never swallowed by structured-to-regex fallback; `CapabilityBusyException` propagates out of the stage so `SortingWorker` records `:BUSY` and sets `NoteStatus.NEEDS_REVIEW`.

3. **Shared `SystemInstruction`, transcript `PromptPrefix`, and session lifecycle**:
   - Stages 2–4 share a constant role prompt (`NOTE_ANALYSIS_SYSTEM_INSTRUCTION`) attached via ML Kit `SystemInstruction` when `isSystemPromptAvailable()` is `true`, and a shared bounded transcript header (`buildTranscriptPrefix`) attached via `PromptPrefix(..., cacheSystemInstruction = useSystemPrompt, cacheTools = false)` when `isCachingAvailable()` is `true`. When prefix caching is unavailable (or if `CACHE_PROCESSING_ERROR` occurs on plain text generation), the prefix is prepended directly to `TextPart`.
   - `GenAiPromptClient.openSession()` creates a `GenAiPromptSession` backed by a single `GenerativeModel` instance. `SortingWorker.executePipeline` opens one shared session when a Stage 2–4 `Nano*` implementation shares a client and probe status is `AVAILABLE`, runs best-effort `warmup()` before Stage 2, passes the session across Stages 2–4, and cleans up in `finally` under `NonCancellable` via `clearImplicitCaches()` followed by `close()`.

## Alternatives Considered

- **Structured output only (removing regex parsers)**: Rejected. Devices or AICore builds where `isStructuredOutputFeatureAvailable()` returns `false` (or where JSON constrained decoding hits `maxOutputTokens`) would lose Tier 3 generative quality unnecessarily when plain-text regex parsing still succeeds.
- **Per-stage `GenerativeModel` open/close without sessions**: Rejected. Closing and reopening `GenerativeModel` between Stages 2, 3, and 4 drops warmup state and forces repeated transcript prefill instead of hitting the shared KV prefix cache.
- **Hardcoding allowed bucket `enumValues` on `@Guide`**: Rejected. `BucketProfileProvider` allows white-label and closed Pro builds to supply custom bucket definitions at runtime without changing core code (`ADR 0002`). Allowed buckets remain listed in the stage prompt and validated dynamically against the active `BucketProfile`.

## Consequences

- Higher Tier 3 reliability on AICore builds with structured output support, with seamless degradation to plain-text regex parsing and Tier 2 heuristics.
- Reduced latency and prefill work across Stages 2–4 of a note via shared `SystemInstruction`, `PromptPrefix`, and `warmup()`, while explicit `clearImplicitCaches()` + `close()` prevents transcript prefix caches from outliving a pipeline run.
- `app/proguard-rules.pro` retains `@Generable` classes and `GenerableProvider` implementations alongside `com.google.mlkit.genai.**` so R8 keeps ServiceLoader metadata and JSON deserialization fields intact in release builds.
