// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Note::class, Bucket::class, ActionItem::class, Tag::class, NoteTag::class, NoteFts::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN status TEXT NOT NULL DEFAULT 'RECORDED'")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "voice-notes.db"
            )
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Seed default buckets on database creation
                        val defaultBuckets = listOf(
                            "IDEA" to "Ideas",
                            "TASK" to "Tasks",
                            "LIST" to "Lists",
                            "NOTE" to "Notes",
                            "INBOX" to "Inbox"
                        )
                        for ((kind, name) in defaultBuckets) {
                            db.execSQL("INSERT OR IGNORE INTO buckets (kind, name) VALUES ('$kind', '$name')")
                        }
                    }
                })
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}
