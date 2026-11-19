// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.icanbenchurcat.voicenotes.ai.ActionExtractor
import com.icanbenchurcat.voicenotes.ai.BucketAssignment
import com.icanbenchurcat.voicenotes.ai.CapabilityBusyException
import com.icanbenchurcat.voicenotes.ai.CapabilityProbe
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.FakeCapabilityChecker
import com.icanbenchurcat.voicenotes.ai.NoOpEmbedder
import com.icanbenchurcat.voicenotes.ai.NullTranscriber
import com.icanbenchurcat.voicenotes.ai.Tier
import com.icanbenchurcat.voicenotes.ai.TitleSummary
import com.icanbenchurcat.voicenotes.ai.Transcript
import com.icanbenchurcat.voicenotes.fixtures.FakeActionExtractor
import com.icanbenchurcat.voicenotes.fixtures.FakeBucketer
import com.icanbenchurcat.voicenotes.fixtures.FakeEmbedder
import com.icanbenchurcat.voicenotes.fixtures.FakeSummarizer
import com.icanbenchurcat.voicenotes.fixtures.FakeTranscriber
import com.icanbenchurcat.voicenotes.fixtures.InMemoryFakeNoteDao
import com.icanbenchurcat.voicenotes.service.SortingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dao: InMemoryFakeNoteDao
    private lateinit var searchRepo: DefaultSearchRepository

    @Before
    fun setUp() {
        dao = InMemoryFakeNoteDao()
        searchRepo = DefaultSearchRepository(dao)
    }

    @Test
    fun blankQueryReturnsAllNotes() = runBlocking {
        dao.insert(Note(createdAtEpochMs = 1000L, audioPath = "", title = "Note 1"))
        dao.insert(Note(createdAtEpochMs = 2000L, audioPath = "", title = "Note 2"))

        val results = searchRepo.search("").first()
        assertEquals(2, results.size)
    }

    @Test
    fun searchMatchesTermsInTitleOrTranscript() = runBlocking {
        dao.insert(Note(createdAtEpochMs = 1000L, audioPath = "", title = "Project Phoenix", transcript = "Launch plan"))
        dao.insert(Note(createdAtEpochMs = 2000L, audioPath = "", title = "Grocery shopping", transcript = "Milk and eggs"))

        val phoenixResults = searchRepo.search("Phoenix").first()
        assertEquals(1, phoenixResults.size)
        assertEquals("Project Phoenix", phoenixResults[0].title)

        val groceryResults = searchRepo.search("milk").first()
        assertEquals(1, groceryResults.size)
        assertEquals("Grocery shopping", groceryResults[0].title)
    }

    @Test
    fun searchWithNoMatchReturnsEmptyList() = runBlocking {
        dao.insert(Note(createdAtEpochMs = 1000L, audioPath = "", title = "Alpha"))

        val results = searchRepo.search("Beta").first()
        assertEquals(0, results.size)
    }

    @Test
    fun tier1And2_noOpEmbedder_fallsBackToPureFts4_evenWhenEmbeddingsExist() = runBlocking {
        val ftsNoteId = dao.insert(
            Note(createdAtEpochMs = 1000L, audioPath = "", title = "Coffee beans", transcript = "Buy espresso roast")
        )
        val nonFtsNoteId = dao.insert(
            Note(createdAtEpochMs = 2000L, audioPath = "", title = "Morning latte", transcript = "Warm milk drink")
        )
        dao.upsertEmbedding(
            NoteEmbedding(
                noteId = nonFtsNoteId,
                modelId = "noop",
                dimension = 2,
                vectorBlob = EmbeddingCodec.encode(floatArrayOf(1f, 0f)),
                updatedAtEpochMs = 2000L
            )
        )

        val noopRepo = DefaultSearchRepository(dao, NoOpEmbedder())
        val results = noopRepo.search("espresso").first()
        assertEquals(listOf(ftsNoteId), results.map { it.id })
    }

    @Test
    fun tier3_hybridSearch_ranksFtsPlusSemanticFirst_andRetrievesPureSemanticMatchesBySimilarity() = runBlocking {
        // 1. Both FTS + high semantic similarity
        val bothId = dao.insert(
            Note(createdAtEpochMs = 1000L, audioPath = "", title = "Coffee shop order", transcript = "Get coffee beans")
        )
        // 2. FTS only (no embedding persisted yet)
        val ftsOnlyId = dao.insert(
            Note(createdAtEpochMs = 1500L, audioPath = "", title = "Coffee table fix", transcript = "Tighten table legs")
        )
        // 3. Semantic only - strong match (cosine = 0.96 with [1, 0, 0])
        val strongSemanticId = dao.insert(
            Note(createdAtEpochMs = 2000L, audioPath = "", title = "Morning espresso", transcript = "Pull double shot")
        )
        // 4. Semantic only - moderate match (cosine = 0.60 with [1, 0, 0])
        val moderateSemanticId = dao.insert(
            Note(createdAtEpochMs = 2500L, audioPath = "", title = "Afternoon tea", transcript = "Green tea leaves")
        )
        // 5. Unrelated note (orthogonal vector, cosine = 0.0) -> must be excluded
        val unrelatedId = dao.insert(
            Note(createdAtEpochMs = 3000L, audioPath = "", title = "Car insurance", transcript = "Renew policy")
        )

        val modelId = "test-semantic-v1"
        dao.upsertEmbedding(
            NoteEmbedding(bothId, modelId, 3, EmbeddingCodec.encode(floatArrayOf(0.95f, 0.31f, 0f)), 1000L)
        )
        dao.upsertEmbedding(
            NoteEmbedding(strongSemanticId, modelId, 3, EmbeddingCodec.encode(floatArrayOf(0.96f, 0.28f, 0f)), 2000L)
        )
        dao.upsertEmbedding(
            NoteEmbedding(moderateSemanticId, modelId, 3, EmbeddingCodec.encode(floatArrayOf(0.60f, 0.80f, 0f)), 2500L)
        )
        dao.upsertEmbedding(
            NoteEmbedding(unrelatedId, modelId, 3, EmbeddingCodec.encode(floatArrayOf(0.0f, 1.0f, 0f)), 3000L)
        )

        val embedder = FakeEmbedder(
            modelId = modelId,
            vectorsByText = mapOf("coffee" to floatArrayOf(1.0f, 0.0f, 0.0f))
        )
        val hybridRepo = DefaultSearchRepository(dao, embedder)

        val results = hybridRepo.search("coffee").first()
        assertEquals(
            listOf(bothId, ftsOnlyId, strongSemanticId, moderateSemanticId),
            results.map { it.id }
        )
    }

    @Test
    fun tier3_modelIdFiltering_ignoresOtherModelEmbeddings_andLazyReEmbedRestoresSearchability() = runTest {
        val dsScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val dsFile = tempFolder.newFile("search_reembed.preferences_pb").also { it.delete() }
            val dataStore = PreferenceDataStoreFactory.create(scope = dsScope) { dsFile }
            val probe = CapabilityProbe(
                FakeCapabilityChecker(
                    transcriber = CapabilityStatus.AVAILABLE,
                    summarizer = CapabilityStatus.AVAILABLE,
                    actions = CapabilityStatus.AVAILABLE
                ),
                dataStore
            )

            // Note A has an embedding from an OLD model that happens to look identical to the query vector,
            // and has NO FTS match for "beverage".
            val legacySemanticId = dao.insert(
                Note(
                    createdAtEpochMs = 1000L,
                    audioPath = "/fake/a.m4a",
                    title = "Morning espresso",
                    summary = "Pull double shot",
                    transcript = "Pull double shot"
                )
            )
            // Note B has an embedding from the ACTIVE model.
            val activeSemanticId = dao.insert(
                Note(
                    createdAtEpochMs = 2000L,
                    audioPath = "/fake/b.m4a",
                    title = "Iced latte",
                    summary = "Cold brew drink",
                    transcript = "Cold brew drink"
                )
            )

            dao.upsertEmbedding(
                NoteEmbedding(
                    noteId = legacySemanticId,
                    modelId = "legacy-model-v1",
                    dimension = 2,
                    vectorBlob = EmbeddingCodec.encode(floatArrayOf(1.0f, 0.0f)),
                    updatedAtEpochMs = 1000L
                )
            )
            dao.upsertEmbedding(
                NoteEmbedding(
                    noteId = activeSemanticId,
                    modelId = "active-model-v2",
                    dimension = 2,
                    vectorBlob = EmbeddingCodec.encode(floatArrayOf(0.8f, 0.6f)),
                    updatedAtEpochMs = 2000L
                )
            )

            val activeEmbedder = FakeEmbedder(
                modelId = "active-model-v2",
                vectorsByText = mapOf(
                    "beverage" to floatArrayOf(1.0f, 0.0f),
                    "Morning espresso\nPull double shot\nPull double shot" to floatArrayOf(0.95f, 0.31f)
                )
            )
            val repo = DefaultSearchRepository(dao, activeEmbedder)

            // Before lazy re-embedding: legacy-model-v1 row MUST be ignored!
            val beforeReEmbed = repo.search("beverage").first()
            assertEquals(listOf(activeSemanticId), beforeReEmbed.map { it.id })

            // Re-run SortingWorker pipeline on Note A with activeEmbedder -> lazily re-embeds under active-model-v2
            SortingWorker.executePipeline(
                note = dao.byId(legacySemanticId)!!,
                capabilityProbe = probe,
                transcriber = FakeTranscriber(result = Transcript("Pull double shot", Tier.CORE)),
                platformTranscriber = FakeTranscriber(),
                nullTranscriber = NullTranscriber,
                summarizer = FakeSummarizer(result = TitleSummary("Morning espresso", "Pull double shot")),
                extractor = FakeActionExtractor(),
                bucketer = FakeBucketer(BucketAssignment("NOTE", emptyList(), Tier.CORE)),
                dao = dao,
                embedder = activeEmbedder
            )
            assertEquals("active-model-v2", dao.embeddingForNote(legacySemanticId)?.modelId)

            // After lazy re-embedding: Note A is now discovered and outranks Note B (0.95 > 0.80)
            val afterReEmbed = repo.search("beverage").first()
            assertEquals(listOf(legacySemanticId, activeSemanticId), afterReEmbed.map { it.id })
        } finally {
            dsScope.cancel()
        }
    }

    @Test
    fun tier3_embedderBusyOrMalformedQuery_fallsBackSafelyToFts4() = runBlocking {
        val ftsId = dao.insert(
            Note(createdAtEpochMs = 1000L, audioPath = "", title = "Dentist checkup", transcript = "Teeth cleaning")
        )
        val semanticOnlyId = dao.insert(
            Note(createdAtEpochMs = 2000L, audioPath = "", title = "Orthodontist", transcript = "Braces adjustment")
        )
        dao.upsertEmbedding(
            NoteEmbedding(
                noteId = semanticOnlyId,
                modelId = "busy-model",
                dimension = 2,
                vectorBlob = EmbeddingCodec.encode(floatArrayOf(1.0f, 0.0f)),
                updatedAtEpochMs = 2000L
            )
        )

        val busyEmbedder = FakeEmbedder(
            modelId = "busy-model",
            exception = CapabilityBusyException()
        )
        val repo = DefaultSearchRepository(dao, busyEmbedder)

        // CapabilityBusyException falls back to FTS4 cleanly
        val results = repo.search("dentist").first()
        assertEquals(listOf(ftsId), results.map { it.id })

        // Punctuation-only query with available embedder returns empty list without invoking embed
        val healthyEmbedder = FakeEmbedder(modelId = "busy-model", defaultVector = floatArrayOf(1.0f, 0.0f))
        val healthyRepo = DefaultSearchRepository(dao, healthyEmbedder)
        val malformedResults = healthyRepo.search("\"\"***!!!").first()
        assertEquals(0, malformedResults.size)
        assertEquals(0, healthyEmbedder.calls)
    }
}
