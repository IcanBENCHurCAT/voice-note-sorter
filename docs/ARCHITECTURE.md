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
  |- Platform implementations (SpeechRecognizer, heuristics)
  |- No-op / manual implementations (Tier 1)
Data: Room (notes, buckets, actions, tags, FTS) + files (audio)
Platform: Glance widgets, Quick Settings tile, Live Updates
```

## Capture stack

Entry points: FAB one-tap on the main screen, a Quick Settings tile (via `EXTRA_START_RECORDING` intent), and notification actions (pause, resume, stop).

All entry points route through `StartCaptureUseCase`, which launches a foreground `RecordingService`. This service holds a microphone foreground service type, manages a `MediaRecorder` instance (MIC audio source, AAC/MPEG-4 format), and enforces a 4-minute warning and 5-minute hard stop limit.

Audio files are written to an app-private directory (`filesDir/recordings/`) using `AudioFileManager`. They are stored as timestamped `.m4a` files. If a recording is cancelled, the partial file is deleted.

When a recording is stopped, the service persists a new `Note` entity (status `RECORDED`, bucket `INBOX`, tier `CORE`) and enqueues a `SortingWorker` via WorkManager to trigger the sorting pipeline.

## The sorting pipeline

Five stages, each independently failable and independently tiered:

1. **Transcribe** - speech to text (Nano Advanced / Basic / platform / none)
2. **Title and summarise** - generative on Tier 3; extractive (first
   sentence, key phrases) on Tier 2; timestamp title on Tier 1
3. **Extract actions** - Prompt API structured output on Tier 3;
   pattern heuristics ("remind me", "need to", dates) below that
4. **Bucket and tag** - model classification on Tier 3; keyword rules
   below; default bucket is Inbox, never a dead end
5. **Embed** - semantic vectors on Tier 3 only; search falls back to FTS

A stage that cannot run (capability absent, AICore BUSY, quota) records
its status on the note and the pipeline continues. A background retry pass
upgrades notes when a higher capability becomes available.

## Capability detection

`CapabilityProbe` checks each ML Kit GenAI feature's status
(AVAILABLE / DOWNLOADABLE / UNAVAILABLE) plus device signals, caches the
result, and re-probes on AICore changes. The UI renders the tier the note
actually got, not the tier the marketing page promised.

## Data model (Room)

- `Note` - id, createdAt, audioPath, transcript, title, summary,
  bucketKind, tierUsed, stageStatus, status (RECORDED / TRANSCRIBED / SORTED / NEEDS_REVIEW)
- `Bucket` - id, name, kind (IDEA / TASK / NOTE / LIST / INBOX / custom)
- `ActionItem` - id, noteId, text, dueAt (nullable), done
- `Tag`, `NoteTag` - free-form labels, many-to-many
- `NoteFts` - FTS4 table over title, transcript, summary

Buckets are configuration, not code: white-label builds swap the default
bucket set and rules without forking the pipeline.

## Extension points (for the closed Pro repo and client builds)

Implemented today, in the `ai/` package:

- `Transcriber`, `Summarizer`, `ActionExtractor`, `Bucketer`, `Embedder` - the
  capability interfaces; alternative engines can be supplied by an outer
  module at build time. Note that there is no vector store and no embeddings are persisted; search falls back to FTS.
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
for why the seam exists and the rules around it, and `CONSTITUTION.md`
for the governing principles.

## What is deliberately absent

- No networking layer. No accounts. No sync. (Restore Credentials and
  export arrive later as user-invoked, local operations.)
- No analytics or crash upload. Logcat is the telemetry.
