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
> `AGENTS.md` is the hard contract. The module structure is a single app
> module; the extension seam is the interface set in the `ai/` package, and
> extraction into a dedicated `:extension-api` module is recorded in
> `docs/adr/0002-module-layout-and-the-extension-seam.md`.
>
> **Shipped on `main` today**: One-tap voice capture (`RecordingService`
> foreground service, Glance home-screen widget, Quick Settings tile),
> 5-stage fault-tolerant `SortingWorker` pipeline, 3-tier capability ladder
> (`NanoTranscriber`, `PlatformTranscriber`, `NullTranscriber` +
> `HeuristicBucketer`, `ExtractiveSummarizer`, `HeuristicActionExtractor`,
> `PlatformDateParser`), Room + FTS4 full-text search UI, `NotesListScreen`
> (bucket, tag, and Needs Review filters), `NoteDetailScreen` (original
> audio playback, inline editing, text/audio sharing via `FileProvider`,
> bucket confirmation/reassignment), `TasksScreen`, `SettingsScreen` (live
> `CapabilityProbe`, storage usage breakdown, opt-in 90-day audio
> auto-delete, Developer Options), and automated unit + API 31/36/37
> instrumented test suites.
>
> **Planned (post-MVP / tracked)**: Tier 3 foundation-model `NanoSummarizer`,
> `NanoActionExtractor`, and `NanoEmbedder` once ML Kit GenAI artifacts
> stabilize; background retry worker when downloadable models finish
> installing; audio file import; task reminders; Wear OS tile/complication;
> and Android 16+ `AppFunctions`.

## The one idea: a capability ladder, not a feature gate

On-device AI (Gemini Nano via ML Kit GenAI) is powerful but flagship-only,
quota-limited and sometimes busy. This app treats every AI feature as a
capability to probe, not a boolean to assume. Shipped capability tiers:

| Tier | Hardware | Transcription | Filing | Search |
| --- | --- | --- | --- | --- |
| 3 - Full | Gemini Nano flagships | ML Kit Speech Recognition (Advanced) | Generative title/summary, structured action extraction | Semantic (embeddings, planned) + full-text |
| 2 - Core | Most devices, API 31+ | ML Kit Speech Recognition (Basic) / platform recogniser | Heuristic bucketing, extractive summary | Full-text (FTS) |
| 1 - Minimal | Any API 31+ device; also the runtime fallback on any tier when AICore is busy, downloading, or over quota | None (record and play back) | Manual | Full-text on manual titles |

Every capability sits behind an interface with a Nano implementation and a
heuristic/platform implementation. Detection is per capability, cached, and
re-probed when AICore state changes. When a capability is busy or over
quota, the app demotes at runtime instead of erroring. Organisation is
always a correction the user can make, never work the user must do.

## Local-only by design

- No account, no server, no analytics SDKs. Notes, audio and embeddings
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

- Android Studio (latest stable), JDK 17, compileSdk 37, minSdk 31.
- Build with the Gradle 9.1 wrapper:
  - Assemble debug APK: `./gradlew :app:assembleDebug` (`gradlew.bat :app:assembleDebug` on Windows)
  - Run unit tests: `./gradlew :app:testDebugUnitTest`
  - Run instrumented tests (referencing `docs/API_37_TESTING_PROTOCOL.md` for local API 37 / 16 KB page-size emulator verification): `./gradlew :app:connectedDebugAndroidTest`
- `RecordingService` is powered by `FOREGROUND_SERVICE_MICROPHONE` and `POST_NOTIFICATIONS` permissions, enabling reliable background audio capture even when the app is minimized or the screen locks.

## Project layout

```
app/            the Android app (Compose UI, Room, WorkManager)
  ai/           capability interfaces + tier implementations
  audio/        audio recording and playback management
  capture/      capture session and recording service coordination
  data/         Room schema, DAOs, repository
  debug/        debug toolbar and seed data generators
  service/      sorting pipeline worker, quick-settings tile
  ui/           Compose screens (Navigation 3)
  widget/       Glance widget (home and lock screen)
docs/           architecture notes, testing guides, and decision records (ADRs)
```

## Names

"Voice Note Sorter" is a working title. Final name candidates live in the
concept spec; the repo and package will be renamed when one clears
trademark and Play availability checks. Renaming a repo is cheap. Shipping
the wrong boundary is not.
