# ADR 0004: Capture Architecture

Status: Accepted, 2026-11-05

## Context

We need a robust mechanism for capturing audio notes from multiple entry points (FAB, Quick Settings tile, app widgets) and handing them off to the processing pipeline (transcription, summarisation, bucketing). The capture layer needs to run reliably across modern Android versions, respecting background execution limits and microphone privacy constraints.

## Decision

The capture stack is built around a foreground `RecordingService`:
- **Entry Points:** A shared `StartCaptureUseCase` standardises entry from the FAB, a Quick Settings tile, and widgets. It launches the foreground service.
- **Service:** `RecordingService` manages a `MediaRecorder` instance (MIC audio source, AAC/MPEG-4 encoding). It requires a foreground service type of `microphone` to guarantee execution and display a persistent notification with pause/resume controls.
- **Limits:** The service enforces a 4-minute warning and a 5-minute hard stop to bound the amount of audio to process and prevent runaway recordings.
- **Storage:** Audio is written to app-private storage (`filesDir/recordings/`) as timestamped `.m4a` files by `AudioFileManager`. Partial files are deleted upon cancellation.
- **Handoff:** On successful stop, a `Note` entity is persisted (status `RECORDED`, bucket `INBOX`, tier `CORE`) and a WorkManager `SortingWorker` is enqueued to begin the processing pipeline.

## Alternatives Considered

- **Background Service:** Discarded. Modern Android strictly limits background execution and background microphone access. A foreground service is required.
- **AudioRecord vs. MediaRecorder:** `AudioRecord` provides raw PCM data, which requires manual encoding (e.g. to WAV or using a custom encoder like LAME or MediaCodec). `MediaRecorder` handles encoding (AAC/MPEG-4) efficiently in native code, saving battery and complexity, which is sufficient for voice note transcription.
- **In-Memory Capture:** Discarded. Voice notes can be long; streaming directly to memory risks OOM exceptions. Streaming directly to a file is safer and more robust.

## Consequences

- The app has a stable, OS-compliant recording mechanism that survives app backgrounding.
- The 5-minute hard limit bounds audio file size and subsequent pipeline processing time.
- The foreground service guarantees visibility to the user while the microphone is active, adhering to privacy best practices.
