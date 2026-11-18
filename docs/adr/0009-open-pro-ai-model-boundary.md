# ADR 0009: Open Core / Closed Pro AI Model Boundary

Status: Accepted, 2026-11-18. Supplements ADR 0001 and ADR 0002; implements the Constitution Article II addendum ("Open Core / Closed Pro Boundary", added 2026-11-18).

## Context

`CONSTITUTION.md` and `ADR 0001` established the structural separation between the public Apache-2.0 open core (`voice-note-sorter`) and the private proprietary companion (`voice-note-sorter-pro`), and `ADR 0002` defined the package-level `ai/` interface seam (and `:extension-api` extraction trigger).

As the project adopts on-device generative AI, OCR, and semantic embeddings, we need an explicit boundary deciding which AI models and runtime permissions belong in the open core versus the closed Pro build:

- Google AICore / Gemini Nano (`com.google.mlkit:genai-*`) models are managed by the OS and require zero APK model bundling and zero network permissions in the app.
- Large open-weight models (such as quantized Gemma via LiteRT-LM, neural sentence embedders, vision-language handwriting models, Whisper/diarization models, and LoRA adapters) are too large to bundle in a lightweight open-core APK and often require user-initiated model-pack downloads over HTTPS.
- `CONSTITUTION.md` Article IV.1 strictly forbids the `INTERNET` permission in the open core.

## Decision

Per the `CONSTITUTION.md` Article II addendum ("Open Core / Closed Pro Boundary", added 2026-11-18):

1. **Open Core (`voice-note-sorter`, Apache-2.0, public):**
   - MUST NOT declare `INTERNET` or any network permission.
   - AI capabilities are limited to OS-managed AICore models (`com.google.mlkit:genai-*`) and small bundled models under Apache-2.0-compatible licenses (for example, bundled `com.google.mlkit:text-recognition` for Tier 2 OCR).
   - Ships free default/fallback implementations (heuristics, `NoOpEmbedder` / FTS4, `ExtractiveSummarizer`, `PlatformTranscriber`, `NullTranscriber`) behind the interfaces in `app/.../ai/`.
   - Contains no billing, entitlement, paywall code, or paid-feature tracking.

2. **Closed Pro (`voice-note-sorter-pro`, proprietary, private):**
   - MAY contain billing/entitlement code and large open-weight models (LiteRT / LiteRT-LM neural embedders, VLM image transcribers, Whisper/diarization, LoRA adapters).
   - Consumes the open core exclusively through the `:extension-api` seam (`ADR 0002`); the open core never depends on Pro.
   - MAY declare `INTERNET` for **one purpose only**: model-pack downloads that the user explicitly initiates, over HTTPS, with SHA-256 verification before use. No other network use, no user account system, and no server operated for the app.

3. **On-Device Content Guarantee in Both Builds:**
   - User content (audio, transcripts, notes, images, embeddings) MUST NOT leave the device in either build, except through a single explicit user-invoked export or share action.
   - Cloud transcription, cloud inference, and cloud sync remain prohibited in both builds; changing this requires a further constitutional amendment and a superseding ADR.

4. **Mirror Rule:**
   - Fixes that belong in the core are implemented in `voice-note-sorter` on their own merits under Apache-2.0. Closed Pro code is never copied into the open repository.

## Consequences

- The open core remains small, offline-only, permission-minimal (`RECORD_AUDIO`, foreground microphone service, notifications, wake lock — zero network permissions), and useful on every tier.
- Pro builds can bring heavy open-weight local models (LiteRT-LM Gemma, neural embedders, VLM OCR, Whisper) to devices without AICore while preserving user privacy (zero accounts, zero cloud inference/sync, SHA-256-verified HTTPS model-pack downloads only).
- Starting Pro development against `ai/` interfaces fires Trigger #1 in `ADR 0002` to extract `:extension-api`.
