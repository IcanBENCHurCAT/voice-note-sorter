// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.debug

import com.icanbenchurcat.voicenotes.fixtures.InMemoryFakeNoteDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class DebugSeedDataTest {

    @Test
    fun seedInto_setsElectricianActionDueAtFriday5pmInsteadOfClockOffset() = runTest {
        val zone = ZoneId.of("America/New_York")
        // Wed 2026-11-18 06:44 AM: previously now + 2*dayMs produced Nov 20, 6:44 AM
        val wed644Clock = Clock.fixed(
            LocalDateTime.of(2026, 11, 18, 6, 44, 0).atZone(zone).toInstant(),
            zone
        )
        val dao = InMemoryFakeNoteDao()

        DebugSeedData.seedInto(dao, wed644Clock)

        val notes = dao.observeAll().first()
        val taskNote = notes.first { it.bucketKind == "TASK" }
        val actions = dao.actionsFor(taskNote.id)
        val electricianAction = actions.first { it.text.contains("Call electrician") }
        val copperWireAction = actions.first { it.text.contains("14-gauge copper wire") }

        assertNotNull(electricianAction.dueAtEpochMs)
        val dueDateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(electricianAction.dueAtEpochMs!!),
            zone
        )
        assertEquals(DayOfWeek.FRIDAY, dueDateTime.dayOfWeek)
        assertEquals(LocalDateTime.of(2026, 11, 20, 17, 0), dueDateTime)
        assertNull(copperWireAction.dueAtEpochMs)
    }

    @Test
    fun seedInto_onFridayMorning_stillResolvesToFriday5pm() = runTest {
        val zone = ZoneId.of("UTC")
        val friMorningClock = Clock.fixed(
            LocalDateTime.of(2026, 11, 20, 6, 44, 0).atZone(zone).toInstant(),
            zone
        )
        val dao = InMemoryFakeNoteDao()

        DebugSeedData.seedInto(dao, friMorningClock)

        val taskNote = dao.observeAll().first().first { it.bucketKind == "TASK" }
        val electricianAction = dao.actionsFor(taskNote.id).first { it.text.contains("Call electrician") }
        val dueDateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(electricianAction.dueAtEpochMs!!),
            zone
        )
        assertEquals(DayOfWeek.FRIDAY, dueDateTime.dayOfWeek)
        assertEquals(17, dueDateTime.hour)
        assertEquals(0, dueDateTime.minute)
    }
}
