// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.MlKitOcrImageTranscriber
import com.icanbenchurcat.voicenotes.ai.NanoImageTranscriber
import com.icanbenchurcat.voicenotes.ai.NanoSummarizer
import com.icanbenchurcat.voicenotes.ai.NanoTranscriber
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class NoInternetPermissionInstrumentedTest {

    @Test
    fun installedPackage_doesNotRequestInternetPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requested = pkgInfo.requestedPermissions?.toList().orEmpty()
        assertFalse(
            "Open Core APK must never request android.permission.INTERNET (requested=$requested)",
            requested.contains(Manifest.permission.INTERNET)
        )
    }

    @Test
    fun mlKitCapabilities_operateWithoutInternetPermission() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val transcriberStatus = NanoTranscriber(context).status()
        val summarizerStatus = NanoSummarizer().status()
        val imageNanoStatus = NanoImageTranscriber(context).status()
        val ocrTranscriber = MlKitOcrImageTranscriber(context)
        val ocrStatus = ocrTranscriber.status()

        assertTrue(transcriberStatus in CapabilityStatus.entries)
        assertTrue(summarizerStatus in CapabilityStatus.entries)
        assertTrue(imageNanoStatus in CapabilityStatus.entries)
        assertTrue(ocrStatus in CapabilityStatus.entries)

        val tempImage = File(context.cacheDir, "no_internet_ocr_test_${System.nanoTime()}.png")
        try {
            val bitmap = Bitmap.createBitmap(640, 200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 48f
            }
            canvas.drawText("Buy milk tomorrow", 40f, 110f, paint)
            FileOutputStream(tempImage).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            bitmap.recycle()

            val transcript = ocrTranscriber.transcribeImage(tempImage.absolutePath)
            assertTrue(
                "Expected bundled OCR to recognize 'milk' offline, got '${transcript.text}'",
                transcript.text.contains("milk", ignoreCase = true)
            )
        } finally {
            tempImage.delete()
        }
    }
}
