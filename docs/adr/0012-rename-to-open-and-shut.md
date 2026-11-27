# ADR 0012: Rename the App to Open & Shut

Status: Accepted, 2026-11-27 (maintainer decision)
Supersedes: ADR 0003, decision points 1 and 3 (retention of the working
title and publishing under it). ADR 0003's package-stability rationale is
preserved; see Decision point 2.

## Context

ADR 0003 (2026-11-04) retained the descriptive working title "Voice Note
Sorter" through MVP launch after the two preferred candidates failed
clearance. Since then the offline voice-notes niche has filled with
closed-source competitors (Offscript, Hushnote, Hushdo, VoiceVault, Libre
Voice Note and others) selling a "trust us, no cloud" pitch that users
cannot verify. This app's differentiators are that its free core is open
source under Apache-2.0 and that the no-INTERNET-permission claim is
checkable in the merged manifest and in Android's own permission list.
The maintainer decided on 2026-11-27 to launch under a brand built on
that contrast: the code is public, the user's notes are not.

Name checks performed 2026-11-27: Google Play and web collision search
found no notes or voice app using "Open & Shut" (one unrelated weather
app, "Open/Shut," exists in a different category). A formal USPTO
trademark search is still outstanding and is tracked as a pre-promotion
task; launching the free MVP under the name before that search completes
is an accepted, recorded risk.

## Decision

1. The product display name is **Open & Shut**, effective with this
   change: the `app_name` string, the Play store listing, and marketing
   metadata publish under the new name.
2. The package name is unchanged: `com.icanbenchurcat.voicenotes`. The
   repository name is also unchanged. The rename is display-name only,
   so there is no package, database, preferences, or widget migration.
3. Instrumented tests that assert the header title text are updated to
   the new name in the same change.
4. References to the product name inside CONSTITUTION.md are NOT changed
   here. The constitution is a ratified document; its title and product
   references are to be updated through a separate constitutional
   amendment, not a rename PR.

## Consequences

- Store assets (screenshots, feature graphic, demo video) are to be
  regenerated under the new name before the Play listing is created.
- Historical documents and ADRs keep the old working title unedited;
  ADR 0003 remains the record of why it was retained at the time.
- If the outstanding trademark search surfaces a conflict, the fallback
  is a further display-name change only; no code or package churn is
  implied by this ADR.
