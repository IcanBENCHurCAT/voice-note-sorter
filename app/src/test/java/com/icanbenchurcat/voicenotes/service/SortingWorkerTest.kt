package com.icanbenchurcat.voicenotes.service

import com.icanbenchurcat.voicenotes.ai.ActionExtractor
import com.icanbenchurcat.voicenotes.ai.BucketAssignment
import com.icanbenchurcat.voicenotes.ai.BucketProfile
import com.icanbenchurcat.voicenotes.ai.Bucketer
import com.icanbenchurcat.voicenotes.ai.CapabilityBusyException
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.ExtractedAction
import com.icanbenchurcat.voicenotes.ai.Summarizer
import com.icanbenchurcat.voicenotes.ai.Tier
import com.icanbenchurcat.voicenotes.ai.TitleSummary
import com.icanbenchurcat.voicenotes.ai.Transcriber
import com.icanbenchurcat.voicenotes.ai.Transcript
import org.junit.Test

class FakeTranscriber(
    private val result: Transcript? = null,
    private val exception: Exception? = null
) : Transcriber {
    override suspend fun status(): CapabilityStatus = CapabilityStatus.AVAILABLE
    override suspend fun transcribe(audioPath: String): Transcript {
        if (exception != null) throw exception
        return result ?: Transcript("fake transcript", Tier.CORE)
    }
}

class FakeSummarizer : Summarizer {
    override suspend fun status(): CapabilityStatus = CapabilityStatus.AVAILABLE
    override suspend fun summarize(transcript: String): TitleSummary = TitleSummary("title", "summary")
}

class FakeActionExtractor : ActionExtractor {
    override suspend fun status(): CapabilityStatus = CapabilityStatus.AVAILABLE
    override suspend fun extract(transcript: String): List<ExtractedAction> = emptyList()
}

class FakeBucketer : Bucketer {
    override suspend fun assign(transcript: String, profile: BucketProfile): BucketAssignment =
        BucketAssignment("INBOX", emptyList(), Tier.CORE)
}

class SortingWorkerTest {

    @Test
    fun busyThrowsDemotesToPlatform() {
        val primary = FakeTranscriber(exception = CapabilityBusyException())
        val platform = FakeTranscriber(result = Transcript("platform transcript", Tier.CORE))
        val nullTranscriber = FakeTranscriber(result = Transcript("null", Tier.MINIMAL))
    }

    @Test
    fun allTranscribersUnavailableCompletesMinimal() {
        val primary = FakeTranscriber(exception = CapabilityBusyException())
        val platform = FakeTranscriber(exception = CapabilityBusyException())
        val nullTranscriber = FakeTranscriber(result = Transcript("", Tier.MINIMAL))
    }
}
