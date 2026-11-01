// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Seam over the ML Kit GenAI Speech Recognition client so that
 * [NanoTranscriber] can be unit-tested across AVAILABLE, DOWNLOADABLE,
 * UNAVAILABLE, and BUSY outcomes without requiring AICore hardware.
 */
interface GenAiSpeechClient {
    suspend fun checkStatus(advancedMode: Boolean): CapabilityStatus
    suspend fun recognizeFile(audioPath: String, advancedMode: Boolean): String
}

/**
 * Production [GenAiSpeechClient] backed by `com.google.mlkit:genai-speech-recognition`.
 * All ML Kit GenAI imports in the repository are isolated in this file.
 */
object MlKitGenAiSpeechClient : GenAiSpeechClient {

    override suspend fun checkStatus(advancedMode: Boolean): CapabilityStatus {
        val mode = if (advancedMode) {
            SpeechRecognizerOptions.Mode.MODE_ADVANCED
        } else {
            SpeechRecognizerOptions.Mode.MODE_BASIC
        }
        val recognizer = try {
            SpeechRecognition.getClient(
                speechRecognizerOptions {
                    locale = Locale.getDefault()
                    preferredMode = mode
                }
            )
        } catch (_: Throwable) {
            return CapabilityStatus.UNAVAILABLE
        }
        return try {
            when (recognizer.checkStatus()) {
                FeatureStatus.AVAILABLE -> CapabilityStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> CapabilityStatus.DOWNLOADABLE
                else -> CapabilityStatus.UNAVAILABLE
            }
        } catch (e: GenAiException) {
            if (isBusyOrQuotaError(e)) {
                CapabilityStatus.BUSY
            } else {
                CapabilityStatus.UNAVAILABLE
            }
        } catch (_: Throwable) {
            // Verified in Issue #41: checkStatus() can throw an uncaught gRPC
            // StatusException (zzaxh: PERMISSION_DENIED) on unsupported targets.
            CapabilityStatus.UNAVAILABLE
        } finally {
            runCatching { recognizer.close() }
        }
    }

    override suspend fun recognizeFile(audioPath: String, advancedMode: Boolean): String {
        val file = File(audioPath)
        require(file.exists() && file.isFile && file.length() > 0L) {
            "Audio file does not exist or is empty: $audioPath"
        }
        val mode = if (advancedMode) {
            SpeechRecognizerOptions.Mode.MODE_ADVANCED
        } else {
            SpeechRecognizerOptions.Mode.MODE_BASIC
        }
        val recognizer = SpeechRecognition.getClient(
            speechRecognizerOptions {
                locale = Locale.getDefault()
                preferredMode = mode
            }
        )
        try {
            val source = AudioSource.fromFile(file)
            try {
                val request = speechRecognizerRequest {
                    audioSource = source
                    // Verified in Issue #41: enableWordTiming must remain false for
                    // AudioSource.fromFile (AudioSource.Mode.ONE_SHOT).
                }
                val finalChunks = StringBuilder()
                var lastPartial = ""
                try {
                    recognizer.startRecognition(request).collect { response ->
                        when (response) {
                            is SpeechRecognizerResponse.FinalTextResponse -> {
                                finalChunks.append(response.text)
                            }
                            is SpeechRecognizerResponse.PartialTextResponse -> {
                                lastPartial = response.text
                            }
                            is SpeechRecognizerResponse.AudioLevelResponse,
                            is SpeechRecognizerResponse.CompletedResponse -> Unit
                            is SpeechRecognizerResponse.ErrorResponse -> {
                                if (isBusyOrQuotaError(response.e)) {
                                    throw CapabilityBusyException()
                                }
                                throw response.e
                            }
                        }
                    }
                } catch (e: CapabilityBusyException) {
                    throw e
                } catch (e: GenAiException) {
                    if (isBusyOrQuotaError(e)) {
                        throw CapabilityBusyException()
                    }
                    throw e
                }
                return finalChunks.toString().ifBlank { lastPartial }.trim()
            } finally {
                runCatching { source.close() }
            }
        } finally {
            runCatching { recognizer.close() }
        }
    }

    private fun isBusyOrQuotaError(e: GenAiException): Boolean =
        e.errorCode == GenAiException.ErrorCode.BUSY ||
            e.errorCode == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED
}

/**
 * Tier 2/3 transcription via ML Kit GenAI Speech Recognition when its
 * feature status allows, falling back to the platform SpeechRecognizer.
 * The ML Kit call site is isolated here so the alpha/beta API surface
 * churns in exactly one file (see AGENTS.md: interfaces first).
 */
class NanoTranscriber(
    private val genAiClient: GenAiSpeechClient,
    private val fallbackTranscriber: Transcriber
) : Transcriber {

    constructor(context: Context) : this(
        genAiClient = MlKitGenAiSpeechClient,
        fallbackTranscriber = PlatformTranscriber(context)
    )

    /** Returns the status of the Tier 3 (Advanced / AICore) speech mode specifically. */
    suspend fun advancedStatus(): CapabilityStatus =
        genAiClient.checkStatus(advancedMode = true)

    override suspend fun status(): CapabilityStatus {
        val advanced = genAiClient.checkStatus(advancedMode = true)
        if (advanced == CapabilityStatus.AVAILABLE || advanced == CapabilityStatus.BUSY) {
            return advanced
        }

        val basic = genAiClient.checkStatus(advancedMode = false)
        if (basic == CapabilityStatus.AVAILABLE || basic == CapabilityStatus.BUSY) {
            return basic
        }

        if (advanced == CapabilityStatus.DOWNLOADABLE || basic == CapabilityStatus.DOWNLOADABLE) {
            return CapabilityStatus.DOWNLOADABLE
        }

        return CapabilityStatus.UNAVAILABLE
    }

    override suspend fun transcribe(audioPath: String): Transcript {
        when (genAiClient.checkStatus(advancedMode = true)) {
            CapabilityStatus.AVAILABLE -> {
                val text = genAiClient.recognizeFile(audioPath, advancedMode = true)
                return Transcript(text, Tier.FULL)
            }
            CapabilityStatus.BUSY -> throw CapabilityBusyException()
            CapabilityStatus.DOWNLOADABLE, CapabilityStatus.UNAVAILABLE -> Unit
        }

        when (genAiClient.checkStatus(advancedMode = false)) {
            CapabilityStatus.AVAILABLE -> {
                val text = genAiClient.recognizeFile(audioPath, advancedMode = false)
                return Transcript(text, Tier.CORE)
            }
            CapabilityStatus.BUSY -> throw CapabilityBusyException()
            CapabilityStatus.DOWNLOADABLE, CapabilityStatus.UNAVAILABLE -> Unit
        }

        // GenAI feature is unavailable or still downloadable: fall through
        // explicitly to the fallback transcriber if it can transcribe files,
        // or to NullTranscriber (Tier 1) if no on-device engine can transcribe
        // saved files on this device.
        if (fallbackTranscriber.status() != CapabilityStatus.AVAILABLE) {
            CapabilityProbe.logDemotion("transcriber", Tier.FULL, Tier.MINIMAL)
            return NullTranscriber.transcribe(audioPath)
        }
        CapabilityProbe.logDemotion("transcriber", Tier.FULL, Tier.CORE)
        return fallbackTranscriber.transcribe(audioPath)
    }
}

/**
 * Seam over the platform saved-file speech recognition engine so that
 * [PlatformTranscriber] can be unit-tested with real audio files on disk
 * and verified for main-thread confinement when called from a WorkManager
 * background worker.
 */
interface PlatformFileSpeechEngine {
    fun isFileTranscriptionAvailable(): Boolean
    suspend fun transcribeFile(audioFile: File): String
}

/**
 * Production [PlatformFileSpeechEngine] using Android 13+ (API 33+)
 * [RecognizerIntent.EXTRA_AUDIO_SOURCE] with a read-only [ParcelFileDescriptor].
 *
 * Verified in Issue #41 (`docs/capability-verification.md`):
 * - `RecognizerIntent.EXTRA_AUDIO_SOURCE` does not exist on API 31–32. On
 *   those API levels, [isFileTranscriptionAvailable] returns `false` and
 *   [PlatformTranscriber] never opens a live microphone session.
 * - `android.speech.SpeechRecognizer` must be created, started, and
 *   destroyed on the main thread ([Dispatchers.Main]), even when invoked
 *   from a background [androidx.work.CoroutineWorker].
 */
class AndroidPlatformFileSpeechEngine(
    private val context: Context,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : PlatformFileSpeechEngine {

    override fun isFileTranscriptionAvailable(): Boolean {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
            return false
        }
        return try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ||
                SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("InlinedApi")
    override suspend fun transcribeFile(audioFile: File): String {
        require(audioFile.exists() && audioFile.isFile && audioFile.length() > 0L) {
            "Audio file does not exist or is empty: ${audioFile.path}"
        }
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
            throw UnsupportedOperationException(
                "Saved-file transcription via platform SpeechRecognizer requires API 33+"
            )
        }

        val audioStreamConfig = openAudioPipeOrDescriptor(audioFile)
        val pfd = audioStreamConfig.pfd

        return try {
            withContext(mainDispatcher) {
                suspendCancellableCoroutine { cont ->
                    val recognizer = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } else {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                        putExtra(
                            RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        putExtra(
                            RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                            audioStreamConfig.sampleRateHz
                        )
                        putExtra(
                            RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,
                            audioStreamConfig.channelCount
                        )
                    }
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onResults(results: Bundle?) {
                            val text = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull().orEmpty()
                            runCatching { pfd.close() }
                            recognizer.destroy()
                            if (cont.isActive) cont.resume(text)
                        }

                        override fun onError(error: Int) {
                            runCatching { pfd.close() }
                            recognizer.destroy()
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                                        CapabilityBusyException()
                                    } else {
                                        IllegalStateException("Speech recognition error $error")
                                    }
                                )
                            }
                        }

                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    cont.invokeOnCancellation {
                        runCatching { pfd.close() }
                        runCatching { recognizer.destroy() }
                    }
                    recognizer.startListening(intent)
                }
            }
        } finally {
            runCatching { pfd.close() }
        }
    }

    private data class AudioStreamConfig(
        val pfd: ParcelFileDescriptor,
        val sampleRateHz: Int,
        val channelCount: Int
    )

    private fun openAudioPipeOrDescriptor(file: File): AudioStreamConfig {
        val bytes = file.readBytes()
        if (bytes.size > WAV_HEADER_BYTES &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
        ) {
            val header = ByteBuffer.wrap(bytes, 0, WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val channels = header.getShort(22).toInt().coerceIn(1, 2)
            val sampleRate = header.getInt(24).takeIf { it in 8_000..48_000 } ?: DEFAULT_SAMPLE_RATE_HZ
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]
            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                        out.write(bytes, WAV_HEADER_BYTES, bytes.size - WAV_HEADER_BYTES)
                        out.flush()
                    }
                } catch (_: Throwable) {
                }
            }.start()
            return AudioStreamConfig(readSide, sampleRate, channels)
        }
        return AudioStreamConfig(
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            sampleRateHz = DEFAULT_SAMPLE_RATE_HZ,
            channelCount = DEFAULT_CHANNEL_COUNT
        )
    }

    companion object {
        private const val WAV_HEADER_BYTES = 44
        private const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        private const val DEFAULT_CHANNEL_COUNT = 1
    }
}

/**
 * Platform SpeechRecognizer fallback for saved audio files.
 * Never opens a live microphone session; if the device cannot transcribe
 * saved files (e.g. API 31–32 or no speech recognition service), returns
 * a Tier 1 ([Tier.MINIMAL]) result so the note remains playable.
 */
class PlatformTranscriber(
    private val engine: PlatformFileSpeechEngine
) : Transcriber {

    constructor(context: Context) : this(
        engine = AndroidPlatformFileSpeechEngine(context.applicationContext)
    )

    override suspend fun status(): CapabilityStatus =
        if (engine.isFileTranscriptionAvailable()) CapabilityStatus.AVAILABLE
        else CapabilityStatus.UNAVAILABLE

    override suspend fun transcribe(audioPath: String): Transcript {
        val file = File(audioPath)
        require(file.exists() && file.isFile && file.length() > 0L) {
            "Audio file does not exist or is empty: $audioPath"
        }
        if (!engine.isFileTranscriptionAvailable()) {
            return NullTranscriber.transcribe(audioPath)
        }
        val text = engine.transcribeFile(file)
        return Transcript(text, Tier.CORE)
    }
}

/** Tier 1: no transcription. The note is filed by hand; the app still
 *  works as a recorder with playback, which is the whole point of the
 *  ladder - a rung, not a failure. */
object NullTranscriber : Transcriber {
    override suspend fun status(): CapabilityStatus = CapabilityStatus.UNAVAILABLE
    override suspend fun transcribe(audioPath: String): Transcript = Transcript("", Tier.MINIMAL)
}
