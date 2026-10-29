# AGENTS.md

Instructions for any agent (human or AI) working in this repository. These
rules exist to protect the project's licensing boundary and its users. If a
task conflicts with this file, stop and ask the maintainer instead of
working around it.

## The boundary (hard rules)

1. **This is the open core.** Everything here is Apache-2.0 and public.
   Never commit anything here that is not safe to publish forever:
   no secrets, no credentials, no private keys, no internal URLs.
2. **No paid code, ever.** No ads SDKs, no billing, no paywall or
   entitlement checks, no feature flags whose purpose is gating a paid
   feature, no "pro" stubs. If a task asks for any of these, it belongs in
   the separate closed Pro repository, not here. Say so and stop.
3. **No imports from the closed repo.** This repo must build standalone
   from public dependencies only. Pro functionality attaches through the
   extension points documented in `docs/ARCHITECTURE.md`; do not add a
   dependency, submodule, or binary artefact from the closed repo.
4. **Contributions are Apache-2.0.** Code merged here is licensed under
   Apache-2.0 (see `CONTRIBUTING.md`). Do not paste in code under
   incompatible licences, and do not copy code from the closed repo into
   this one, even code the maintainer wrote.
5. **Local-only is a product guarantee.** Do not add network calls,
   analytics, crash reporting that uploads user content, or third-party
   SDKs that phone home. Notes, audio, transcripts and embeddings never
   leave the device. A change that needs the network needs the
   maintainer's explicit approval first.

## Working rules

- **Capability, not assumption.** Every on-device AI feature goes behind
  the interfaces in `app/.../ai/` with a non-AI fallback implementation.
  UI code calls the interface, never ML Kit directly. Runtime demotion on
  BUSY/quota is normal behaviour, not an error path.
- **Small, complete commits.** One logical change per commit, with the
  build green. Do not commit code you have not compiled; if you cannot
  compile in your environment, say so in the PR description.
- **Tests for the ladder.** Changes to capability detection, the sorting
  pipeline, or bucket assignment need unit tests covering at least the
  Tier 1 (no AI) path.
- **Docs move with code.** If you change the architecture, the extension
  points, or the boundary, update `docs/ARCHITECTURE.md` and add or amend
  an ADR in `docs/adr/` in the same change.
- **No silent scope growth.** The MVP scope is the capture-to-filed loop.
  New features (reminders, import, Wear, AppFunctions) are listed as later
  scope in the concept spec; do not start them without the maintainer
  opening an issue for it.

## Repository facts

- Package: `com.icanbenchurcat.voicenotes` (working; renamed with the app)
- Stack: Kotlin, Jetpack Compose (Material 3 Expressive), Navigation 3,
  Room, WorkManager, Glance, ML Kit GenAI behind interfaces
- minSdk 31, targetSdk 36, compileSdk 37
- Build: `gradle :app:assembleDebug` (Gradle 9.1, JDK 17; wrapper is
  committed with the first local Studio build); CI runs the same
