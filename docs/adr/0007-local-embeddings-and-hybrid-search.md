# ADR 0007: Local Embeddings Persistence and Hybrid Search

Status: Accepted, 2026-11-18. Supplements ADR 0001, ADR 0002, and ADR 0009.

## Context

`SearchRepository` (`DefaultSearchRepository`) and `Embedder` (`NoOpEmbedder`) were introduced in `app/.../data/` and `app/.../ai/` as extension seams (`ADR 0002`), backed in Open Core by Room FTS4 (`notes_fts`) across Tiers 1 and 2. However:

1. `SortingWorker` still left Stage 5 (`Embed`) as a no-op comment, with no Room entity or persistence for note embeddings.
2. `CONSTITUTION.md` Article IV.4 requires that deleting a note deletes its audio, transcript, tags, actions, search index rows, **and embeddings**, and Article IV.2 requires embeddings to be excluded from platform backup and device transfer.
3. Downstream builds (such as Closed Pro per `ADR 0009`) plugging a neural `Embedder` implementation into the `ai/` seam need:
   - A stable Room table (`note_embeddings`) storing per-note embedding blobs alongside the producing `modelId` and `dimension`.
   - Model-version isolation so vectors produced by one embedding model are never compared against vectors produced by another model, plus lazy re-embedding when a note is re-processed under a new `modelId`.
   - Hybrid ranking in `DefaultSearchRepository` combining FTS4 lexical matches with cosine similarity over active-model embeddings, while Open Core itself ships `NoOpEmbedder` only (zero bundled neural models, zero lexical pseudo-embedders, and zero network permissions).

## Decision

1. **Open Core Ships `NoOpEmbedder` Only (FTS4 Fallback):**
   - Open Core retains `NoOpEmbedder` (`status() == CapabilityStatus.UNAVAILABLE`, `embed(...) == null`, `modelId = "noop"`) as its sole `Embedder` implementation.
   - No lexical pseudo-embedder and no bundled neural embedding weights are added to Open Core. Pure FTS4 (`notes_fts`) remains the shipped retrieval path for Tiers 1 and 2.

2. **Model Identity on the `Embedder` Seam:**
   - `Embedder` exposes `val modelId: String`. `NoOpEmbedder` uses `modelId = "noop"`.
   - Downstream `Embedder` implementations provide a stable identifier for their active model and vector space (e.g., `"gecko-quantized-v1"`).

3. **Room Persistence (`note_embeddings`, Database Version 3, `MIGRATION_2_3`):**
   - `NoteEmbedding` (`@Entity(tableName = "note_embeddings")`) stores:
     - `noteId: Long` (`@PrimaryKey`)
     - `modelId: String`
     - `dimension: Int`
     - `vectorBlob: ByteArray` (IEEE-754 32-bit floats encoded in little-endian byte order via `EmbeddingCodec`)
     - `updatedAtEpochMs: Long`
   - `AppDatabase` advances to `version = 3` with `MIGRATION_2_3` creating `note_embeddings` and exports the Room schema to `app/schemas/com.icanbenchurcat.voicenotes.data.AppDatabase/3.json`.
   - **Cascade Delete & Backup Exclusion:** `NoteDao.deleteNoteCascade` and `NoteDao.deleteAllNotesCascade` atomically delete `note_embeddings` rows alongside action items, tags, notes, and FTS sync triggers, fulfilling `CONSTITUTION.md` Art. IV.4 via `NoteDeletionCoordinator`. Both `backup_rules.xml` and `data_extraction_rules.xml` exclude the entire `database` domain (`path="."`), keeping embeddings off cloud backups and device transfers (`CONSTITUTION.md` Art. IV.2).

4. **Stage 5 (`Embed`) in `SortingWorker.executePipeline`:**
   - `SortingWorker` and `SortingWorker.executePipeline` accept an optional `embedder: Embedder = NoOpEmbedder()` (and `SortingWorker.embedderOverride` for instrumented tests).
   - After Stage 4 persists bucket/tag state, Stage 5 probes `embedder.status()`:
     - When `AVAILABLE`: checks `dao?.embeddingForNote(note.id)`. If no row exists or `existing.modelId != embedder.modelId`, computes `embedder.embed(title + "\n" + summary + "\n" + transcript)` and persists `NoteEmbedding`, recording `embed:OK` (or `embed:SKIPPED` if `embed()` returns null/empty). If a row with `existing.modelId == embedder.modelId` already exists, Stage 5 skips recomputation for idempotency (`CONSTITUTION.md` Art. VII.3) and records `embed:OK`.
     - When `BUSY` or when `embed()` throws `CapabilityBusyException`: logs demotion and records `embed:BUSY` without failing the note or overwriting Stages 1–4 filing state.
     - When `UNAVAILABLE` or `DOWNLOADABLE`: records `embed:SKIPPED`.
     - On unexpected non-cancellation exceptions: records `embed:FAILED` without failing the worker.

5. **Hybrid Vector + FTS4 Search in `DefaultSearchRepository`:**
   - `DefaultSearchRepository(dao, embedder = NoOpEmbedder())` preserves pure FTS4 behavior whenever `embedder.status() != CapabilityStatus.AVAILABLE`, `embedder.embed(query)` returns `null`/empty, or embedding throws a non-cancellation exception (`CapabilityBusyException`, etc.).
   - When `embedder.status() == CapabilityStatus.AVAILABLE` and `embedder.embed(query)` returns a non-empty query vector:
     - Loads `note_embeddings` filtered strictly by `modelId == embedder.modelId`. Rows from other models are ignored by vector scoring and lazily re-embedded when notes re-run through `SortingWorker`.
     - Combines FTS4 matches with cosine similarity (`EmbeddingCodec.cosineSimilarity`) over matching-`modelId` vectors: FTS4 matches are always retained and boosted, while semantic-only matches meeting the minimum cosine similarity threshold (`0.25f` default) are surfaced and ranked by descending similarity.

## Consequences

- Open Core stays offline-only, permission-minimal, and lightweight with zero bundled embedding models while delivering complete Room storage, deletion guarantees, and FTS4 search.
- Downstream modules implementing `Embedder` immediately gain Stage 5 persistence, safe model migration via `modelId` isolation + lazy re-embedding, atomic cascade delete, and hybrid vector+FTS4 search with no changes to core code.
