# ADR 0002: Module layout and the extension seam

Status: Accepted, 2026-10-30. Supplements ADR 0001; does not supersede it.

## Context

The concept spec (Sections 5 and 10) prescribed a multi-module layout
from the first commit: `:core`, `:feature:*`, and a published
`:extension-api` Gradle module, with the split "enforced in Gradle from
the first commit."

The scaffold that actually shipped is a single `:app` module. The
extension seam is real but package-level: the interfaces in
`app/.../ai/` (`Transcriber`, `Summarizer`, `ActionExtractor`,
`Bucketer`, `BucketProfileProvider`), of which `BucketProfileProvider`
is the only downstream seam in use today. ADR 0001 requires a change to
a recorded decision to be made by a new ADR written before the change;
the divergence happened without one. This ADR records it, per
Constitution Article VIII.2: the divergence is recorded, not erased.

## Decision

- The single `:app` module is the layout until a named trigger fires.
- The seam today is the `ai/` package interface set. That is the
  contract downstream work codes against.
- Extraction into a dedicated `:extension-api` module happens at the
  first of: (1) the first Pro feature starting in the closed repo,
  (2) the first white-label build, or (3) the first external consumer
  of the seam. Until then, extracting a module with no consumer is
  ceremony, not structure.
- ADR numbering in this repo is four-digit (`0001`, `0002`, ...). The
  concept spec's "ADR-001" references are read as this series.
- The spec hooks named in concept spec Section 10 (`EntitlementProvider`,
  `FeatureGate`, `ExportDestination`, `ReportTemplate`, BucketPack,
  settings/navigation contribution) are defined in the spec, not yet
  implemented. They are implemented only when a consumer exists, and
  any paid behaviour behind them lives in the closed repo per ADR 0001.

## Consequences

- The open core builds and reads as one module; contributors are not
  taxed with multi-module overhead before there is a second consumer.
- The extraction is a move of existing interfaces, not a redesign, when
  the trigger fires. Keeping the interfaces clean and UI-free is what
  makes that true, which is why Constitution Article III.1 exists.
- README and `docs/ARCHITECTURE.md` describe the package-level seam as
  it is, and name this ADR for the extraction plan.
