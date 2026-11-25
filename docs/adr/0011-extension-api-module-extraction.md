# ADR 0011: `:extension-api` Module Extraction (ADR 0002 Trigger #1)

Status: Accepted, 2026-11-25. Implements the extraction ADR 0002
scheduled; supplements ADR 0001, ADR 0002, and ADR 0009.

## Context

ADR 0002 kept the extension seam package-level in `:app` until the
first of three triggers fired: (1) the first Pro feature starting in
the closed repo, (2) the first white-label build, or (3) the first
external consumer of the seam.

Trigger #1 has fired: the first Pro feature is starting in the closed
`voice-note-sorter-pro` repository (tracked there as its issue #1),
and per the Constitution Article II addendum the closed build consumes
the core only through the `:extension-api` seam. The module therefore
had to exist before that work could proceed against it.

## Decision

1. **New module.** `:extension-api` is an Android library module
   (settings: `include(":app", ":extension-api")`), licensed strictly
   Apache-2.0. It contains contracts only: no implementations, no UI,
   no ML Kit references, and no billing, paywall, entitlement, or
   paid-feature material of any kind (Constitution Article II.1). Its
   only dependency is `kotlinx-coroutines-core`, exposed as `api`
   because the contracts declare `suspend` functions.

2. **What moved.** The following pure contract types moved from
   `:app` (`ai/Capabilities.kt`) into `:extension-api`, in the same
   packages (`com.icanbenchurcat.voicenotes.ai`), so `:app` code and
   tests compile unchanged and `:app` now depends on the module via
   `implementation(project(":extension-api"))`:
   `Tier`, `CapabilityStatus`, `Transcript`, `CapabilityBusyException`,
   `Transcriber`, `Proofreader`, `ImageTranscriber`, `Summarizer`,
   `TitleSummary`, `ActionExtractor`, `ExtractedAction`, `Bucketer`,
   `BucketAssignment`, `BucketProfile`, `BucketDef`, `Embedder`,
   `BucketProfileProvider`.

3. **What stayed in `:app`, and why.**
   - The free default implementations `NullImageTranscriber`,
     `NoOpEmbedder`, and `DefaultBucketProfileProvider` stay in
     `:app`: they are implementations, not contracts, and the open
     build ships them (ADR 0002, Constitution Article II.3).
   - `SearchRepository` does **not** move. Its signature is
     `search(query): Flow<List<Note>>` and `Note` is a Room entity;
     moving the interface would drag Room into a module that must
     stay a pure contract surface. It remains in `:app` alongside
     `DefaultSearchRepository`. Moving it is future work only if its
     signature is ever decoupled from the Room entity, which is a
     separate decision, not part of this extraction.

4. **ExtensionRegistry.** The concept spec's `ExtensionRegistry` hook
   (previously recorded as not yet implemented) is now implemented in
   `:extension-api`: one nullable slot per capability contract, filled
   by a single `register(...)` call, last write wins.
   `VoiceNotesApp` (the `:app` composition root) registers the open
   core's free primary implementations at startup: `NanoTranscriber`,
   `NanoProofreader`, `NanoImageTranscriber`, `NanoSummarizer`,
   `NanoActionExtractor`, `NanoBucketer`, `NoOpEmbedder`, and
   `DefaultBucketProfileProvider`. These are exactly the
   implementations `SortingWorker` constructs today.

5. **Registration only, for now.** No existing consumer was rewired.
   `SortingWorker` still constructs its own instances (including its
   test-override path), and `DefaultSearchRepository` is still
   constructed where it was. Nothing reads the registry yet. This is
   deliberate: the extraction is a move, not a redesign (ADR 0002),
   and rewiring consumers is separate work to be taken up when a
   consumer actually needs the registry.

## Consequences

- The closed Pro build can now depend on `:extension-api` and code
  against the capability contracts and `ExtensionRegistry` without
  depending on `:app` internals. The dependency direction stays
  one-way: the open core never references the closed repo.
- `:app` gains one project dependency; package names did not change,
  so no call site or test needed edits for the move itself.
- The seam's purity is now enforced structurally: a contract type
  that drags in Room, ML Kit, or Android UI cannot enter
  `:extension-api` without adding a dependency the module does not
  declare, which fails the build instead of eroding the boundary
  quietly.
- `docs/ARCHITECTURE.md` describes the two-module layout and the
  registry as shipped behaviour as of this ADR.
