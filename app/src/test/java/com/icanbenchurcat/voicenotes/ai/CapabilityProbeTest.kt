// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class FakeCapabilityChecker(
    var transcriber: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    var summarizer: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    var actions: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    var fingerprint: String = "app=1|aicore=none",
    var probeDelayMs: Long = 0L
) : CapabilityChecker {
    val transcriberCalls = AtomicInteger(0)
    val summarizerCalls = AtomicInteger(0)
    val actionsCalls = AtomicInteger(0)

    override suspend fun checkTranscriber(): CapabilityStatus {
        if (probeDelayMs > 0) delay(probeDelayMs)
        transcriberCalls.incrementAndGet()
        return transcriber
    }

    override suspend fun checkSummarizer(): CapabilityStatus {
        if (probeDelayMs > 0) delay(probeDelayMs)
        summarizerCalls.incrementAndGet()
        return summarizer
    }

    override suspend fun checkActionExtractor(): CapabilityStatus {
        if (probeDelayMs > 0) delay(probeDelayMs)
        actionsCalls.incrementAndGet()
        return actions
    }

    override fun versionFingerprint(): String = fingerprint
}

class CapabilityProbeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = tempFolder.newFile("test_capabilities.preferences_pb")
        dataStoreFile.delete()
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { dataStoreFile }
        )
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun tier1NoAiPath_allCapabilitiesUnavailable_resolvesMinimalTier() = runTest {
        val checker = FakeCapabilityChecker(
            transcriber = CapabilityStatus.UNAVAILABLE,
            summarizer = CapabilityStatus.UNAVAILABLE,
            actions = CapabilityStatus.UNAVAILABLE
        )
        val probe = CapabilityProbe(checker, dataStore)

        assertEquals(CapabilityStatus.UNAVAILABLE, probe.transcriberStatus())
        assertEquals(CapabilityStatus.UNAVAILABLE, probe.summarizerStatus())
        assertEquals(CapabilityStatus.UNAVAILABLE, probe.actionExtractorStatus())
        assertEquals(Tier.MINIMAL, probe.resolveDeviceTier())
        assertEquals(1, checker.transcriberCalls.get())
        assertEquals(1, checker.summarizerCalls.get())
        assertEquals(1, checker.actionsCalls.get())
    }

    @Test
    fun summarizerAndActions_transitionFromDownloadableToAvailableWhenDownloadCompletes() = runTest {
        val checker = FakeCapabilityChecker(
            transcriber = CapabilityStatus.AVAILABLE,
            summarizer = CapabilityStatus.DOWNLOADABLE,
            actions = CapabilityStatus.DOWNLOADABLE,
            fingerprint = "app=1|aicore=100"
        )
        val probe = CapabilityProbe(checker, dataStore)

        // While downloading, status reflects DOWNLOADABLE and device tier is CORE
        assertEquals(CapabilityStatus.DOWNLOADABLE, probe.summarizerStatus())
        assertEquals(CapabilityStatus.DOWNLOADABLE, probe.actionExtractorStatus())
        assertEquals(Tier.CORE, probe.resolveDeviceTier())

        // Model finishes downloading -> transitions to AVAILABLE on next probe without manual invalidation
        checker.summarizer = CapabilityStatus.AVAILABLE
        checker.actions = CapabilityStatus.AVAILABLE

        assertEquals(CapabilityStatus.AVAILABLE, probe.summarizerStatus())
        assertEquals(CapabilityStatus.AVAILABLE, probe.actionExtractorStatus())
        assertEquals(Tier.FULL, probe.resolveDeviceTier())

        // Subsequent calls hit the terminal AVAILABLE cache without re-invoking checker
        val sumCallsAfterReady = checker.summarizerCalls.get()
        assertEquals(CapabilityStatus.AVAILABLE, probe.summarizerStatus())
        assertEquals(sumCallsAfterReady, checker.summarizerCalls.get())
    }

    @Test
    fun persistedResultsSurviveProcessDeath_andInvalidateOnAiCoreOrAppUpdate() = runTest {
        val checker1 = FakeCapabilityChecker(
            transcriber = CapabilityStatus.AVAILABLE,
            summarizer = CapabilityStatus.AVAILABLE,
            actions = CapabilityStatus.UNAVAILABLE,
            fingerprint = "app=1|aicore=200"
        )
        val probe1 = CapabilityProbe(checker1, dataStore)
        assertEquals(CapabilityStatus.AVAILABLE, probe1.transcriberStatus())
        assertEquals(CapabilityStatus.AVAILABLE, probe1.summarizerStatus())
        assertEquals(CapabilityStatus.UNAVAILABLE, probe1.actionExtractorStatus())

        // Simulate process death: create a fresh CapabilityProbe reading the same DataStore
        val checker2 = FakeCapabilityChecker(
            transcriber = CapabilityStatus.UNAVAILABLE, // should NOT be called while fingerprint matches
            summarizer = CapabilityStatus.UNAVAILABLE,
            actions = CapabilityStatus.AVAILABLE,
            fingerprint = "app=1|aicore=200"
        )
        val probe2 = CapabilityProbe(checker2, dataStore)
        assertEquals(CapabilityStatus.AVAILABLE, probe2.transcriberStatus())
        assertEquals(CapabilityStatus.AVAILABLE, probe2.summarizerStatus())
        assertEquals(CapabilityStatus.UNAVAILABLE, probe2.actionExtractorStatus())
        assertEquals("Persisted DataStore cache should be used without re-probing", 0, checker2.transcriberCalls.get())
        assertEquals(0, checker2.summarizerCalls.get())
        assertEquals(0, checker2.actionsCalls.get())

        // Now AICore updates to version 201 -> cache invalidates and re-probes
        checker2.fingerprint = "app=1|aicore=201"
        assertEquals(CapabilityStatus.UNAVAILABLE, probe2.transcriberStatus())
        assertEquals(CapabilityStatus.UNAVAILABLE, probe2.summarizerStatus())
        assertEquals(CapabilityStatus.AVAILABLE, probe2.actionExtractorStatus())
        assertEquals(1, checker2.transcriberCalls.get())
        assertEquals(1, checker2.summarizerCalls.get())
        assertEquals(1, checker2.actionsCalls.get())
    }

    @Test
    fun concurrentCoroutineAccess_isThreadSafeAndProbesOnlyOnce() = runTest {
        val checker = FakeCapabilityChecker(
            transcriber = CapabilityStatus.AVAILABLE,
            summarizer = CapabilityStatus.AVAILABLE,
            actions = CapabilityStatus.AVAILABLE,
            fingerprint = "app=1|aicore=300",
            probeDelayMs = 20L
        )
        val probe = CapabilityProbe(checker, dataStore)

        val results = (1..24).map {
            async { probe.transcriberStatus() }
        }.awaitAll()

        assertEquals(24, results.size)
        results.forEach { assertEquals(CapabilityStatus.AVAILABLE, it) }
        assertEquals(1, checker.transcriberCalls.get())
    }
}
