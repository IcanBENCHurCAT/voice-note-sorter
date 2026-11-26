# Voice Note Sorter (working title)

Talk first. File never.

A free, local-only Android utility for people who capture thoughts as voice
notes and never organise them. The target loop: one tap to record, and the
note is transcribed, titled, summarised and filed automatically, on the
device, with no account and no server. Find notes later by what they are
about, not by scrolling a list of date-named recordings.

This repository is also a **best-practices reference app**: a clean example
of capability detection and progressive enhancement on modern Android
(Compose, Navigation 3, Glance, ML Kit GenAI), designed to degrade
flagship on-device AI down to a plain recorder that still
works.

> **Status: MVP open core.** The boundary described in `CONSTITUTION.md` and
> `AGENTS.md` is the hard contract. The module structure is `:app` plus
> `:extension-api`; the extension seam is the capability interface set in
> the `ai/` package, extracted into the dedicated `:extension-api` module
> under ADR 0002 Trigger #1 (see
> `docs/adr/0011-extension-api-module-extraction.md`).
>
> **Shipped on `main` today**: One-tap voice capture (`RecordingService`
> foreground service, Glance home-screen widget, Quick Settings tile),
> zero-permission image-to-note capture (`PickVisualMedia` / `TakePicture`
> via `FileProvider`), 6-stage fault-tolerant `SortingWorker` pipeline,
> 3-tier capability ladder (`NanoTranscriber`, `PlatformTranscriber`,
> `NullTranscriber`; `NanoProofreader` / `DeterministicTranscriptCleaner`;
> `NanoImageTranscriber` / bundled `MlKitOcrImageTranscriber` /
> `NullImageTranscriber`; `NanoSummarizer`, `NanoActionExtractor`,
> `NanoBucketer` with `@Generable` structured output + shared
> `GenAiPromptSession` prefix caching + `ErrorCode 30` background demotion;
> `HeuristicBucketer`, `ExtractiveSummarizer`, `HeuristicActionExtractor`,
> `PlatformDateParser`), Room v4 + FTS4 + `note_embeddings` hybrid search
> (`NoOpEmbedder` in Open Core), `NotesListScreen` (bucket, tag, and Needs
> Review filters), `NoteDetailScreen` (audio playback, image preview, inline
> editing, text/audio/image sharing via `FileProvider`, bucket
> confirmation/reassignment), `TasksScreen`, `SettingsScreen` (live
> `CapabilityProbe`, storage usage breakdown, opt-in 90-day audio
> auto-delete, Developer Options), `:extension-api` + `ExtensionRegistry`,
> and automated unit (`201/201`) + API 31/36/37 instrumented test suites
> (`69/69` verified on physical Pixel 10 Pro XL).
>
> **Planned (post-MVP / tracked)**: Background retry worker when
> downloadable models finish installing; audio file import; task reminders;
> Wear OS tile/complication; Android 16+ `AppFunctions`; and optional
> neural embedders / large open-weight models in downstream Pro builds.

## Developer Reference Guide

Start with [`docs/ON_DEVICE_AI_GUIDE.md`](docs/ON_DEVICE_AI_GUIDE.md) for a
complete walkthrough of how this app implements modern on-device Android AI
(with architecture diagrams, Pixel 10 Pro XL benchmarks, and hardware gotchas):

- **Capability Ladder & Runtime Probe:** [`CapabilityProbe.kt`](app/src/main/java/com/icanbenchurcat/voicenotes/ai/CapabilityProbe.kt) & [`Capabilities.kt`](extension-api/src/main/java/com/icanbenchurcat/voicenotes/ai/Capabilities.kt)
- **ML Kit GenAI Prompt API (`@Generable`, KV Prefix Caching, `ErrorCode 30`):** [`GenerativeAi.kt`](app/src/main/java/com/icanbenchurcat/voicenotes/ai/GenerativeAi.kt) (`ADR 0005`, `ADR 0006`)
- **Saved-File Speech Recognition (`AudioSource.fromFile` + PCM Pipe):** [`Transcribers.kt`](app/src/main/java/com/icanbenchurcat/voicenotes/ai/Transcribers.kt)
- **Zero-Permission Handwriting / Photo OCR:** [`ImageTranscribers.kt`](app/src/main/java/com/icanbenchurcat/voicenotes/ai/ImageTranscribers.kt) (`ADR 0008`)
- **6-Stage Fault-Tolerant Pipeline:** [`SortingWorker.kt`](app/src/main/java/com/icanbenchurcat/voicenotes/service/SortingWorker.kt)
- **Hybrid FTS4 + Cosine Search Seam:** [`SearchRepository.kt`](app/src/main/java/com/icanbenchurcat/voicenotes/data/SearchRepository.kt) (`ADR 0007`)
- **One-Way Extension Seam:** [`ExtensionRegistry.kt`](extension-api/src/main/java/com/icanbenchurcat/voicenotes/ai/ExtensionRegistry.kt) (`ADR 0002`, `ADR 0009`, `ADR 0011`)

## The one idea: a capability ladder, not a feature gate

On-device AI (Gemini Nano via ML Kit GenAI) is powerful but flagship-only,
quota-limited and sometimes busy. This app treats every AI feature as a
capability to probe, not a boolean to assume. Shipped capability tiers:

| Tier | Hardware | Transcription | Filing | Search |
| --- | --- | --- | --- | --- |
| 3 - Full | Gemini Nano flagships | ML Kit Speech Recognition (Advanced) / VLM Image (`ImagePart`) | Generative proofread, `@Generable` title/summary, structured action extraction, bucketing | Hybrid (FTS4 + `Embedder` when supplied downstream) |
| 2 - Core | Most devices, API 31+ | ML Kit Speech Recognition (Basic) / platform recogniser | Heuristic bucketing, extractive summary | Full-text (FTS) |
| 1 - Minimal | Any API 31+ device; also the runtime fallback on any tier when AICore is busy, downloading, or over quota | None (record and play back) | Manual | Full-text on manual titles |

Every capability sits behind an interface with a Nano implementation and a
heuristic/platform implementation. Detection is per capability, cached, and
re-probed when AICore state changes. When a capability is busy or over
quota, the app demotes at runtime instead of erroring. Organisation is
always a correction the user can make, never work the user must do.

## Local-only by design

- No account, no server, no analytics SDKs. Notes, audio, transcripts, and embeddings never leave the device.
- The permission list is the privacy policy. The manifest declares:
  - `RECORD_AUDIO`: Required for voice note capture.
  - `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE`: Powers `RecordingService` for robust background recording.
  - `POST_NOTIFICATIONS`: Displays recording progress and completion/failure notices on API 33+.
  - `WAKE_LOCK`: Ensures uninterrupted audio capture during device sleep.

## The open-core boundary

- **Apache-2.0**: Every file in this repository is licensed under Apache-2.0.
- **No paid code**: No ads SDKs, billing, paywalls, or entitlement checks.
- **No closed imports**: Built standalone from public dependencies.
- **Local-only guarantee**: Zero network calls, telemetry, or cloud telemetry.

Why so strict? Apache-2.0 releases cannot be taken back. Keeping the
boundary structural (not just a promise) is what lets the free app stay
genuinely open while leaving room for a sustainable Pro and white-label
business later. The full decision record is
`docs/adr/0001-open-core-closed-pro-split.md`.

The principles governing both repositories are in
[`CONSTITUTION.md`](CONSTITUTION.md). Contribution terms are in
[`CONTRIBUTING.md`](CONTRIBUTING.md).

Agents and automation working in this repo: read `AGENTS.md` first. The
boundary rules there are hard constraints, not guidelines.

## White-label / client builds

The same engine is designed to drive field-notes builds for trades and
professions (contractors, realtors, inspectors): same capture and filing
pipeline, different bucket sets, branding and export targets, configured
rather than forked. Client work is built on this core, in the open where
it is generic, and in client repos where it is not. First client pitch
follows the consumer MVP reaching the closed track.

## Building

- Android Studio (latest stable), JDK 17, compileSdk 37, targetSdk 36, minSdk 31 (AGP 9.5.2, Kotlin 2.4.21).
- Build with the Gradle 9.9.2 wrapper:
  - Assemble debug APK: `./gradlew :app:assembleDebug` (`gradlew.bat :app:assembleDebug` on Windows)
  - Run unit tests: `./gradlew :app:testDebugUnitTest`
  - Run instrumented tests (referencing `docs/API_37_TESTING_PROTOCOL.md` for local API 37 / 16 KB page-size emulator verification): `./gradlew :app:connectedDebugAndroidTest`
- `RecordingService` is powered by `FOREGROUND_SERVICE_MICROPHONE` and `POST_NOTIFICATIONS` permissions, enabling reliable background audio capture even when the app is minimized or the screen locks.

## Project layout

```
app/              the Android app (Compose UI, Room, WorkManager)
  ai/             tier implementations (Nano*, Platform*, Bundled OCR, Heuristics, CapabilityProbe)
  audio/          audio recording and playback management
  capture/        capture session, image capture, and recording service coordination
  data/           Room schema (v4), DAOs, hybrid search repository
  debug/          debug toolbar and seed data generators
  service/        6-stage sorting pipeline worker, quick-settings tile
  ui/             Compose screens (Navigation 3)
  widget/         Glance widget (home and lock screen)
extension-api/    pure Apache-2.0 capability contracts and ExtensionRegistry seam
docs/             architecture notes, ON_DEVICE_AI_GUIDE.md, testing guides, and ADRs
```

## Names

"Voice Note Sorter" is a working title. Final name candidates live in the
concept spec; the repo and package will be renamed when one clears
trademark and Play availability checks. Renaming a repo is cheap. Shipping
the wrong boundary is not.
