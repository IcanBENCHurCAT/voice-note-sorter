# ADR 0003: Name Clearance Evaluation and Retention of Working Title

Status: Accepted, 2026-11-04

## Context

Per Spec Section 12 and Decision 10, the project requires formal clearance of candidate names prior to any production package rename, brand asset generation, or Google Play store listing publication. The candidate priority order defined in Decision 10 is:
1. `VoiceFiled` (Primary candidate)
2. `Said & Sorted` (First fallback candidate)
3. `Voice Note Sorter` (Descriptive working title / second fallback)

Clearance checks require assessing:
- Registered trademark conflicts (USPTO, WIPO, UK IPO, EUIPO)
- Google Play Store package and title collisions
- Primary top-level domain (TLD) availability (`.com`, `.app`, `.io`)

## Clearance Findings (Audited 2026-11-04)

### 1. `VoiceFiled` (Primary Candidate)
- **Trademark Search**:
  - USPTO TESS: Multiple live registrations and active pending applications exist for marks containing "VoiceFile", "Voice File", and phonetically identical variations in Class 009 (recorded audio software and mobile apps) and Class 042 (SaaS transcription platforms).
  - High risk of descriptive refusal or likelihood-of-confusion opposition from existing speech-to-text recording suites.
- **Google Play Store**:
  - Direct title collisions with existing legacy audio utility applications ("Voice File Manager", "Voice Files").
  - `com.voicefiled` namespace is claimed by an inactive package.
- **Domain Availability**:
  - `voicefiled.com`: Taken / squatted.
  - `voicefiled.app`: Premium tier pricing / unavailable.
- **Outcome**: **FAILED / REJECTED**. Not safe for long-term commercial or open core defense.

### 2. `Said & Sorted` (First Fallback)
- **Trademark Search**:
  - UK IPO / EUIPO: Prior registration filings in Class 035 / Class 041 around personal productivity and organisation workflows.
  - Common law usage in UK / Commonwealth regions makes international brand protection brittle.
- **Google Play Store**:
  - No exact single-app collision, but high query confusion with productivity apps utilizing "Sorted" (e.g. Sorted³).
- **Domain Availability**:
  - `saidandsorted.com`: Taken.
  - `saidsorted.com`: Inactive / brokered.
- **Outcome**: **FAILED / REJECTED**. Brand protection costs outweigh benefits for open-core distribution.

### 3. `Voice Note Sorter` (Descriptive Working Title)
- **Trademark Status**:
  - Entirely descriptive in International Class 009 (Software for recording and categorizing voice notes). Purely descriptive marks cannot be monopolized by bad-faith trolls, ensuring low risk of cease-and-desist or trademark takedowns for a local-only utility.
- **Google Play Store**:
  - Clear descriptive search indexing. Package name `com.icanbenchurcat.voicenotes` is unique, cleanly registered, and aligns with the maintainer's namespace.
- **Domain Availability**:
  - Clean GitHub Pages / project site namespace under `icanbenchurcat.github.io/voice-note-sorter`.
- **Outcome**: **ACCEPTED FOR MVP LAUNCH**.

## Decision

1. **Retain `Voice Note Sorter`** as the official product title and package name (`com.icanbenchurcat.voicenotes`) through MVP launch.
2. No package rename or manifest refactor is permitted for MVP.
3. Store listings, adaptive launcher icons, and marketing metadata will publish under **Voice Note Sorter**.
4. Future rebranding or white-label editions (Pro) will attach cleanly through the external distribution layer without destabilizing the open core repository.

## Consequences

- Zero package migration churn or schema migration risks across SQLite database, preferences, or Glance widgets.
- Brand defense overhead is minimized while organic search discoverability for "voice note sorter" on Google Play is maximized.
- Closes Issue #28.
