// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.capture

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icanbenchurcat.voicenotes.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureIntentConsumptionTest {

    @Test
    fun consumeStartRecordingIntent_withExtra_returnsTrueAndRemovesExtra() {
        val intent = Intent().apply {
            putExtra(MainActivity.EXTRA_START_RECORDING, true)
        }

        val consumed = MainActivity.consumeStartRecordingIntent(intent)

        assertTrue(consumed)
        assertFalse(intent.getBooleanExtra(MainActivity.EXTRA_START_RECORDING, false))
    }

    @Test
    fun consumeStartRecordingIntent_withoutExtra_returnsFalse() {
        val intent = Intent()

        val consumed = MainActivity.consumeStartRecordingIntent(intent)

        assertFalse(consumed)
    }

    @Test
    fun consumeStartRecordingIntent_nullIntent_returnsFalse() {
        val consumed = MainActivity.consumeStartRecordingIntent(null)

        assertFalse(consumed)
    }
}
