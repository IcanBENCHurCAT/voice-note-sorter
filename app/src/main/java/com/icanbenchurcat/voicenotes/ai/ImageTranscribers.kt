// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.icanbenchurcat.voicenotes.capture.ImageFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Seam over the bundled offline OCR engine so [MlKitOcrImageTranscriber] can be
 * deterministically unit-tested on the JVM without Android runtime or Bitmap dependencies.
 */
interface BundledOcrEngine {
    suspend fun isAvailable(): Boolean = true
    suspend fun recognizeText(imageFile: File): String
}

/**
 * Production [BundledOcrEngine] backed by the **bundled**
 * `com.google.mlkit:text-recognition` artifact (zero network model downloads).
 * All ML Kit Vision Text imports in the repository are isolated in this file.
 */
class AndroidMlKitOcrEngine(private val context: Context) : BundledOcrEngine {

    override suspend fun isAvailable(): Boolean = try {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        client.close()
        true
    } catch (_: Throwable) {
        false
    }

    override suspend fun recognizeText(imageFile: File): String {
        require(imageFile.exists() && imageFile.isFile && imageFile.length() > 0L) {
            "Image file does not exist or is empty: ${imageFile.path}"
        }
        val bitmap = withContext(Dispatchers.IO) {
            ImageFileManager.decodeScaledBitmap(imageFile.absolutePath, maxDimensionPx = 2048)
        }
        val inputImage = if (bitmap != null) {
            InputImage.fromBitmap(bitmap, 0)
        } else {
            InputImage.fromFilePath(context, Uri.fromFile(imageFile))
        }
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCancellableCoroutine { cont ->
                client.process(inputImage)
                    .addOnSuccessListener { result ->
                        if (cont.isActive) cont.resume(result.text.orEmpty())
                    }
                    .addOnFailureListener { error ->
                        if (cont.isActive) cont.resumeWithException(error)
                    }
                    .addOnCanceledListener {
                        if (cont.isActive) cont.cancel()
                    }
            }
        } finally {
            runCatching { bitmap?.recycle() }
            runCatching { client.close() }
        }
    }
}

/**
 * Tier 2 ([Tier.CORE]) offline OCR image transcriber backed by bundled
 * `com.google.mlkit:text-recognition` via [BundledOcrEngine].
 * Demotes cleanly to [NullImageTranscriber] (`Tier.MINIMAL`) when the OCR engine
 * is unavailable or produces blank text.
 */
class MlKitOcrImageTranscriber(
    private val engine: BundledOcrEngine
) : ImageTranscriber {

    constructor(context: Context) : this(
        engine = AndroidMlKitOcrEngine(context.applicationContext)
    )

    override suspend fun status(): CapabilityStatus =
        if (engine.isAvailable()) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE

    override suspend fun transcribeImage(imagePath: String): Transcript {
        val file = File(imagePath)
        require(file.exists() && file.isFile && file.length() > 0L) {
            "Image file does not exist or is empty: $imagePath"
        }
        if (!engine.isAvailable()) {
            return NullImageTranscriber.transcribeImage(imagePath)
        }
        val text = engine.recognizeText(file).trim()
        if (text.isBlank()) {
            return NullImageTranscriber.transcribeImage(imagePath)
        }
        return Transcript(text, Tier.CORE)
    }
}
