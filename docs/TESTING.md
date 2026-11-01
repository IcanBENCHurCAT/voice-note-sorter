# Manual Device-Testing Protocol

Repeatable manual and emulator testing protocol for **Voice Note Sorter**.
Run the sections below in order on each target in the device matrix so every
build is verified against the same checklist and results are recorded honestly.

> **Status honesty rule (`AGENTS.md`):** Only features that have shipped on
> `main` have executable pass/fail steps below. Unshipped features (capture,
> playback, search) are listed as explicit stubs naming the issue that unlocks
> them.

---

## 1. Device Matrix

| Target | API Level | Role / Tier Coverage | Required? | Local / CI Reference |
| :--- | :--- | :--- | :--- | :--- |
| **API 31 Emulator** | Android 12 (API 31) | `minSdk = 31` floor; Tier 1 (`MINIMAL`) & Tier 2 (`CORE`) fallback path | **Required** | Local AVD `Pixel_6_API_31` (`google_apis/x86_64`) / CI `aosp_atd` API 31 |
| **API 36 / 37 Emulator** | Android 16 / 17 (API 36 / 37.0) | `targetSdk = 36`, `compileSdk = 37`, 16 KB page-size (`ps16k`) verification | **Required** | Local AVD `Pixel_10_Pro_XL` (`google_apis_playstore_ps16k/x86_64`, API 37.0, 16 KB page size) / CI `aosp_atd` API 36 |
| **Physical Tier 2 Device** | API 31+ | Real microphone, `SpeechRecognizer`, foreground service, Glance host, Quick Settings tile, TalkBack | **Required** (pre-release / hardware pass) | Any API 31+ phone without Gemini Nano / AICore |
| **Physical Tier 3 Flagship** | API 35+ with AICore / Gemini Nano | ML Kit GenAI Speech Recognition & Prompt API, runtime `BUSY`/quota demotion | **Optional** (when hardware available) | Pixel 9 / 10 Pro or Galaxy S24 / S25 series with AICore enabled |

---

## 2. Section A — Scaffold & Seeded Surface Pass (Shipped Today)

Run these steps in order on a clean install of the debug build:

### A1. Install from a clean clone
- **Steps:**
  1. From a clean checkout on `main`, run `./gradlew :app:assembleDebug :app:testDebugUnitTest` (`gradlew.bat` on Windows).
  2. With the target emulator or device connected (`adb devices -l`), run `./gradlew :app:installDebug`.
  3. Clear any prior app state: `adb shell pm clear com.icanbenchurcat.voicenotes`.
- **Pass criteria:**
  - Build, unit tests, and APK installation succeed with exit code `0`.

### A2. Cold launch & empty state
- **Steps:**
  1. Launch `MainActivity`: `adb shell am start -W -n com.icanbenchurcat.voicenotes/.MainActivity` (or tap **Voice Note Sorter** in the launcher).
  2. Observe the initial screen before seeding.
- **Pass criteria:**
  - App launches without crash or ANR.
  - Empty-state copy `"No voice notes yet. Tap Record to capture a thought."` is displayed.
  - Debug toolbar buttons **Seed sample data** and **Clear data** are visible at the top (debug builds only; absent in release builds).
  - The floating action button (`●`) is visible in the bottom-right corner.

### A3. Seeded list & note detail (Issue #38)
- **Steps:**
  1. Tap **Seed sample data** in the debug toolbar.
  2. Verify the list populates with 5 sample notes sorted newest-first across all 5 default buckets:
     - `Offline contractor field-notes profile` (`IDEA · FULL`)
     - `Renovation follow-ups for today` (`TASK · FULL`)
     - `Weekend shopping list: espresso beans, oat milk` (`LIST · CORE`)
     - `Architecture meeting recap: keep the open-core` (`NOTE · CORE`)
     - `Voice note 2026-11-01 08:15` (`INBOX · MINIMAL`)
  3. Tap `Renovation follow-ups for today` (`TASK · FULL`) to open `NoteDetailScreen`.
  4. Verify the detail screen renders:
     - Title (`Renovation follow-ups for today`) and bucket label (`TASK`)
     - Tags (`Tags: #errands, #renovation`)
     - Full transcript text
     - Action items section showing both completed (`[x] Call the electrician before noon`) and pending (`[ ] Send the updated floor plan PDF to Dana`, `[ ] Pick up cabinet hinges at the hardware store`) items
     - Pipeline status (`Pipeline: transcribe:OK,title:OK,actions:OK,bucket:OK`)
  5. Navigate back to the list and tap `Voice note 2026-11-01 08:15` (`INBOX · MINIMAL`).
  6. Verify the Tier 1 empty-transcript fallback copy renders: `"No transcript yet. Playback is available; this note was filed by hand (Tier 1)."` along with `Pipeline: transcribe:UNAVAILABLE,title:FALLBACK,actions:SKIPPED,bucket:DEFAULT`.
  7. Tap **Clear data** and verify the list returns to the empty state; tap **Seed sample data** again and verify the 5 notes re-populate without reinstalling.
- **Pass criteria:**
  - All 5 buckets, all 3 tiers (`FULL`, `CORE`, `MINIMAL`), tags, and done/undone action items render accurately, and clear + re-seed works in-place.

### A4. Glance home-screen widget
- **Steps:**
  1. Return to the device home screen, long-press empty workspace -> **Widgets** -> search for **Voice Note Sorter**.
  2. Verify the `2 × 1` widget preview shows description `"One-tap voice note capture"` and place it on the home screen.
  3. Tap the placed widget (`●  Tap to record a note`).
- **Pass criteria:**
  - Widget registers under `RecordWidgetReceiver`, renders on the home screen, and tapping it launches `MainActivity` with `extra_start_recording = true` (`ActivityTaskManager` result code `0`). *(Note: actually starting a recording session on launch is gated on Issues #4 and #6.)*

### A5. Quick Settings tile
- **Steps:**
  1. Add the Quick Settings tile via the shade editor or `adb shell cmd statusbar add-tile com.icanbenchurcat.voicenotes/.service.RecordTileService`.
  2. Expand Quick Settings (`adb shell cmd statusbar expand-settings`) and verify the **Record a note** tile appears.
  3. Tap the **Record a note** tile.
- **Pass criteria:**
  - Tile appears in Quick Settings with label/content description `Record a note` and launches `MainActivity` without crashing. *(See first-run report below for the API 34+ `TileService.startActivityAndCollapse(Intent)` `UnsupportedOperationException` uncovered during the first run.)*

### A6. Accessibility / TalkBack pass over shipped surfaces (Issue #27 shipped portion)
- **Steps:**
  1. Inspect accessibility node semantics via TalkBack or UI Automator dump (`android layout -p` / Layout Inspector) on the home widget, empty list, seeded list, and note detail screen.
- **Pass criteria:**
  - Record FAB exposes `contentDescription = "Record a note"` while the inner `●` glyph is marked decorative (`contentDescription = ""`).
  - Glance widget root exposes `contentDescription = "Record a note"`.
  - Note cards meet the `48dp` minimum height (`defaultMinSize(minHeight = 48.dp)`) and expose `"Filed as <bucket>, <tier>"` semantics on the metadata line.
  - Note detail screen exposes `"Filed as <bucket>"` and `"Pipeline status: <stageStatus>"` content descriptions.

---

## 3. Planned Sections (Stubs — Locked Behind Open Issues)

> The sections below **cannot be run today**. Expand each stub into pass/fail
> steps in the PR that lands its blocking issue.

### Section B — Audio Capture & Recording Session *(Blocked on Issues #4, #5, #6)*
- **Unlocked by:**
  - **#4**: One-tap in-app recording creates a note and enqueues the sorting pipeline
  - **#5**: Recording session: foreground service, Live Update, pause/stop, and duration limit
  - **#6**: Widget and Quick Settings tile actually start recording
  - **#27** (remaining portion): Live-region announcements for recording start, state changes, stop, and sorting completion

### Section C — Audio Playback from Note Detail *(Blocked on Issue #7)*
- **Unlocked by:**
  - **#7**: Play back the original audio from note detail (play/pause, scrub, duration, and graceful handling when audio file is missing)

### Section D — Full-Text Search UI *(Blocked on Issue #10)*
- **Unlocked by:**
  - **#10**: Full-text search UI over the existing `notes_fts` FTS4 index (and **#22** for v1.1 semantic embedding search seam)

---

## 4. Test Results Template

Copy this row format for each manual or emulator test session:

| Date | Build SHA | Device / AVD (API) | Section | Result | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `YYYY-MM-DD` | `<short-sha>` | `<AVD or model (API XX)>` | `A1–A6` | `PASS / FAIL` | `<observations, logcat excerpts, or issue links>` |

---

## 5. Recorded Runs

### Run 1 — 2026-11-01 (`c633c83`)

| Date | Build SHA | Device / AVD (API) | Section | Result | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 2026-11-01 | `c633c83` | `Pixel_10_Pro_XL` AVD (`google_apis_playstore_ps16k/x86_64`, API 37.0, `PAGE_SIZE = 16384`) | **A1. Install from clean clone** | **PASS** | `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:installDebug` succeeded cleanly on Windows 11 with JDK 17.0.19. |
| 2026-11-01 | `c633c83` | `Pixel_10_Pro_XL` AVD (API 37.0, 16 KB page size) | **A2. Cold launch & empty state** | **PASS** | Cold launch renders `"No voice notes yet. Tap Record to capture a thought."`, debug toolbar (`Seed sample data`, `Clear data`), and `Record a note` FAB. *(Note: prior to PR #43, cold launch crashed with `SerializationException` on `NotesListKey`; fixed by applying `kotlin-serialization` plugin.)* |
| 2026-11-01 | `c633c83` | `Pixel_10_Pro_XL` AVD (API 37.0, 16 KB page size) | **A3. Seeded list & note detail** | **PASS** | Verified via both manual UI inspection and `DebugSeedInstrumentedTest`: all 5 buckets (`IDEA`, `TASK`, `LIST`, `NOTE`, `INBOX`), all 3 tiers (`FULL`, `CORE`, `MINIMAL`), Tier 1 no-transcript fallback copy, tags (`#errands, #renovation`), and `[x]`/`[ ]` action items render; `Clear data` and re-seeding work without reinstall. Release build verified clean via `dexdump`. |
| 2026-11-01 | `c633c83` | `Pixel_10_Pro_XL` AVD (API 37.0, 16 KB page size) | **A4. Glance home-screen widget** | **PASS** | Placed `2 × 1` `Voice Note Sorter` widget (`RecordWidgetReceiver`) on NexusLauncher home screen (`[355,712]`). Tapping widget launched `MainActivity` (`START u0 {dat=glance-action:/... cmp=com.icanbenchurcat.voicenotes/.MainActivity (has extras)} result code=0`). |
| 2026-11-01 | `c633c83` | `Pixel_10_Pro_XL` AVD (API 37.0, 16 KB page size) | **A5. Quick Settings tile** | **FAIL** | Tile adds via `cmd statusbar add-tile` and renders in Quick Settings at `[195,795]` with `content-desc="Record a note"`. Tapping the tile crashes `com.icanbenchurcat.voicenotes` with `java.lang.UnsupportedOperationException: startActivityAndCollapse: Starting activity from TileService using an Intent is not allowed` at `RecordTileService.kt:20` because `targetSdk = 36` (>= 34) requires `startActivityAndCollapse(PendingIntent)` on API 34+. Tracked for fix with Issue #6. |
| 2026-11-01 | `c633c83` | `Pixel_10_Pro_XL` AVD (API 37.0, 16 KB page size) | **A6. Accessibility / TalkBack semantics** | **PASS** | UI Automator dump confirmed `content-desc="Record a note"` on FAB and QS tile, `48dp` minimum card height, `"Filed as <bucket>, <tier>"` on list cards, and `"Filed as <bucket>"` + `"Pipeline status: <status>"` on detail screen. |
| 2026-11-01 | `c633c83` | CI Emulators (`aosp_atd/x86_64`, **API 31** & **API 36**) | **A1–A3 (Automated Smoke & Seed)** | **PASS** | `MainActivitySmokeTest` and `DebugSeedInstrumentedTest` passed on both API 31 (`2m33s`) and API 36 (`2m44s`) in GitHub Actions run `41328507763`. |
