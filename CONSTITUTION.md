# Voice Note Sorter Constitution

## Preamble

Voice Note Sorter is one application maintained as two repositories: a
public open core licensed under Apache-2.0, and a private closed
repository for Pro and client work that may be created when the first
paid feature is built. This constitution governs both repositories, all
contributors and agents working in them, and every build produced from
them. Where this constitution conflicts with a plan, a specification, or
a deadline, this constitution prevails until it is amended under
Article VIII.

## Article I. Mission and the Local-Only Guarantee

1. The application captures a voice note in one action, files it
   automatically, and retrieves it by meaning. Capture speed and
   reliability are the product; automation exists to serve them.
2. All processing of user content happens on the user's device. Notes,
   audio, transcripts, and embeddings MUST NOT leave the device except
   through an export or share action the user explicitly invokes.
3. The application MUST function with no account, no server, and no
   network connection. There is no backend, and none may be introduced
   under this article without an amendment.
4. The open core MUST remain a complete, useful application on its own.
   It MUST NOT be reduced to a demonstration or teaser for the closed
   product.

## Article II. The Boundary

1. Anything that might ever be charged for MUST NOT enter the open
   repository: no Pro features, billing, paywall or entitlement
   behaviour, licence checks, trial timers, feature locks, advertising
   code or SDKs, or white-label-only assets. Not in a branch, not
   behind a flag, not temporarily.
2. Dependency direction is one-way. The closed repository MAY depend on
   the open core through its documented extension points. The open core
   MUST NOT import, link, copy, vendor, or reference closed code,
   closed assets, or a closed binary, for any purpose including tests
   and screenshots.
3. The extension seam consists of interfaces with free default
   implementations in the open core. A closed build replaces
   implementations in its own composition root. The open build ships
   only the free implementations.
4. A fix discovered during closed work that belongs in the core MUST be
   re-implemented in the open repository on its own merits, under
   Apache-2.0, with no reference to closed code.
5. Secrets, credentials, client names, and client material MUST NOT
   appear in the open repository, including in commits, issues, and
   fixtures.
6. Advertising, if ever run, runs only in the maintainer's separately
   composed builds. Advertising code and SDKs MUST NOT exist in the
   open repository.

## Article III. The Capability Ladder and Honest Degradation

1. Every on-device AI capability MUST sit behind an interface in the
   core, with at least one non-AI fallback implementation. User
   interface code MUST NOT call a model API directly.
2. Capabilities are probed per capability, never assumed from the
   device model. A probe result is cached and re-probed when the
   underlying service state changes.
3. A capability that is busy, downloading, unavailable, or over quota
   causes demotion to the next tier for that stage. Demotion is normal
   behaviour, MUST NOT be surfaced to the user as an error, and MUST
   NOT lose the note.
4. The capture path MUST NOT depend on any AI capability. Recording
   works identically on every tier.
5. A capability's reported status MUST reflect a real check of the
   underlying service. Hardcoded or assumed status is a defect.
6. Documentation, store listings, and in-app copy MUST describe the
   tier the user's device actually receives, and MUST label unshipped
   capabilities as planned. Present tense is reserved for shipped
   behaviour.

## Article IV. Privacy by Permission

1. The permission list is the privacy policy. The open core MUST NOT
   declare the INTERNET permission.
2. Adding a permission, or changing backup, restore, or data-extraction
   behaviour, requires the maintainer's explicit approval recorded in
   an issue before the change is made. Audio, the database, and
   embeddings MUST be excluded from platform backup and device transfer
   unless the maintainer approves a specific design in an ADR.
3. No analytics, telemetry upload, or crash reporting that can carry
   user content may be added to either repository. Diagnostics stay on
   the device.
4. Export and delete MUST be complete: deleting a note deletes its
   audio, transcript, tags, actions, search index rows, and embeddings.

## Article V. Scope Discipline

1. The minimum viable scope is the capture-to-filed loop: capture,
   transcription, filing, and retrieval on every tier.
2. Features designated as later scope in the concept specification
   (including reminders, import, Wear surfaces, and assistant
   integration) MUST NOT be started in either repository without an
   issue opened by the maintainer that names the feature and the
   release it targets.
3. Scope changes are recorded. Adding a feature to a release requires
   removing scope, moving the date, or an explicit maintainer decision
   recorded in the release issue. Silent scope growth is a defect.
4. White-label and client variation is configuration: bucket sets,
   vocabulary, branding, and export targets. A client request that
   requires forking the engine is a separate product decision and MUST
   be scoped and priced as one.

## Article VI. Contributions and Licensing

1. The open core is licensed under Apache-2.0 and will never be
   relicensed closed. Contributions are accepted under Apache-2.0,
   inbound equals outbound, with no contributor licence agreement.
2. Contributions MAY be used commercially, including in builds that
   bundle the closed module. Contributors retain their copyright.
3. Every source file in the open repository carries the header
   `SPDX-License-Identifier: Apache-2.0`. Source files in the closed
   repository carry a proprietary header. A file carrying the wrong
   header for its repository MUST NOT merge.
4. Contributions of paid or Pro behaviour to the open repository are
   redirected to the closed repository, not merged.

## Article VII. Quality Gates

1. No change merges with a failing build. A change the author could not
   compile MUST say so in its pull request description.
2. Changes to capability detection, the sorting pipeline, or bucket
   assignment MUST include unit tests covering at least the Tier 1
   (no AI) path. Each AI interface ships with a fake so pipeline tests
   run without a flagship device.
3. The sorting pipeline MUST be safe to re-run: every stage persists
   its own output, and re-processing a note produces the same state
   as processing it once.
4. Documentation moves with code. A change to the architecture, the
   extension points, or the boundary MUST update
   `docs/ARCHITECTURE.md` and add or amend an ADR in the same change.
5. Commits are small and complete: one logical change per commit, with
   the build green.
6. The continuous integration boundary guard MUST pass on every change
   to the open repository: it fails the build on billing or
   advertising dependencies, on proprietary licence headers, and on a
   declared INTERNET permission.

## Article VIII. Decisions, ADRs, and Amendment

1. Decisions that affect the architecture, the boundary, licensing, or
   scope are recorded as Architecture Decision Records in `docs/adr/`,
   numbered sequentially in four-digit form, before the change they
   authorise is made.
2. An ADR is superseded only by a later ADR that names it. Editing a
   ratified ADR to match code that diverged from it is prohibited; the
   divergence is recorded, not erased.
3. This constitution is amended by the maintainer. Amendments carry a
   version number: a change to a MUST principle requires a major
   version bump, a new principle or expanded guidance a minor bump,
   and a clarification a patch bump. Every amendment is recorded in
   the footer with its date.
4. Working rules for day-to-day development live in `AGENTS.md` in
   each repository. `AGENTS.md` implements this constitution and MUST
   NOT contradict it.

## Article IX. Conflicts and Deadlines

1. No deadline, launch date, client commitment, or testing window
   authorises violating Articles I, II, IV, or VI. Those articles may
   be changed only by amendment under Article VIII, made before the
   work that depends on the change.
2. When a rule in Articles III, V, or VII conflicts with a deadline,
   the maintainer chooses explicitly among shipping less, shipping
   later, or amending the rule, and records the choice in an ADR or
   the release issue. The choice is never made silently by the person
   doing the work.
3. A change made in violation of this constitution is reverted or
   remediated when discovered, regardless of when it landed. Discovery
   after release does not ratify it, and for Apache-2.0 code already
   published, remediation includes the disclosure required by the
   consequences recorded in ADR 0001.

**Version**: 1.0.0 | **Ratified**: 2026-10-30 | **Last Amended**: 2026-10-30
