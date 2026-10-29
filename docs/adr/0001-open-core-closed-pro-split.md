# ADR 0001: Open core, closed Pro, structural split from day one

Status: Accepted, 2026-10-29

## Context

The app is free, local-only, and intended to double as an open
best-practices reference implementation. The maintainer also wants the
option, later, to run ads, sell a Pro version, and build white-label
client versions. The open core is licensed Apache-2.0, which permits all
of that commercially for the copyright holder.

Two properties of Apache-2.0 shape this decision:

1. Releases cannot be taken back. Code published under Apache-2.0 stays
   available under Apache-2.0 forever, and anyone may fork it, remove
   ads, or unlock a paywall implemented in the open code.
2. Outside contributions arrive under Apache-2.0. They can be used
   commercially, but the combined open codebase can never later be
   relicensed as closed without every contributor's consent.

## Decision

The project is split into two repositories from day one **in structure**,
even though the second repository may be created later:

- **Open core (this repo):** the free app and reference implementation,
  Apache-2.0. Contains no ads, billing, paywall, entitlement, or
  paid-feature code of any kind, including dormant stubs and flags.
- **Closed Pro repo (separate, private):** any paid features, ad
  integrations for the maintainer's own builds if ever used, and
  client-specific white-label code. It depends on the core through the
  extension points in `docs/ARCHITECTURE.md`.

Rules that keep the boundary real:

- The core builds standalone from public dependencies only. It never
  imports, links, or vendors code from the closed repo.
- The closed repo consumes the core; information flows one way.
- Extension points are designed as if the Pro repo already exists, so
  nothing paid is ever "temporarily" parked in the core.
- The boundary is written into `README.md`, `AGENTS.md` and
  `CONTRIBUTING.md`, not held in the maintainer's head, so it survives
  contributors and agent sessions.
- If ads are ever run, they run in the maintainer's distributed builds
  and configuration, not as code in this repository.

## Consequences

- Forks get a genuinely useful free app. That is accepted and intended;
  the business, if any, is Pro features, support and client work, not
  secrecy around the core.
- Some duplication of effort is possible at the extension seam; that is
  the price of a clean licence story, and it is cheaper than untangling
  a mixed repo later.
- Any future change to this decision requires a new ADR superseding this
  one, written before the change is made.
