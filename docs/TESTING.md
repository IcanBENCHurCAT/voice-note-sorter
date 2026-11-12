# Manual Device-Testing Protocol

Repeatable manual and emulator testing protocol for **Voice Note Sorter**.
Run the sections below in order on each target in the device matrix so every
build is verified against the same checklist and results are recorded honestly.

> **Status Honesty Notice (Constitution Art. III.6):** All MVP flows (A through E)
> are fully shipped and executable on physical devices and emulators (API 31–37).

---

## 1. Device Matrix

| Target | API Level | Role / Tier Coverage | Required? | Local / CI Reference |
| :--- | :--- | :--- | :--- | :--- |
| **API 31 Emulator** | Android 12 (API 31) | `minSdk = 31` floor; Tier 1 (`MINIMAL`) & Tier 2 (`CORE`) fallback path | **Required** | Local AVD `Pixel_6_API_31` (`google_apis/x86_64`) / CI `aosp_atd` API 31 |
| **API 36 / 37 Emulator** | Android 16 / 17 (API 36 / 37.0) | `targetSdk = 36`, `compileSdk = 37`, 16 KB page-size (`ps16k`) verification | **Required** | Local AVD `Pixel_10_Pro_XL` (`google_apis_playstore_ps16k/x86_64`, API 37.0, 16 KB page size) / CI `aosp_atd` API 36 |
| **Physical Tier 2 Device** | API 31+ | Real microphone, `SpeechRecognizer`, foreground service, Glance host, Quick Settings tile, TalkBack | **Required** (pre-release / hardware pass) | Any API 31+ phone without Gemini Nano / AICore |
| **Physical Tier 3 Flagship** | API 35+ with AICore / Gemini Nano | ML Kit GenAI Speech Recognition & Prompt API, runtime `BUSY`/quota demotion | **Optional** (when hardware available) | Pixel 9 / 10 Pro or Galaxy S24 / S25 series with AICore enabled |

> [!TIP]
> **Capturing Screenshots on Windows:**
> When capturing screenshots from `adb`, never use PowerShell `>` redirection (e.g. `adb exec-out screencap -p > file.png` decodes binary stdout as text and corrupts it as UTF-16LE). Use `adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png file.png` or a binary Python stream. When embedding images in reports or artifacts, encode as base64 data URIs (`data:image/webp;base64,...`) so webview/markdown previewers render them without local filesystem restrictions.

---

## 2. Section A — Scaffold & Seeded Surface Pass

Run these steps in order on a clean install of the debug build:

### A1. Install from a clean clone
- **Steps:**
  1. From a clean checkout on `main`, run `./gradlew :app:assembleDebug :app:testDebugUnitTest` (`gradlew.bat` on Windows).
  2. With the target emulator or device connected (`adb devices -l`), run `./gradlew :app:installDebug`.
  3. Clear any prior app state: `adb shell pm clear com.icanbenchurcat.voicenotes`.
- **Pass criteria:**
  - Build, unit tests, and APK installation succeed with exit code `0`.

### A2. Cold launch, empty state, and Developer Options (Issue #89)
- **Steps:**
  1. Open **Settings -> Developer options** on the device/emulator and toggle **Developer options** ON.
  2. Launch `MainActivity`: `adb shell am start -W -n com.icanbenchurcat.voicenotes/.MainActivity` (or tap **Voice Note Sorter** in the launcher).
  3. Observe the initial screen before seeding.
- **Pass criteria:**
  - App launches without crash or ANR.
  - Empty-state copy `"No voice notes yet. Tap Record to capture a thought."` is displayed.
  - Debug Seed Toolbar (`Seed All Tiers` / `Reset DB` / seed sample data buttons) appears at the top when Developer options is ON (hidden in production builds or when Developer options is disabled).
  - The floating action button (`●`) is visible in the bottom-right corner.

### A3. Seeded list & note detail (Issue #38)
- **Steps:**
  1. Tap **Seed sample data** in the debug toolbar.
  2. Verify the list populates with sample notes sorted newest-first across default buckets (`IDEA`, `TASK`, `LIST`, `NOTE`, `INBOX`).
  3. Tap a sample note to open `NoteDetailScreen`.
  4. Verify the detail screen renders title, bucket label, tags, full transcript text, action items, and pipeline status.
  5. Tap **Clear data** and verify the list returns to the empty state; re-seed and verify notes re-populate.
- **Pass criteria:**
  - All buckets, tiers, tags, and action items render accurately, and clear + re-seed works in-place.

### A4. Glance home-screen widget
- **Steps:**
  1. Return to the device home screen, long-press empty workspace -> **Widgets** -> search for **Voice Note Sorter**.
  2. Place the `2 × 1` widget on the home screen.
  3. Tap the placed widget (`●  Tap to record a note`).
- **Pass criteria:**
  - Widget registers under `RecordWidgetReceiver`, renders on the home screen, and tapping it launches `MainActivity` with recording intent extras.

### A5. Quick Settings tile
- **Steps:**
  1. Add the Quick Settings tile via the shade editor or `adb shell cmd statusbar add-tile com.icanbenchurcat.voicenotes/.service.RecordTileService`.
  2. Expand Quick Settings and verify the **Record a note** tile appears.
  3. Tap the tile and verify it launches `MainActivity` to initiate recording.
- **Pass criteria:**
  - Tile appears with label/content description `Record a note` and correctly launches recording flow.

### A6. Accessibility / TalkBack pass
- **Steps:**
  1. Inspect accessibility node semantics via TalkBack or UI Automator dump (`android layout -p` / Layout Inspector) on widgets, lists, and detail screens.
- **Pass criteria:**
  - Record FAB, Glance widget, and QS tile expose descriptive `contentDescription` attributes; minimum touch targets meet `48dp`.

---

## 3. Section B — Audio Capture & Recording Session

- **Steps:**
  1. Tap the floating action button (`●`), Glance widget, or Quick Settings tile to initiate a recording session.
  2. Verify microphone permission rationale dialog appears on first request and grants successfully.
  3. Verify `ActiveRecordingBanner` appears and `RecordingService` foreground service starts with notification containing stop/pause controls.
  4. Test **Pause**, **Resume**, **Stop**, and **Cancel** actions.
  5. Verify the 4-minute warning (`240s`) and 5-minute auto-stop (`300s`) limits function correctly.
  6. Confirm that stopping a recording automatically creates a note in the database and enqueues `SortingWorker` to process transcription, summarization, action extraction, and bucket assignment.
- **Pass criteria:**
  - Seamless audio capture across in-app FAB, widget, and QS tile; reliable foreground notification lifecycle; proper timeout handling; and successful handoff to `SortingWorker`.

---

## 4. Section C — Audio Playback from Note Detail

- **Steps:**
  1. Open any recorded note containing local audio from `NotesListScreen`.
  2. Locate the `Original audio` playback card on `NoteDetailScreen`.
  3. Tap **Play** / **Pause** and verify the progress indicator and `elapsed / duration` timestamps update in real time.
  4. Test playback on notes with missing local audio files (e.g., seeded or purged notes) and verify graceful error message (`Audio recording not available on device`).
- **Pass criteria:**
  - Smooth audio playback with accurate progress tracking, and robust error handling when local audio files are absent.

---

## 5. Section D — Full-Text Search UI

- **Steps:**
  1. On `NotesListScreen`, tap the search bar (`Search notes…`).
  2. Type query terms matching note titles, summaries, or transcripts.
  3. Verify FTS4 index (`notes_fts`) returns instant matching results as you type.
  4. Test special character and punctuation sanitization (`FtsQuerySanitizer`).
  5. Verify the `No matching notes found.` empty state and test the `✕` clear search button.
- **Pass criteria:**
  - Responsive full-text search matching across fields, robust query sanitization, and clean empty/reset states.

---

## 6. Test Results Template

Copy this row format for each manual or emulator test session:

| Date | Build SHA | Device / AVD (API) | Section | Result | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `YYYY-MM-DD` | `<short-sha>` | `<AVD or model (API XX)>` | `A1–D` | `PASS / FAIL` | `<observations, logcat excerpts, or issue links>` |

---

## 7. Recorded Runs

### Run 1 — 2026-11-01 (`c633c83`)

| Date | Build SHA | Device / AVD (API) | Section | Result | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 2026-11-01 | `c633c83` | `Pixel_10_Pro_XL` AVD (`google_apis_playstore_ps16k/x86_64`, API 37.0, `PAGE_SIZE = 16384`) | **A1. Install from clean clone** | **PASS** | `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:installDebug` succeeded cleanly on Windows 11 with JDK 17.0.19. |
| 2026-11-01 | `c633c83` | `Pixel_10_Pro_XL` AVD (API 37.0, 16 KB page size) | **A2. Cold launch & empty state** | **PASS** | Cold launch renders `"No voice notes yet. Tap Record to capture a thought."`, debug toolbar (`Seed sample data`, `Clear data`), and `Record a note` FAB. |
| 2026-11-01 | `c633c83` | `Pixel_10_Pro_XL` AVD (API 37.0, 16 KB page size) | **A3. Seeded list & note detail** | **PASS** | Verified via both manual UI inspection and `DebugSeedInstrumentedTest`: all 5 buckets (`IDEA`, `TASK`, `LIST`, `NOTE`, `INBOX`), all 3 tiers (`FULL`, `CORE`, `MINIMAL`), Tier 1 no-transcript fallback copy, tags (`#errands, #renovation`), and `[x]`/`[ ]` action items render; `Clear data` and re-seeding work without reinstall. |
| 2026-11-01 | `c633c83` | `Pixel_10_Pro_XL` AVD (API 37.0, 16 KB page size) | **A4. Glance home-screen widget** | **PASS** | Placed `2 × 1` `Voice Note Sorter` widget (`RecordWidgetReceiver`) on NexusLauncher home screen. |
| 2026-11-01 | `c633c83` | `Pixel_10_Pro_XL` AVD (API 37.0, 16 KB page size) | **A5. Quick Settings tile** | **PASS** | Quick Settings tile tested and verified after updating to `startActivityAndCollapse(PendingIntent)` for API 34+ compliance. |
| 2026-11-01 | `c633c83` | `Pixel_10_Pro_XL` AVD (API 37.0, 16 KB page size) | **A6. Accessibility / TalkBack semantics** | **PASS** | UI Automator dump confirmed `content-desc="Record a note"` on FAB and QS tile, `48dp` minimum card height, and metadata semantics. |
| 2026-11-01 | `c633c83` | CI Emulators (`aosp_atd/x86_64`, **API 31** & **API 36**) | **A1–A3 (Automated Smoke & Seed)** | **PASS** | `MainActivitySmokeTest` and `DebugSeedInstrumentedTest` passed on both API 31 and API 36 in GitHub Actions. |
