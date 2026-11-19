// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteDaoFtsSearchTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: NoteDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.noteDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun search_matchesTitleTranscriptSummary_andOrdersNewestFirst() = runBlocking {
        val id1 = dao.insert(
            Note(
                createdAtEpochMs = 1_000L,
                audioPath = "/audio/1.m4a",
                title = "Grocery List",
                summary = "Apples, bananas, and milk",
                transcript = "Remember to buy some snacks too"
            )
        )
        val id2 = dao.insert(
            Note(
                createdAtEpochMs = 2_000L,
                audioPath = "/audio/2.m4a",
                title = "Weekly Standup",
                summary = "Discuss project milestones",
                transcript = "We need more groceries for the office kitchen"
            )
        )
        val id3 = dao.insert(
            Note(
                createdAtEpochMs = 3_000L,
                audioPath = "/audio/3.m4a",
                title = "Doctor Appointment",
                summary = "Annual checkup",
                transcript = "Check blood pressure and schedule follow up"
            )
        )

        // "groceries" should match id2 (in transcript) and id1 ("Grocery List" prefix match via sanitizer)
        val query = FtsQuerySanitizer.sanitize("grocer") ?: "grocer*"
        val results = dao.search(query).first()

        assertEquals(2, results.size)
        // Ordered newest first (id2 has 2000L, id1 has 1000L)
        assertEquals(id2, results[0].id)
        assertEquals(id1, results[1].id)
    }

    @Test
    fun search_sanitizerHandlesMalformedOperatorsWithoutThrowing() = runBlocking {
        dao.insert(
            Note(
                createdAtEpochMs = 1_000L,
                audioPath = "/audio/1.m4a",
                title = "Team Meeting",
                summary = "Q3 roadmap review",
                transcript = "Let's review the deliverables"
            )
        )

        // Raw malformed input containing unclosed quotes, asterisks, brackets, NOT/AND
        val malformed1 = "\"\"***meeting***\""
        val sanitized1 = FtsQuerySanitizer.sanitize(malformed1)
        assertEquals("meeting*", sanitized1)
        val results1 = dao.search(sanitized1!!).first()
        assertEquals(1, results1.size)
        assertEquals("Team Meeting", results1[0].title)

        // Query with pure special chars produces null and search is safely bypassed
        val malformed2 = "!@#$%^&*()\"\"\""
        val sanitized2 = FtsQuerySanitizer.sanitize(malformed2)
        assertEquals(null, sanitized2)
    }

    @Test
    fun migration2To3_createsNoteEmbeddingsTable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "migration_test_v2_to_v3.db"
        context.deleteDatabase(dbName)

        try {
            val helperV2 = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbName)
                    .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS `notes` (" +
                                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "`createdAtEpochMs` INTEGER NOT NULL, " +
                                    "`audioPath` TEXT NOT NULL, " +
                                    "`transcript` TEXT NOT NULL, " +
                                    "`title` TEXT NOT NULL, " +
                                    "`summary` TEXT NOT NULL, " +
                                    "`bucketKind` TEXT NOT NULL, " +
                                    "`tierUsed` TEXT NOT NULL, " +
                                    "`stageStatus` TEXT NOT NULL, " +
                                    "`status` TEXT NOT NULL DEFAULT 'RECORDED')"
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    })
                    .build()
            )
            helperV2.writableDatabase.close()
            helperV2.close()

            val helperV3 = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbName)
                    .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) {}
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            AppDatabase.MIGRATION_2_3.migrate(db)
                        }
                    })
                    .build()
            )
            helperV3.writableDatabase.use { v3Db ->
                v3Db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='note_embeddings'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("note_embeddings", cursor.getString(0))
                }
            }
            helperV3.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migration3To4_preservesExistingAudioRowsWithDefaultSourceTypeAndEmptyImagePath() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "migration_test_v3_to_v4.db"
        context.deleteDatabase(dbName)

        try {
            val helperV3 = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbName)
                    .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS `notes` (" +
                                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "`createdAtEpochMs` INTEGER NOT NULL, " +
                                    "`audioPath` TEXT NOT NULL, " +
                                    "`transcript` TEXT NOT NULL, " +
                                    "`title` TEXT NOT NULL, " +
                                    "`summary` TEXT NOT NULL, " +
                                    "`bucketKind` TEXT NOT NULL, " +
                                    "`tierUsed` TEXT NOT NULL, " +
                                    "`stageStatus` TEXT NOT NULL, " +
                                    "`status` TEXT NOT NULL DEFAULT 'RECORDED')"
                            )
                            AppDatabase.MIGRATION_2_3.migrate(db)
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    })
                    .build()
            )
            helperV3.writableDatabase.use { v3Db ->
                v3Db.execSQL(
                    "INSERT INTO notes (id, createdAtEpochMs, audioPath, transcript, title, summary, bucketKind, tierUsed, stageStatus, status) " +
                        "VALUES (1, 1000, '/files/recordings/1.m4a', 'Buy milk', 'Milk', 'Buy milk', 'TASK', 'CORE', 'transcribe:OK', 'SORTED')"
                )
            }
            helperV3.close()

            val helperV4 = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbName)
                    .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: SupportSQLiteDatabase) {}
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            AppDatabase.MIGRATION_3_4.migrate(db)
                        }
                    })
                    .build()
            )
            helperV4.writableDatabase.use { v4Db ->
                v4Db.query("SELECT sourceType, imagePath, audioPath, status FROM notes WHERE id = 1").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("AUDIO", cursor.getString(0))
                    assertEquals("", cursor.getString(1))
                    assertEquals("/files/recordings/1.m4a", cursor.getString(2))
                    assertEquals("SORTED", cursor.getString(3))
                }
            }
            helperV4.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }
}
