# Play Launch Gate Tracker & Checklist

Living tracker for the Google Play Store launch gates, conforming to Spec Section 11 and Issue #29.

Last updated: 2026-11-26  
Target Release: v1.0.0 (MVP)  
Package: `com.icanbenchurcat.voicenotes`  

---

## 1. Non-Compressible Google Play Gates

| Gate | Requirement | Status | Evidence / Target Dates |
| :--- | :--- | :--- | :--- |
| **Personal Account Closed Testing** | 12+ opted-in testers continuously opted-in for 14 consecutive days before production track application. | **Scheduled** | Target window: **Nov 10 – Nov 24, 2026**.<br>Tester cohort per Spec Decision 11: 4 flagship device owners (Pixel 8 Pro, Pixel 9 Pro for Tier 3 on-device AI) + 8 mid-range/baseline users (Tier 1/2). |
| **Developer Identity Verification** | Dun & Bradstreet D-U-N-S or government ID verification in Google Play Console. | **Verified** | Play Developer Console organization account verified. |
| **Target API Window** | `targetSdk = 36` (Android 16), compliant with Play policy extension window (Nov 1, 2026). Plan for API 37 follow-up. | **PASS** | `app/build.gradle.kts` specifies `compileSdk = 37`, `targetSdk = 36`, `minSdk = 31`. Verified on API 31 & API 36/37 local emulators. |

---

## 2. 16 KB Page-Size Compatibility Audit

Google Play requires all apps targeting Android 15+ devices to support 16 KB memory page sizes (`ps16k`).

| Native Component / Library | Status | Verification Method |
| :--- | :--- | :--- |
| **App Binaries & Bytecode** | **PASS** | Kotlin / Java bytecode; verified `:app:assembleRelease` (R8, `49.3 MB` APK) and `:app:bundleRelease` (`26.7 MB` AAB) on `abf7112`. |
| **Native `.so` Libraries** | **PASS** | Bundled 64-bit native libraries in `app-release.aab`: `libmlkit_google_ocr_pipeline.so` (`com.google.mlkit:text-recognition:16.0.1`), `libandroidx.graphics.path.so`, and `libdatastore_shared_counter.so`. |
| **Emulator Test Run (`ps16k`)** | **PASS** | Verified on `Pixel_10_Pro_XL` AVD (`google_apis_playstore_ps16k/x86_64`, API 37.0, 16 KB page size). Cold launch, database queries, and Glance widget rendered without page fault crashes. |
| **Binary Verification (ELF `PT_LOAD`)** | **PASS** | Audited all 64-bit `.so` files (`base/lib/arm64-v8a/*.so` and `base/lib/x86_64/*.so`) inside `app-release.aab` on `abf7112`: every `PT_LOAD` segment reports `p_align = 0x4000` (16 KB). |

---

## 3. Data Safety & Privacy Declaration

Under the local-only guarantee (Constitution Article I):

| Play Console Field | Declaration | Evidence |
| :--- | :--- | :--- |
| **Data Collection** | **BLOCKED (see `#172`)** | Source `app/src/main/AndroidManifest.xml` omits `INTERNET`, **but** the merged release manifest (`app-release.aab`) currently inherits `android.permission.INTERNET` transitively from `com.google.mlkit:genai-speech-recognition:1.0.0-alpha2` and `com.google.android.datatransport:transport-backend-cct:2.3.3`. Must strip via `tools:node="remove"` (Issue `#172`) before Play Store submission. Zero analytics/crash reporting SDKs. |
| **Data Sharing** | **No data shared** | Notes, transcripts, audio recordings, and tags never transmit off the host hardware. |
| **Data Deletion Mechanism** | **In-app delete + Settings purge** | Full atomic cascade delete (`NoteDeletionCoordinator`) purges DB rows and physical audio. Optional 90-day auto-purge for audio files. |
| **Backup Exclusions** | **Excluded from cloud backups** | Configured in `res/xml/data_extraction_rules.xml` and `res/xml/backup_rules.xml` with passing unit tests (`BackupRulesVerificationTest`). |

---

## 4. Pre-Launch Technical Checklist (Spec Section 11)

- [x] **Boundary Guard**: CI workflow blocks any imports from closed Pro modules or network SDKs.
- [x] **Licensing**: Apache-2.0 headers on all source files (`// SPDX-License-Identifier: Apache-2.0`).
- [x] **Adaptive Icons**: Material 3 adaptive launcher icon (`ic_launcher.xml` / `ic_launcher_round.xml`).
- [x] **Edge-to-Edge**: `enableEdgeToEdge()` enabled with proper window inset handling.
- [x] **Dark Mode & Dynamic Colour**: Full Material 3 Expressive theme with dynamic light/dark palettes.
- [x] **Accessibility Touch Targets**: All interactive targets across capture flow, note cards, and widget meet the 48dp minimum floor.
- [x] **Storage Visibility**: Settings displays accurate audio and database storage byte breakdown.
- [x] **Audio Retention Policy**: Opt-in 90-day audio auto-delete policy implemented with explicit user consent.
- [x] **E2E Integration Gate**: Automated instrumented capture-to-filed loop test on device (`CaptureToFiledLoopE2ETest`, verified on API 31, 36, and 37).
- [ ] **14-Day Closed Testing Completion**: Complete closed testing run with 12+ registered testers.
- [ ] **Production Track Promotion**: Submit production build release bundle (`.aab`).
