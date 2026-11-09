<!-- SPDX-License-Identifier: Apache-2.0 -->

# Android API 37 (Android 17) Verification Protocol & Architecture Findings

## Overview & Context
The project builds against `compileSdk = 37` with `targetSdk = 36` and `minSdk = 31`.
Android 17 introduces minor-versioned platform packages (`platforms;android-37.0`) and requires strict 16 KB memory page size (`ps16k`) architectural compliance for native libraries and emulators.

## Live Verification on API 37 System Image / VM
Full verification was executed directly on an Android 17 / API 37 virtual device:

- **Target / System Image**: `system-images/android-37.0/google_apis_playstore_ps16k/x86_64` (16 KB Page Size Google Play Intel x86_64 Atom System Image)
- **Device Profile**: Pixel 10 Pro XL (`hw.lcd.width=1344`, `hw.lcd.height=2992`, `hw.ramSize=2048`, `hw.cpu.ncore=4`)
- **Properties**:
  - `ro.build.version.sdk`: `37`
  - `ro.build.version.release`: `17`
- **Result**:
  - `MainActivitySmokeTest`: **PASSED**
  - Full suite (`./gradlew :app:connectedDebugAndroidTest`): **26/26 tests passed (100% success rate)**
  - No 16 KB page-size crashes or regressions on the Android 17 runtime.

## CI Runner Matrix Analysis (`reactivecircus/android-emulator-runner`)
On GitHub Actions standard Linux hosted runners (`ubuntu-latest` / Azure Standard_D2s_v3):
1. **Runner Constraints**:
   - Azure standard hosted runners provide 2 vCPUs without nested hardware GPU acceleration (`swiftshader_indirect`).
   - Starting heavy Google APIs / Play Store 16 KB system images (`google_apis_ps16k` or `google_apis_playstore_ps16k`) or early preview images under software translation on 2 virtual cores causes boot times to exceed standard runner limits (`sys.boot_completed` timeout after 600s/900s).
2. **Local & Dedicated Runner Protocol**:
   - For pre-release platform validation and 16 KB page-size verification, run the test suite locally or on a KVM-accelerated host with >= 4 cores:
     ```bash
     # Launch AVD
     emulator -avd Pixel_10_Pro_XL -no-snapshot -no-boot-anim -no-audio
     # Execute test suite
     ./gradlew :app:connectedDebugAndroidTest
     ```
3. **CI Matrix Strategy for Issue #82**:
   - The CI matrix can pin API 31 and API 36 on standard hosted runners (`aosp_atd`), while API 37 validation can be triggered on dedicated/larger hardware runners or verified prior to major platform promotions.
