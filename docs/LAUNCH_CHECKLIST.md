# Play Launch Gate Tracker & Checklist

Living tracker for the Google Play Store launch gates, conforming to Spec Section 11 and Issue #29.

Last updated: 2026-11-04  
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
| **App Binaries & Bytecode** | **PASS** | Kotlin / Java pure bytecode (`no NDK / C++ custom native code`). Native page alignment is inherited from standard Android runtime. |
| **SQLite / Room Bundled Libs** | **PASS** | Room 2.7.0-alpha13 uses platform SQLite (`android.database.sqlite`) on API 31–37; no third-party native `.so` packaged in APK. |
| **Emulator Test Run (`ps16k`)** | **PASS** | Verified on `Pixel_10_Pro_XL` AVD (`google_apis_playstore_ps16k/x86_64`, API 37.0, 16 KB page size). Cold launch, database queries, and Glance widget rendered without page fault crashes. |
| **Binary Verification (`objdump`)** | **PASS** | APK contains zero unaligned 4 KB ELF segments in `lib/`. |

---

## 3. Data Safety & Privacy Declaration

Under the local-only guarantee (Constitution Article I):

| Play Console Field | Declaration | Evidence |
| :--- | :--- | :--- |
| **Data Collection** | **No data collected** | Verified by zero network permissions (`android.permission.INTERNET` absent from manifest). Zero third-party analytics/crash reporting SDKs. |
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
- [ ] **E2E Integration Gate**: Automated instrumented capture-to-filed loop test on device (Issue #40, pending PR #69 merge).
- [ ] **14-Day Closed Testing Completion**: Complete closed testing run with 12+ registered testers.
- [ ] **Production Track Promotion**: Submit production build release bundle (`.aab`).
