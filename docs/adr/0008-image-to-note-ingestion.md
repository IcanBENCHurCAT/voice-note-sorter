# ADR 0008: Zero-Permission Handwritten & Printed Image-to-Note Ingestion Pipeline

Status: Accepted, 2026-11-18

## Context

Users frequently want to capture handwritten notebook pages, whiteboards, or printed sheets alongside spoken thoughts and have them transcribed, summarized, action-extracted, tagged, and filed into the same searchable local-first knowledge base.

Under `CONSTITUTION.md` Articles I, II, III, and IV:
- The open core MUST function completely offline with no network connection and no `INTERNET` permission (Article I.3, Article IV.1).
- Permissions are the privacy policy: requesting `CAMERA` or `READ_MEDIA_IMAGES` expands the trust boundary unnecessarily when Android provides system-mediated pickers and camera delegation (Article IV.2).
- Every on-device AI capability MUST sit behind an interface in `app/.../ai/` with deterministic degradation down to a Tier 1 (`MINIMAL`) non-AI fallback, and runtime `BUSY`/quota demotion MUST NOT lose the user's note (Article III.1–III.4).
- Private captured media MUST be excluded from cloud backup / device-to-device transfer and MUST be completely deleted when a note or all notes are deleted (Article IV.2, Article IV.4).

## Decision

1. **Zero-Permission Image Capture & App-Private Storage:**
   - Capture uses Android's system Photo Picker (`ActivityResultContracts.PickVisualMedia`) and system camera delegation (`ActivityResultContracts.TakePicture`) writing directly into an app-private directory (`filesDir/images/`) via `FileProvider` (`ImageFileManager`).
   - `AndroidManifest.xml` declares **zero new permissions** (`CAMERA`, `READ_MEDIA_IMAGES`, and `INTERNET` remain absent).
   - Picked images from Photo Picker URIs are copied immediately into `filesDir/images/` so temporary picker grants can expire safely without losing local media.

2. **Unified `Note` Schema Extension & Room Migration (v2 → v3):**
   - `Note` in `Entities.kt` gains `sourceType` (`"AUDIO"` | `"IMAGE"`, SQL default `'AUDIO'`) and `imagePath` (SQL default `''`).
   - `AppDatabase` advances to schema version `3` with `MIGRATION_2_3` adding both columns via `ALTER TABLE` without disturbing existing audio notes or `notes_fts`.

3. **Three-Tier `ImageTranscriber` Capability Ladder:**
   - Added `ImageTranscriber` and `NullImageTranscriber` in `app/.../ai/Capabilities.kt`:
     - **Tier 3 (`FULL`):** `NanoImageTranscriber` (in `GenerativeAi.kt`) passes an `ImagePart(Bitmap)` + `TextPart` prompt through `GenAiPromptClient` (`com.google.mlkit:genai-prompt`). Propagates `CapabilityBusyException` on `BUSY`/quota; falls back to Tier 2 when `UNAVAILABLE`, `DOWNLOADABLE`, or blank output is returned.
     - **Tier 2 (`CORE`):** `MlKitOcrImageTranscriber` (in `ImageTranscribers.kt`) runs completely offline using the **bundled** `com.google.mlkit:text-recognition` artifact behind a testable `BundledOcrEngine` seam. The Play Services unbundled OCR artifact (`com.google.android.gms:play-services-mlkit-text-recognition`) is **explicitly prohibited** in the open core because it downloads its model over the network (`CONSTITUTION.md` Art. I.3 & Art. II addendum).
     - **Tier 1 (`MINIMAL`):** `NullImageTranscriber` returns `Transcript("", Tier.MINIMAL)`.

4. **Pipeline Branching in `SortingWorker`:**
   - Stage 1 in `SortingWorker.executePipeline` branches on `note.sourceType`: `"IMAGE"` routes through `imageTranscriber` → `fallbackImageTranscriber` → `nullImageTranscriber` (and `"AUDIO"` continues through `transcriber` → `platformTranscriber` → `nullTranscriber`).
   - When an `"IMAGE"` note produces a blank transcript or runs on Tier 1 (`NullImageTranscriber`), the pipeline retains `imagePath`, sets `status = NEEDS_REVIEW`, and preserves the note for manual entry/filing.
   - Non-blank transcripts proceed identically through Stages 2–4 (title/summary, action extraction, bucketing/tagging, and `notes_fts` indexing).

5. **Backup Exclusion & Complete Deletion:**
   - `filesDir/images/` is excluded from Auto Backup, Google Drive cloud backup, and device-to-device transfer in `backup_rules.xml` and `data_extraction_rules.xml`.
   - `NoteDeletionCoordinator` deletes `note.imagePath` on single-note deletion and deletes all tracked and orphaned image files in `filesDir/images/` on `deleteAllNotes`.
   - `StorageRetentionManager` includes `filesDir/images/` in `StorageStats` (`imageSizeBytes`).

## Alternatives Considered

- **Requesting `CAMERA` and `READ_MEDIA_IMAGES` permissions:** Rejected. In-app CameraX capture or broad gallery queries would force runtime permissions onto users who only want occasional whiteboard/notebook capture. System Photo Picker and `TakePicture` provide zero-permission parity.
- **Unbundled Play Services Text Recognition (`play-services-mlkit-text-recognition`):** Rejected. Although smaller in APK size, it fetches the OCR model dynamically over the network via Google Play Services, violating the open core's strict offline / zero-network-model-download boundary (`CONSTITUTION.md` Art. I.3 & Art. II addendum; `ADR 0009`).
- **Separate `image_notes` SQLite table:** Rejected. Extending `Note` with `sourceType` and `imagePath` reuses `notes_fts`, `action_items`, `note_tags`, bucket filtering, editing, and deletion cascades uniformly.

## Consequences

- Users can capture or import handwritten and printed notes without granting `CAMERA` or `READ_MEDIA_IMAGES` permissions.
- Bundling `com.google.mlkit:text-recognition` increases APK size slightly in exchange for guaranteed offline OCR on Tier 2 devices with zero network calls.
- Heavy open-weight Vision-Language OCR models (for complex handwriting without AICore) remain in scope for Closed Pro via the `ImageTranscriber` extension seam (`ADR 0002`, `ADR 0009`).
