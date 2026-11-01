// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
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
            val source = AudioSource.fromFile(File(audioPath))
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
        // explicitly to the fallback transcriber and preserve the tier that
        // actually produced the transcript.
        CapabilityProbe.logDemotion("transcriber", Tier.FULL, Tier.CORE)
        return fallbackTranscriber.transcribe(audioPath)
    }
}

/** Platform SpeechRecognizer fallback. Works broadly, no Nano required. */
class PlatformTranscriber(private val context: Context) : Transcriber {

    override suspend fun status(): CapabilityStatus =
        if (SpeechRecognizer.isRecognitionAvailable(context)) CapabilityStatus.AVAILABLE
        else CapabilityStatus.UNAVAILABLE

    override suspend fun transcribe(audioPath: String): Transcript =
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    recognizer.destroy()
                    if (cont.isActive) cont.resume(Transcript(text, Tier.CORE))
                }

                override fun onError(error: Int) {
                    recognizer.destroy()
                    if (cont.isActive) {
                        cont.resumeWithException(
                            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) CapabilityBusyException()
                            else IllegalStateException("Speech recognition error $error")
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
            cont.invokeOnCancellation { recognizer.destroy() }
            recognizer.startListening(intent)
        }
}

/** Tier 1: no transcription. The note is filed by hand; the app still
 *  works as a recorder with playback, which is the whole point of the
 *  ladder - a rung, not a failure. */
object NullTranscriber : Transcriber {
    override suspend fun status(): CapabilityStatus = CapabilityStatus.UNAVAILABLE
    override suspend fun transcribe(audioPath: String): Transcript = Transcript("", Tier.MINIMAL)
}
