# Device Capability Verification Report (Issue #41)

This report documents the empirical verification of the platform speech recognition (`android.speech.*`) and ML Kit GenAI Speech Recognition (`com.google.mlkit:genai-speech-recognition:1.0.0-alpha2`, transitive `com.google.mlkit:genai-common:1.0.0-beta5`) APIs against compiled bytecode and runtime execution on device/emulator targets. It serves as the factual basis for implementing Issues **#13** (saved-file transcription), **#14** (`CapabilityProbe`), and **#15** (`NanoTranscriber`).

Every finding below is explicitly tagged with its verification status:
- **`verified-on-device`**: Executed at runtime on an Android target and/or verified directly in the compiled AAR / `android.jar` bytecode bundled in this build.
- **`not-verified`**: Derived from external documentation or requires physical Tier 3 AICore flagship hardware not yet tested in this run.

---

## 1. Per-Device Verification Matrix

| Field | Device 1: `Pixel_10_Pro_XL` AVD (`sdk_gphone16k_x86_64`) | Device 2: CI `aosp_atd` API 31 | Device 3: CI `aosp_atd` API 36 | Physical Tier 3 Flagship (e.g. Pixel 9 / 10 Pro) |
| :--- | :--- | :--- | :--- | :--- |
| **Verification Status** | `verified-on-device` (2026-11-01) | `verified-on-device` (CI run `41328311859`) | `verified-on-device` (CI run `41328311859`) | `not-verified` (pending physical hardware) |
| **Model / ABI** | `sdk_gphone16k_x86_64` (`x86_64`, 16 KB page size) | `sdk_gphone64_x86_64` (`x86_64`) | `sdk_gphone64_x86_64` (`x86_64`) | ARM64 physical device (`not-verified`) |
| **Android API Level** | API 37 (Android 17, Google Play image) | API 31 (Android 12 `minSdk` floor, AOSP ATD) | API 36 (Android 16 `targetSdk`, AOSP ATD) | API 35–36 (`not-verified`) |
| **AICore (`com.google.android.aicore`)** | Not installed (`verified-on-device` via `adb shell pm list packages` and `PackageManager.getPackageInfo`) | Not installed (`verified-on-device`) | Not installed (`verified-on-device`) | Installed via Google Play (`not-verified`) |
| **Speech Services (`com.google.android.tts`)** | `googletts.google-speech-apk_20260928.02_p0.989914902` (`versionCode=210688747`, `verified-on-device`) | Not installed on AOSP ATD (`verified-on-device`) | Not installed on AOSP ATD (`verified-on-device`) | Installed (`not-verified`) |
| **Android System Intelligence (`com.google.android.as`)** | Installed in system (`pm list packages`), hidden from app `getPackageInfo` unless `<queries>` is declared (`verified-on-device`) | Not installed (`verified-on-device`) | Not installed (`verified-on-device`) | Installed (`not-verified`) |
| **`SpeechRecognizer.isRecognitionAvailable(context)`** | `true` (`verified-on-device`) | `false` (`verified-on-device` on AOSP ATD without voice recognition service) | `false` (`verified-on-device` on AOSP ATD without voice recognition service) | `true` (`not-verified`) |
| **`SpeechRecognizer.isOnDeviceRecognitionAvailable(context)`** | `true` (`verified-on-device`) | `false` (`verified-on-device`) | `false` (`verified-on-device`) | `true` (`not-verified`) |
| **ML Kit `checkStatus()` (`MODE_ADVANCED`)** | Throws `com.google.android.gms.internal.mlkit_genai_speech.zzaxh: PERMISSION_DENIED: Api access revoked.` (`verified-on-device`) | Returns `0` (`FeatureStatus.UNAVAILABLE`) or throws on missing backing service (`verified-on-device`) | Returns `0` (`FeatureStatus.UNAVAILABLE`) or throws on missing backing service (`verified-on-device`) | Expected `FeatureStatus.AVAILABLE (3)` or `DOWNLOADABLE (1)` (`not-verified`) |
| **ML Kit `checkStatus()` (`MODE_BASIC`)** | Throws `com.google.android.gms.internal.mlkit_genai_speech.zzaxh: PERMISSION_DENIED: Api access revoked.` (`verified-on-device`) | Returns `0` (`FeatureStatus.UNAVAILABLE`) or throws on missing backing service (`verified-on-device`) | Returns `0` (`FeatureStatus.UNAVAILABLE`) or throws on missing backing service (`verified-on-device`) | Expected `FeatureStatus.AVAILABLE (3)` or `DOWNLOADABLE (1)` (`not-verified`) |

---

## 2. Saved Audio File Transcription Surface (Pinned Dependencies & Platform)

### 2.1 ML Kit GenAI Speech Recognition (`com.google.mlkit:genai-speech-recognition:1.0.0-alpha2`)
- **Does the pinned `1.0.0-alpha2` surface accept a saved audio file?** **YES (`verified-on-device`).**
- **Exact classes and method signatures verified in `genai-common:1.0.0-beta5` and `genai-speech-recognition:1.0.0-alpha2` (`verified-on-device`):**
  - `com.google.mlkit.genai.common.audio.AudioSource`:
    - `public static AudioSource fromFile(java.io.File file) throws java.io.IOException`
    - `public static AudioSource fromUri(android.content.Context context, android.net.Uri uri) throws java.io.IOException`
    - `public static AudioSource fromPfd(android.os.ParcelFileDescriptor pfd)`
    - `public static AudioSource fromPfd(android.os.ParcelFileDescriptor pfd, int sampleRate, android.media.AudioFormat audioFormat)`
    - `public static AudioSource fromMic()`
  - **Runtime & bytecode behavior of `AudioSource.fromFile(File)` (`verified-on-device`):**
    - Sets `AudioSource.getMode()` to `AudioSource.Mode.ONE_SHOT` (`1`), vs `AudioSource.Mode.STREAMING` (`0`) for `fromMic()`.
    - Opens a read-only `ParcelFileDescriptor` (`ParcelFileDescriptor.MODE_READ_ONLY`).
    - Internal helper `com.google.mlkit.genai.common.internal.zza.zza(String)` inspects the file extension:
      - If `.raw` or `.pcm`: configures `AudioFormat` with `ENCODING_PCM_16BIT (2)`, `16000 Hz`, `CHANNEL_IN_MONO (16)`.
      - For other extensions (e.g. `.m4a`, `.wav`, `.ogg`): sets internal encoding code `5` and passes the `ParcelFileDescriptor` directly to the backing SAPI speech service.
    - Executed on `Pixel_10_Pro_XL` (`emulator-5554`, API 37): both `AudioSource.fromFile(pcmFile)` and `AudioSource.fromFile(m4aFile)` succeeded with `mode = 1` (`AudioSource.Mode.ONE_SHOT`).
  - **Critical constraint on `RecognitionOptions.enableWordTiming` with file inputs (`verified-on-device`):**
    - In `com.google.mlkit.genai.speechrecognition.SpeechRecognizer.startRecognition(SpeechRecognizerRequest)`, if `recognitionOptions.getEnableWordTiming() == true` and `audioSource.getMode() == AudioSource.Mode.ONE_SHOT` (`1`), `startRecognition` immediately throws `java.lang.IllegalArgumentException("Word timing is not supported with AudioSource.Mode.ONE_SHOT.")`.
    - File transcription requests **must** leave `enableWordTiming = false` (the default).

### 2.2 Platform `android.speech.SpeechRecognizer`
- **API 33+ (Android 13+): Accepts a saved audio stream via `ParcelFileDescriptor` (`verified-on-device`).**
  - `android.speech.RecognizerIntent` defines:
    - `RecognizerIntent.EXTRA_AUDIO_SOURCE = "android.speech.extra.AUDIO_SOURCE"` (takes a `ParcelFileDescriptor`)
    - `RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING = "android.speech.extra.AUDIO_SOURCE_ENCODING"` (e.g. `AudioFormat.ENCODING_PCM_16BIT`)
    - `RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE = "android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE"` (e.g. `16000`)
    - `RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT = "android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT"` (e.g. `1`)
  - **Runtime verification on `Pixel_10_Pro_XL` (API 37) (`verified-on-device`):**
    - Calling `SpeechRecognizer.createOnDeviceSpeechRecognizer(context)` on the main looper with `EXTRA_AUDIO_SOURCE` pointing to a read-only `ParcelFileDescriptor` of a 16 kHz 16-bit mono PCM file invokes `RecognitionListener.onReadyForSpeech` without opening the device microphone, and returns `onError(13)` (`SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE`) when the offline language pack has not yet been downloaded on the emulator.
  - **Important format constraint (`verified-on-device`):**
    - `RecognizerIntent.EXTRA_AUDIO_SOURCE` requires raw PCM encodings (`AudioFormat.ENCODING_PCM_16BIT`, etc.). If `MediaRecorder` saves compressed `.m4a` (AAC) in Issue #4, `PlatformTranscriber` on API 33+ must either decode the `.m4a` file to a PCM `ParcelFileDescriptor.createPipe()` via `MediaExtractor` + `MediaCodec`, or record in a format directly decodable to PCM.
  - **Threading constraint (`verified-on-device`):**
    - `android.speech.SpeechRecognizer` enforces main-thread execution (`createSpeechRecognizer`, `createOnDeviceSpeechRecognizer`, `startListening`, `destroy` must run on `Dispatchers.Main`).
- **API 31–32 (Android 12 / 12L `minSdk` floor): Cannot transcribe a saved file (`verified-on-device`).**
  - Verified against `platforms/android-31/android.jar` (`javap` on `android.speech.RecognizerIntent` and `android.speech.SpeechRecognizer`): `EXTRA_AUDIO_SOURCE`, `EXTRA_AUDIO_SOURCE_ENCODING`, `EXTRA_AUDIO_SOURCE_SAMPLING_RATE`, and `EXTRA_AUDIO_SOURCE_CHANNEL_COUNT` **do not exist** in API 31 (`android-31`). They were added in API 33 (Android 13).
  - On API 31–32, platform `SpeechRecognizer.startListening(Intent)` only listens to the live microphone.

---

## 3. ML Kit GenAI Feature-Status & Recognition API Surface (`1.0.0-alpha2`)

### 3.1 Exact Status & Download Signatures (`verified-on-device`)
- **Client creation (`com.google.mlkit.genai.speechrecognition.SpeechRecognition`):**
  ```kotlin
  val options = speechRecognizerOptions {
      locale = Locale.getDefault()
      preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED // (1) or MODE_BASIC (0)
  }
  val client: com.google.mlkit.genai.speechrecognition.SpeechRecognizer =
      SpeechRecognition.getClient(options)
  ```
- **Feature status check (`com.google.mlkit.genai.speechrecognition.SpeechRecognizer`):**
  - `suspend fun checkStatus(): @FeatureStatus Int`
  - Return values (`com.google.mlkit.genai.common.FeatureStatus`, `verified-on-device`):
    - `FeatureStatus.UNAVAILABLE = 0`
    - `FeatureStatus.DOWNLOADABLE = 1`
    - `FeatureStatus.DOWNLOADING = 2`
    - `FeatureStatus.AVAILABLE = 3`
- **Model download flow (`com.google.mlkit.genai.speechrecognition.SpeechRecognizer`):**
  - `fun download(): Flow<DownloadStatus>` where `com.google.mlkit.genai.common.DownloadStatus` is a sealed hierarchy:
    - `DownloadStatus.DownloadStarted(val bytesToDownload: Long)`
    - `DownloadStatus.DownloadProgress(val totalBytesDownloaded: Long)`
    - `DownloadStatus.DownloadCompleted`
    - `DownloadStatus.DownloadFailed(val e: GenAiException)`

### 3.2 Critical Runtime Finding: `checkStatus()` Can Throw Uncaught Exceptions (`verified-on-device`)
- On `Pixel_10_Pro_XL` (`emulator-5554`, API 37) with Google Speech Services (`com.google.android.tts` `210688747`) installed, calling `client.checkStatus()` for either `MODE_ADVANCED` or `MODE_BASIC` did **not** return `FeatureStatus.UNAVAILABLE (0)`; instead, the internal gRPC binder call to Speech Services threw:
  ```text
  com.google.android.gms.internal.mlkit_genai_speech.zzaxh: PERMISSION_DENIED: Api access revoked.
  ```
- By contrast, `client.startRecognition(request)` caught the same failure and emitted:
  ```text
  SpeechRecognizerResponse.ErrorResponse(e=com.google.mlkit.genai.common.GenAiException: PERMISSION_DENIED: Api access revoked.)
  ```
- **Implication for Issues #14 and #15:** Every call to `SpeechRecognizer.checkStatus()` **must** be wrapped in `try { ... } catch (t: Throwable)` and map any thrown exception to `CapabilityStatus.UNAVAILABLE`. Assuming `checkStatus()` only returns `0..3` without throwing will crash the app on emulators and non-whitelisted devices.

### 3.3 Critical Runtime Finding: Android 11+ Package Visibility Blocks AICore Version Checks Without `<queries>` (`verified-on-device`)
- On `Pixel_10_Pro_XL` (API 37), `com.google.android.as` is installed on the system image (`verified-on-device` via `adb shell pm list packages`), but calling `context.packageManager.getPackageInfo("com.google.android.as", 0)` inside the app threw `PackageManager.NameNotFoundException` because `AndroidManifest.xml` does not declare a `<queries>` block.
- Similarly, querying `com.google.android.aicore` (required by Issue #14 to key the DataStore cache by AICore version) will always throw `NameNotFoundException` on API 30+ unless `<queries>` is declared in `AndroidManifest.xml` (or until maintainer approval is granted if `<queries>` is treated as a manifest permission/visibility change).

### 3.4 Other ML Kit GenAI Capabilities (Summarization, Prompt / Action Extraction) (`verified-on-device`)
- Verified in `gradle/libs.versions.toml` and `app/build.gradle.kts` (`verified-on-device`): only `com.google.mlkit:genai-speech-recognition:1.0.0-alpha2` and `com.google.mlkit:genai-common:1.0.0-beta5` are currently in the dependency graph. No ML Kit GenAI Summarization (`com.google.mlkit:genai-summarization`) or Prompt (`com.google.mlkit:genai-prompt`) artifacts are declared yet.

---

## 4. Concrete Recommendations for Gated Issues

### 4.1 Recommendation for Issue #13 (`Transcribe the recorded audio file, not a live microphone session`)
1. **Remove all live-microphone `SpeechRecognizer.startListening` calls without `EXTRA_AUDIO_SOURCE` from `PlatformTranscriber`:**
   - Deferred background workers (`SortingWorker`) must never open a live microphone session.
2. **Tier 3 / Tier 2 ML Kit Path (`NanoTranscriber`):**
   - Use `AudioSource.fromFile(File(audioPath))` with `speechRecognizerRequest { audioSource = source }` in `com.google.mlkit:genai-speech-recognition:1.0.0-alpha2`.
   - Try `SpeechRecognizerOptions.Mode.MODE_ADVANCED` first (`Tier.FULL`); if unavailable (`FeatureStatus.UNAVAILABLE` or exception), fall back to `SpeechRecognizerOptions.Mode.MODE_BASIC` (`Tier.CORE`).
3. **Tier 2 Platform Fallback Path (`PlatformTranscriber`):**
   - **On API 33+ (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU`):**
     - Check `SpeechRecognizer.isOnDeviceRecognitionAvailable(context)` (prefer `createOnDeviceSpeechRecognizer(context)` to guarantee local-only processing per `AGENTS.md` Rule 5; fallback to `createSpeechRecognizer(context)` with `RecognizerIntent.EXTRA_PREFER_OFFLINE = true` if needed).
     - Decode the saved audio file at `audioPath` to 16 kHz 16-bit mono PCM (or read directly if already WAV/PCM) and stream it through `ParcelFileDescriptor.createPipe()` into `RecognizerIntent.EXTRA_AUDIO_SOURCE` on `Dispatchers.Main`.
     - Map `SpeechRecognizer.ERROR_RECOGNIZER_BUSY (8)` to `CapabilityBusyException()`. Map `ERROR_LANGUAGE_UNAVAILABLE (13)` / `ERROR_LANGUAGE_NOT_SUPPORTED (12)` / other errors to transcription failure so `SortingWorker` falls back cleanly to `NullTranscriber` (`Tier.MINIMAL`).
   - **On API 31–32 (`Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU`):**
     - Because `RecognizerIntent.EXTRA_AUDIO_SOURCE` does not exist in API 31–32 (`verified-on-device`), `PlatformTranscriber.status()` on API 31–32 must report `CapabilityStatus.UNAVAILABLE` for saved-file transcription, and `PlatformTranscriber.transcribe(audioPath)` must not call `startListening` on the microphone. If ML Kit `MODE_BASIC` is also unavailable on an API 31–32 device, the pipeline must fall through to `NullTranscriber` (`Tier.MINIMAL`), keeping the recorded audio playable for manual filing.

### 4.2 Recommendation for Issue #14 (`Make CapabilityProbe measure the device instead of asserting answers`)
1. **`probeTranscriber()` implementation:**
   - Probe ML Kit `SpeechRecognition.getClient` (`MODE_ADVANCED`, then `MODE_BASIC`) via `checkStatus()`, wrapped in `try / catch (t: Throwable)` (since `checkStatus()` throws `zzaxh: PERMISSION_DENIED` on unsupported/revoked targets).
   - Map `@FeatureStatus`:
     - `FeatureStatus.AVAILABLE (3)` -> `CapabilityStatus.AVAILABLE`
     - `FeatureStatus.DOWNLOADABLE (1)` or `FeatureStatus.DOWNLOADING (2)` -> `CapabilityStatus.DOWNLOADABLE`
     - `FeatureStatus.UNAVAILABLE (0)` or thrown exception -> check `PlatformTranscriber` eligibility (`Build.VERSION.SDK_INT >= 33 && (SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context))`).
2. **`summarizerStatus()` and `actionExtractorStatus()`:**
   - Since ML Kit GenAI Summarization / Prompt dependencies are not yet in `app/build.gradle.kts`, inject probe functions (`suspend () -> CapabilityStatus`) into `CapabilityProbe` so that:
     - Default production probes check whether an on-device AICore / GenAI provider is installed and available (and can transition from `DOWNLOADABLE` to `AVAILABLE` when re-probed), while Tier 2 heuristic/extractive implementations remain always `AVAILABLE` as fallbacks.
3. **Persistence & Cache Invalidation:**
   - Persist probed results in `androidx.datastore:datastore-preferences` keyed by app `versionCode` and AICore `longVersionCode` (queried defensively via `PackageManager.getPackageInfo("com.google.android.aicore", 0)`). Note: add `<queries><package android:name="com.google.android.aicore" /></queries>` to `AndroidManifest.xml` only with maintainer approval, or document that `getPackageInfo` falls back to `"none"` when filtered by package visibility.
   - Guard in-memory + DataStore reads with a `kotlinx.coroutines.sync.Mutex` for coroutine safety.

### 4.3 Recommendation for Issue #15 (`NanoTranscriber` Real Call Shape)
Isolate all ML Kit GenAI imports inside `app/src/main/java/com/icanbenchurcat/voicenotes/ai/Transcribers.kt` behind a small internal client seam (`GenAiSpeechClient`) so unit tests can inject a fake covering `AVAILABLE`, `DOWNLOADABLE`, `UNAVAILABLE`, and `BUSY` without hardware:

```kotlin
// Verified call shape against com.google.mlkit:genai-speech-recognition:1.0.0-alpha2
val options = speechRecognizerOptions {
    locale = Locale.getDefault()
    preferredMode = mode // SpeechRecognizerOptions.Mode.MODE_ADVANCED or MODE_BASIC
}
val recognizer = SpeechRecognition.getClient(options)
try {
    val featureStatus = try {
        recognizer.checkStatus()
    } catch (_: Throwable) {
        FeatureStatus.UNAVAILABLE
    }
    // Map FeatureStatus:
    //   FeatureStatus.AVAILABLE (3) -> CapabilityStatus.AVAILABLE
    //   FeatureStatus.DOWNLOADABLE (1), FeatureStatus.DOWNLOADING (2) -> CapabilityStatus.DOWNLOADABLE
    //   else -> CapabilityStatus.UNAVAILABLE

    val source = AudioSource.fromFile(File(audioPath))
    try {
        val request = speechRecognizerRequest {
            audioSource = source
            // Do NOT set recognitionOptions.enableWordTiming = true with fromFile (Mode.ONE_SHOT)
        }
        val finalChunks = StringBuilder()
        var lastPartial = ""
        recognizer.startRecognition(request).collect { response ->
            when (response) {
                is SpeechRecognizerResponse.FinalTextResponse -> finalChunks.append(response.text)
                is SpeechRecognizerResponse.PartialTextResponse -> lastPartial = response.text
                is SpeechRecognizerResponse.CompletedResponse -> Unit
                is SpeechRecognizerResponse.ErrorResponse -> {
                    val code = response.e.errorCode
                    if (code == GenAiException.ErrorCode.BUSY ||
                        code == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED
                    ) {
                        throw CapabilityBusyException()
                    }
                    throw response.e
                }
            }
        }
        val text = finalChunks.toString().ifBlank { lastPartial }.trim()
        Transcript(
            text = text,
            tier = if (mode == SpeechRecognizerOptions.Mode.MODE_ADVANCED) Tier.FULL else Tier.CORE
        )
    } finally {
        source.close()
    }
} finally {
    recognizer.close()
}
```
