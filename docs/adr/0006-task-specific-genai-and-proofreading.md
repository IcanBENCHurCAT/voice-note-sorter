# ADR 0006: Task-Specific GenAI APIs and the Proofreader Capability Seam

Status: Accepted, 2026-11-19. Supplements ADR 0001, ADR 0002, and ADR 0009; authorizes the `Proofreader` seam implemented under Issue #149 while deferring `com.google.mlkit:genai-proofreading` adoption pending physical hardware verification under Issue #147.

## Context

Raw transcripts produced in Stage 1 (`Transcriber` — `NanoTranscriber`, `PlatformTranscriber`, or `NullTranscriber`) frequently omit terminal punctuation, sentence-initial capitalization, or clean spacing, or contain minor speech-to-text formatting quirks.

Downstream pipeline stages (`Summarizer`, `ActionExtractor`, `Bucketer`) benefit from normalized sentence boundaries and punctuation, yet the persisted `Note.transcript` must remain an immutable, verbatim record of what Stage 1 transcribed:

- Users must never lose original dictated words, facts, names, or numbers to an automated rewrite.
- Re-running `SortingWorker` on an already-processed note must remain idempotent (`CONSTITUTION.md` Art. VII.3), rather than repeatedly re-rewriting a previously proofread transcript.

In parallel, ML Kit GenAI provides task-specific APIs (`com.google.mlkit:genai-proofreading`, `com.google.mlkit:genai-summarization`, `com.google.mlkit:genai-rewriting`) alongside the general-purpose Prompt API (`com.google.mlkit:genai-prompt`). Per `docs/capability-verification.md` and Issue `#147`, physical Tier 3 AICore hardware has not yet verified the task-specific `genai-proofreading` / `genai-summarization` artifacts' runtime availability, status semantics, or offline behavior.

## Decision

1. **`Proofreader` capability seam (`app/.../ai/Capabilities.kt`):**
   - Introduce `interface Proofreader` with `suspend fun status(): CapabilityStatus` and `suspend fun proofread(rawTranscript: String): String`.
   - Expose `checkProofreader()` on `CapabilityChecker` and `proofreaderStatus()` on `CapabilityProbe` (defaulting to the foundation-model probe in v1) so capability status is probed and cached with the app/AICore version fingerprint.

2. **Shipped v1 implementations and honest degradation:**
   - **Tier 1 / Tier 2 (`DeterministicTranscriptCleaner` in `Heuristics.kt`):** Deterministic, word-preserving transcript cleanup that collapses whitespace, capitalizes sentence-initial characters, and ensures terminal punctuation without changing, adding, or removing words. Reports `CapabilityStatus.AVAILABLE`.
   - **Tier 3 (`NanoProofreader` v1 in `GenerativeAi.kt`):** Uses `GenAiPromptClient` (**Prompt API path only**, backed by `MlKitGenAiPromptClient` / `com.google.mlkit:genai-prompt`) with strict instructions and output validation preserving facts, names, and numbers.
     - On `CapabilityStatus.UNAVAILABLE`, `CapabilityStatus.DOWNLOADABLE`, or blank/unusable model output (including refusals, dropped digits, or extreme truncation/expansion), `NanoProofreader` logs demotion and falls back to `DeterministicTranscriptCleaner`.
     - On `CapabilityStatus.BUSY` or battery/quota exhaustion, `NanoProofreader` propagates `CapabilityBusyException` so `SortingWorker` logs demotion, falls back to `DeterministicTranscriptCleaner`, records `proofread:BUSY,proofread_fallback:OK` in `stageStatus`, and sets `Note.status` to `NEEDS_REVIEW`.

3. **Task-API adoption and fallback order (planned under `#147`):**
   - `NanoProofreader` v1 ships with the Prompt API path only (`GenAiPromptClient`) and does **not** declare `com.google.mlkit:genai-proofreading` in `gradle/libs.versions.toml` or `app/build.gradle.kts`.
   - Adopting `com.google.mlkit:genai-proofreading` (and evaluating `com.google.mlkit:genai-summarization`) awaits physical Tier 3 AICore hardware verification tracked in Issue `#147`.
   - Planned fallback order once `#147` verifies task-specific APIs:
     1. Task-specific ML Kit GenAI Proofreading API (`com.google.mlkit:genai-proofreading`, planned under `#147`) when `AVAILABLE`.
     2. Prompt API (`GenAiPromptClient` / `com.google.mlkit:genai-prompt`, shipped in v1) when `AVAILABLE`.
     3. Deterministic Tier 1/2 fallback (`DeterministicTranscriptCleaner`) when GenAI is `UNAVAILABLE`, `DOWNLOADABLE`, `BUSY` (via `CapabilityBusyException` demotion in `SortingWorker`), or produces unusable output.

4. **Raw transcript immutability (hard invariant):**
   - Stage 1 persists `transcript.text` to `Note.transcript`.
   - `SortingWorker` runs `Proofreader` between Stage 1 (Transcribe) and Stage 2 (Summarize) to produce a separate `workingTranscript: String` (held as a pipeline working copy without mutating `Note.transcript`).
   - Stages 2 (`Summarizer`), 3 (`ActionExtractor`), and 4 (`Bucketer`) consume `workingTranscript`, while `Note.transcript` remains unchanged verbatim.

## Consequences

- Downstream summarization, action extraction, and bucketing operate on cleaner punctuation and capitalization across every capability tier without mutating the persisted raw transcript.
- Zero new dependencies or Android permissions are added, preserving the open-core local-only boundary (`CONSTITUTION.md` Art. I, II, IV; `ADR 0009`).
- Future adoption of `com.google.mlkit:genai-proofreading` after `#147` hardware verification requires updating only `NanoProofreader` (and `DeviceCapabilityChecker`) behind the `Proofreader` seam, with no pipeline, database, or UI refactoring.
