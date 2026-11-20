# ADR 0010: ADR 0008 Room Schema Version Correction (v4 / `MIGRATION_3_4`)

Status: Accepted, 2026-11-20. Clarifies ADR 0008 section 2.

## Context

`ADR 0008` (`docs/adr/0008-image-to-note-ingestion.md`, written 2026-11-18) stated in section 2 ("Unified `Note` Schema Extension & Room Migration (v2 → v3)") that `AppDatabase` advances to Room schema version `3` with `MIGRATION_2_3` adding `sourceType` and `imagePath`.

Before the image-to-note ingestion work landed, PR #152 (Stage 5 `Embedder` and `note_embeddings`, `ADR 0007`) claimed Room schema version `3` and `MIGRATION_2_3`. Consequently, PR #154 (commit `9528823a`) implemented the image-ingestion schema extension as v3 → v4 (`version = 4`, `MIGRATION_3_4`) in `app/src/main/java/com/icanbenchurcat/voicenotes/data/AppDatabase.kt` and exported `app/schemas/com.icanbenchurcat.voicenotes.data.AppDatabase/4.json`.

## Decision

Per `CONSTITUTION.md` Article VIII.2 ("Editing a ratified ADR to match code that diverged from it is prohibited; the divergence is recorded, not erased"), ratified `ADR 0008` is preserved unmodified.

This ADR formally records that the implemented Room schema version is `4` (`MIGRATION_3_4`), while the added columns (`sourceType TEXT NOT NULL DEFAULT 'AUDIO'`, `imagePath TEXT NOT NULL DEFAULT ''`), `notes_fts` behavior, zero-permission manifest, bundled `com.google.mlkit:text-recognition` OCR, backup exclusion, and complete deletion match `ADR 0008` verbatim.

## Consequences

- No code changes are needed; `app/src/main/java/com/icanbenchurcat/voicenotes/data/AppDatabase.kt` (`version = 4`, `MIGRATION_3_4`) and schema export `app/schemas/com.icanbenchurcat.voicenotes.data.AppDatabase/4.json` remain the authoritative schema state.
- `ADR 0008` remains intact as ratified, with this ADR serving as the canonical record of the v3 → v4 schema numbering adjustment.
