package com.icanbenchurcat.voicenotes.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Tier 2/3 transcription via ML Kit GenAI Speech Recognition when its
 * feature status allows, falling back to the platform SpeechRecognizer.
 * The ML Kit call site is isolated here so the alpha/beta API surface
 * churns in exactly one file (see AGENTS.md: interfaces first).
 */
class NanoTranscriber(private val context: Context) : Transcriber {

    override suspend fun status(): CapabilityStatus = CapabilityStatus.AVAILABLE

    override suspend fun transcribe(audioPath: String): Transcript {
        // TODO(ml-kit): route through ML Kit GenAI Speech Recognition
        // (Basic on API 31+, Advanced on supported flagships) once the
        // beta API stabilises; platform recogniser is the honest
        // fallback and the current implementation.
        return PlatformTranscriber(context).transcribe(audioPath)
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
