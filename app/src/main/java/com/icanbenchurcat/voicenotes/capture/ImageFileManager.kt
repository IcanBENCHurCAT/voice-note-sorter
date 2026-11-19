// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Manages creation, copying, EXIF-aware bounded decoding, and directory isolation
 * for app-private captured/picked image files in `filesDir/images/`.
 * Conforms to the local-only and zero-permission guarantees (no READ_MEDIA_IMAGES or CAMERA).
 */
object ImageFileManager {

    const val IMAGES_DIR_NAME = "images"
    val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic")
    private const val DEFAULT_EXT = "jpg"

    fun getImagesDir(context: Context): File {
        val dir = File(context.filesDir, IMAGES_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun createImageOutputFile(
        context: Context,
        extension: String = DEFAULT_EXT,
        timestampEpochMs: Long = System.currentTimeMillis()
    ): File = createImageOutputFileInDir(getImagesDir(context), extension, timestampEpochMs)

    internal fun createImageOutputFileInDir(
        dir: File,
        extension: String = DEFAULT_EXT,
        timestampEpochMs: Long = System.currentTimeMillis()
    ): File {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val cleanExt = sanitizeExtension(extension)
        val timeString = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestampEpochMs))
        val candidate = File(dir, "image_${timeString}_$timestampEpochMs.$cleanExt")
        if (!candidate.exists()) {
            return candidate
        }
        val uniqueSuffix = UUID.randomUUID().toString().substring(0, 8)
        return File(dir, "image_${timeString}_${timestampEpochMs}_$uniqueSuffix.$cleanExt")
    }

    fun createCameraImageUri(context: Context, outputFile: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, outputFile)
    }

    /**
     * Copies a picked [sourceUri] (e.g., from `PickVisualMedia`) into app-private `filesDir/images/`
     * so temporary picker grants can expire safely and deletion cascades own the file lifecycle.
     */
    fun copyUriToAppPrivateImage(
        context: Context,
        sourceUri: Uri,
        timestampEpochMs: Long = System.currentTimeMillis()
    ): File? {
        val mimeType = runCatching { context.contentResolver.getType(sourceUri) }.getOrNull()
        val ext = extensionForMimeType(mimeType)
        val destination = createImageOutputFile(context, extension = ext, timestampEpochMs = timestampEpochMs)
        return try {
            val copied = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
                true
            } ?: false
            if (copied && destination.exists() && destination.length() > 0L) {
                destination
            } else {
                destination.delete()
                null
            }
        } catch (_: Throwable) {
            destination.delete()
            null
        }
    }

    internal fun extensionForMimeType(mimeType: String?): String = when (mimeType?.lowercase(Locale.ROOT)) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        else -> DEFAULT_EXT
    }

    internal fun sanitizeExtension(extension: String): String {
        val normalized = extension.trim().trimStart('.').lowercase(Locale.ROOT)
        return if (normalized in SUPPORTED_IMAGE_EXTENSIONS) normalized else DEFAULT_EXT
    }

    /**
     * Decodes [imagePath] into a bounded [Bitmap] applying EXIF rotation metadata
     * so both ML Kit GenAI `ImagePart` and Compose preview render upright without OOM.
     */
    fun decodeScaledBitmap(imagePath: String, maxDimensionPx: Int = 2048): Bitmap? {
        val file = File(imagePath)
        if (!file.exists() || !file.isFile || file.length() <= 0L) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imagePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sampleSize > maxDimensionPx) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeFile(imagePath, decodeOptions) ?: return null
        return applyExifOrientation(imagePath, decoded)
    }

    private fun applyExifOrientation(imagePath: String, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(imagePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return try {
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed !== bitmap) {
                bitmap.recycle()
            }
            transformed
        } catch (_: Throwable) {
            bitmap
        }
    }
}
