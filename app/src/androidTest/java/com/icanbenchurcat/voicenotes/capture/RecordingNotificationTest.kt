// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.capture

import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingNotificationTest {

    @Test
    fun testActiveRecordingNotification() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notification = RecordingService.buildRecordingNotification(
            context = context,
            isPaused = false,
            elapsedSec = 10L,
            isWarning = false,
            nowEpochMs = 100000L
        )

        assertEquals(NotificationCompat.CATEGORY_STOPWATCH, notification.category)
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, notification.visibility)
        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue((notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
        assertTrue(notification.extras.getBoolean("android.requestPromotedOngoing"))
        assertEquals(3, notification.actions?.size)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }

    @Test
    fun testPausedRecordingNotification() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notification = RecordingService.buildRecordingNotification(
            context = context,
            isPaused = true,
            elapsedSec = 45L,
            isWarning = false,
            nowEpochMs = 100000L
        )

        assertEquals(NotificationCompat.CATEGORY_STOPWATCH, notification.category)
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, notification.visibility)
        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue((notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
        assertTrue(notification.extras.getBoolean("android.requestPromotedOngoing"))
        assertEquals(3, notification.actions?.size)
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }

    @Test
    fun testWarningRecordingNotification() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notification = RecordingService.buildRecordingNotification(
            context = context,
            isPaused = false,
            elapsedSec = 245L,
            isWarning = true,
            nowEpochMs = 300000L
        )

        assertEquals(NotificationCompat.CATEGORY_STOPWATCH, notification.category)
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, notification.visibility)
        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue((notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
        assertTrue(notification.extras.getBoolean("android.requestPromotedOngoing"))
        assertEquals(3, notification.actions?.size)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }
}
