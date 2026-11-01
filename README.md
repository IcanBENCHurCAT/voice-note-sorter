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
gracefully from flagship on-device AI down to a plain recorder that still
works.

> Status: early scaffold. The licensing boundary described here is the
> contract. The module structure is a single app module today; the
> extension seam is the interface set in the `ai/` package, and extraction
> into a dedicated `:extension-api` module is recorded in
> `docs/adr/0002-module-layout-and-the-extension-seam.md`.
>
> Today: scaffold, capability interfaces, heuristic filing pipeline, Room
> data layer, list and detail screens. Not yet: audio capture, playback,
> search UI, a Settings screen.

## The one idea: a capability ladder, not a feature gate

On-device AI (Gemini Nano via ML Kit GenAI) is powerful but flagship-only,
quota-limited and sometimes busy. This app treats every AI feature as a
capability to probe, not a boolean to assume. Target behaviour at MVP
complete (semantic search lands in v1.1):

| Tier | Hardware | Transcription | Filing | Search |
| --- | --- | --- | --- | --- |
| 3 - Full | Gemini Nano flagships | ML Kit Speech Recognition (Advanced) | Generative title/summary, structured action extraction | Semantic (embeddings, v1.1) + full-text |
| 2 - Core | Most devices, API 31+ | ML Kit Speech Recognition (Basic) / platform recogniser | Heuristic bucketing, extractive summary | Full-text (FTS) |
| 1 - Minimal | Any API 31+ device; also the runtime fallback on any tier when AICore is busy, downloading, or over quota | None (record and play back) | Manual | Full-text on manual titles |

Every capability sits behind an interface with a Nano implementation and a
heuristic/platform implementation. Detection is per capability, cached, and
re-probed when AICore state changes. When a capability is busy or over
quota, the app demotes at runtime instead of erroring. Organisation is
always a correction the user can make, never work the user must do.

## Local-only by design

- No account, no server, no analytics SDKs. Notes, audio and embeddings
  never leave the device.
- The permission list is the privacy policy. The manifest declares:
  microphone (`RECORD_AUDIO`); notifications (`POST_NOTIFICATIONS` and
  `POST_PROMOTED_NOTIFICATIONS`, for Live Updates); and the foreground
  service permissions recording requires (`FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MICROPHONE`). No INTERNET permission.
- Zero running cost is a feature. There is no backend to pay for.

## Repository split and licensing (read this before contributing)

This project is **open core**:

- **This repo** is the free app and reference implementation, licensed
  under the **Apache License 2.0** (see [`LICENSE`](LICENSE)). It will
  never contain ads, paywalls, billing code, or paid features, and the
  open codebase will never be relicensed closed.
- **Pro features**, if and when they exist, live in a **separate, closed
  repository** that plugs into this core through the extension points in
  `docs/ARCHITECTURE.md`. Paid code never enters this repo, in either
  direction: this repo also never imports from the closed one.

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
- Build with Gradle 9.1 (`gradle :app:assembleDebug`). The Gradle
  wrapper gets committed with the first local Studio build; CI already
  provisions Gradle directly (`.github/workflows/build.yml`).

## Project layout

```
app/            the Android app (Compose UI, Room, WorkManager)
  ai/           capability interfaces + tier implementations
  data/         Room schema, DAOs, repository
  service/      sorting pipeline worker, quick-settings tile
  ui/           Compose screens (Navigation 3)
  widget/       Glance widget (home screen; lock-screen variant planned,
                pending the device test pass in the concept spec)
docs/           architecture notes and decision records (ADRs)
```

Planned, not present: Live Updates and the recording foreground service.
They land with the capture work, not before it.

## Names

"Voice Note Sorter" is a working title. Final name candidates live in the
concept spec; the repo and package will be renamed when one clears
trademark and Play availability checks. Renaming a repo is cheap. Shipping
the wrong boundary is not.
