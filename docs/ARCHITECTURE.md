# Architecture

Local-first, capability-tiered, one app module with strict internal seams.

## Layers

```
UI (Compose, Navigation 3, Material 3 Expressive)
  |  calls interfaces only
Domain: SortingPipeline (staged, WorkManager-backed)
  |
AI capability interfaces (ai/ package)
  |- Nano implementations (ML Kit GenAI / AICore)
  |- Platform & bundled implementations (SpeechRecognizer, bundled ML Kit OCR, heuristics)
  |- No-op / manual implementations (Tier 1)
Data: Room (notes, buckets, actions, tags, FTS) + files (audio, images)
Platform: Glance widgets, Quick Settings tile, Live Updates
```

## Capture stack

**Audio capture:** Entry points are the primary FAB one-tap on the main screen, a Quick Settings tile (via `EXTRA_START_RECORDING` intent), and notification actions (pause, resume, stop).

All audio entry points route through `StartCaptureUseCase`, which launches a foreground `RecordingService`. This service holds a microphone foreground service type, manages a `MediaRecorder` instance (MIC audio source, AAC/MPEG-4 format), and enforces a 4-minute warning and 5-minute hard stop limit.

Audio files are written to an app-private directory (`filesDir/recordings/`) using `AudioFileManager`. They are stored as timestamped `.m4a` files. If a recording is cancelled, the partial file is deleted.

When a recording is stopped, the service persists a new `Note` entity (`sourceType = AUDIO`, status `RECORDED`, bucket `INBOX`, tier `CORE`) and enqueues a `SortingWorker` via WorkManager to trigger the sorting pipeline.

**Image capture (ADR 0008):** A secondary scan/photo capture action alongside the Record FAB launches either Android's system Photo Picker (`PickVisualMedia`) or system camera delegation (`TakePicture`) via an app-private `FileProvider` URI—requiring zero new manifest permissions (`CAMERA`, `READ_MEDIA_IMAGES`, and `INTERNET` remain absent). Captured or picked images are stored in `filesDir/images/` using `ImageFileManager`, persisted as a `Note` (`sourceType = IMAGE`, `imagePath`), and enqueued to `SortingWorker`.

## The sorting pipeline

Staged, independently failable, and independently tiered:

1. **Transcribe** - branches on `Note.sourceType`:
   - `AUDIO`: speech to text (`NanoTranscriber` → `PlatformTranscriber` → `NullTranscriber`).
   - `IMAGE`: handwritten/printed text extraction (`NanoImageTranscriber` via `ImagePart(Bitmap)` → `MlKitOcrImageTranscriber` using bundled `com.google.mlkit:text-recognition` → `NullImageTranscriber`). On Tier 1 or blank OCR output, the attached image is retained and the note is marked `NEEDS_REVIEW` for manual entry.
   Persists the raw `Note.transcript` immediately; once stored, `Note.transcript`
   is immutable across downstream stages.
2. **Proofread (transcript cleanup)** - runs between Stage 1 and Stage 2 behind
   the `Proofreader` capability seam (`ADR 0006`): `NanoProofreader` on Tier 3
   (v1 uses `GenAiPromptClient` / Prompt API path only, while adopting
   `com.google.mlkit:genai-proofreading` awaits physical hardware verification
   in `#147`) and `DeterministicTranscriptCleaner` on Tier 1/2 (whitespace
   collapse, sentence-initial capitalization, and terminal punctuation without
   altering words). Proofreader output is held as a separate `workingTranscript`
   consumed by Stages 3-6, leaving `Note.transcript` untouched.
3. **Title and summarise** - Tier 3 Prompt API (`GenerateTypedContentRequest`
   structured output when supported, automatic plain-text + regex fallback
   otherwise); extractive (`ExtractiveSummarizer`) on Tier 2; timestamp title on Tier 1
   (consumes `workingTranscript`)
4. **Extract actions** - Tier 3 Prompt API (`GenerateTypedContentRequest`
   structured output when supported, automatic plain-text + regex fallback
   otherwise) with `PlatformDateParser` due-date resolution; pattern
   heuristics (`HeuristicActionExtractor`) below that (consumes
   `workingTranscript`)
5. **Bucket and tag** - Tier 3 Prompt API (`GenerateTypedContentRequest`
   structured output against the active `BucketProfile` when supported,
   automatic plain-text + regex fallback otherwise); keyword rules
   (`HeuristicBucketer`) below; default bucket is Inbox, never a dead end
   (consumes `workingTranscript`)
6. **Embed** - persists semantic vectors (`NoteEmbedding`) behind `Embedder` when `AVAILABLE` (recording `embed:OK`, `embed:BUSY`, `embed:SKIPPED`, or `embed:FAILED`); Open Core ships `NoOpEmbedder` (`embed:SKIPPED`) and search falls back to FTS4 (consumes `workingTranscript`)

When Stages 2–4 run on Tier 3 (`NanoSummarizer`, `NanoActionExtractor`,
`NanoBucketer`) with a shared `GenAiPromptClient`, `SortingWorker` opens a
single `GenAiPromptSession`, warms up the model before Stage 2, attaches
shared `SystemInstruction` (role framing) and transcript `PromptPrefix`
(when supported by the model) so KV prefix state can be reused across all
three stages, and clears implicit caches + closes the session in `finally`.
A stage that cannot run (capability absent, AICore `BUSY`, quota, or
unparseable output) records its status (`:BUSY` / `:FAILED`) or Tier 2
demotion on the note and the pipeline continues (`CapabilityBusyException`
marks the note `NEEDS_REVIEW`). See
`docs/adr/0005-structured-output-and-prefix-caching.md`.

## Capability detection

`CapabilityProbe` checks each ML Kit GenAI feature's status
(AVAILABLE / DOWNLOADABLE / UNAVAILABLE) plus device signals, caches the
result, and re-probes on AICore changes. The UI renders the tier the note
actually got, not the tier the marketing page promised.

## Data model (Room)

- `Note` - id, createdAt, sourceType (AUDIO / IMAGE), audioPath, imagePath,
  transcript, title, summary, bucketKind, tierUsed, stageStatus,
  status (RECORDED / TRANSCRIBED / SORTED / NEEDS_REVIEW)
- `Bucket` - id, name, kind (IDEA / TASK / NOTE / LIST / INBOX / custom)
- `ActionItem` - id, noteId, text, dueAt (nullable), done
- `Tag`, `NoteTag` - free-form labels, many-to-many
- `NoteFts` - FTS4 table over title, transcript, summary
- `NoteEmbedding` - `noteId` (PK), `modelId`, `dimension`, `vectorBlob` (little-endian floats), `updatedAtEpochMs`; isolated by `modelId` and deleted atomically in note cascades (`NoteDeletionCoordinator`)

Buckets are configuration, not code: white-label builds swap the default
bucket set and rules without forking the pipeline.

## Extension points (for the closed Pro repo and client builds)

Implemented today, in the `ai/` package:

- `Transcriber`, `ImageTranscriber`, `Proofreader`, `Summarizer`, `ActionExtractor`, `Bucketer`, `Embedder` - the
  capability interfaces; alternative engines can be supplied by an outer
  module at build time. Open Core ships `NoOpEmbedder` (`modelId = "noop"`,
  FTS4 fallback) behind `Embedder`, while Room persists `NoteEmbedding`
  rows in `note_embeddings` and `DefaultSearchRepository` combines FTS4
  matches with cosine similarity over rows matching the active
  `Embedder.modelId` whenever an `Embedder` is `AVAILABLE` (see
  `docs/adr/0007-local-embeddings-and-hybrid-search.md`).
- `BucketProfileProvider` - supplies bucket sets and filing rules. This
  is the only downstream seam in use today.
- `SearchRepository` - interface for performing searches.

Defined in the concept spec (Section 10), not yet implemented:
`ExtensionRegistry`, `EntitlementProvider`, `FeatureGate`,
`ExportDestination`, `ReportTemplate`, extra BucketPack / vocabulary
packs, and a settings / navigation contribution point for Pro screens.
These are implemented only when a consumer exists; see
`docs/adr/0002-module-layout-and-the-extension-seam.md` for the seam
today (package-level interfaces in `:app`) and the trigger for
extracting a dedicated `:extension-api` module.

Anything behind these seams can be replaced by a downstream module
without modifying core code. See `docs/adr/0001-open-core-closed-pro-split.md`
for why the seam exists and the rules around it,
`docs/adr/0006-task-specific-genai-and-proofreading.md` for the `Proofreader`
seam and deferred `genai-proofreading` adoption (`#147`),
`docs/adr/0008-image-to-note-ingestion.md` for the zero-permission image ingestion pipeline
(clarified for Room schema v4 / `MIGRATION_3_4` in `docs/adr/0010-adr-0008-schema-version-correction.md`),
`docs/adr/0009-open-pro-ai-model-boundary.md` for the AI model and network
boundary between Open Core and Closed Pro, and `CONSTITUTION.md`
for the governing principles.

## What is deliberately absent

- No networking layer. No accounts. No sync. (Restore Credentials and
  export arrive later as user-invoked, local operations.)
- No analytics or crash upload. Logcat is the telemetry.
