// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.capture

import com.icanbenchurcat.voicenotes.data.NoteSourceType
import com.icanbenchurcat.voicenotes.data.NoteStatus
import com.icanbenchurcat.voicenotes.fixtures.InMemoryFakeNoteDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageFileManagerAndIngestionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun createImageOutputFileInDir_createsDirAndUniqueTimestampedFilename() {
        val targetDir = File(tempFolder.root, "images")
        val file1 = ImageFileManager.createImageOutputFileInDir(
            dir = targetDir,
            extension = ".PNG",
            timestampEpochMs = 1_700_000_000_000L
        )
        assertTrue(targetDir.exists())
        assertTrue(file1.name.startsWith("image_"))
        assertTrue(file1.name.endsWith(".png"))

        file1.writeBytes(byteArrayOf(1, 2, 3))
        val file2 = ImageFileManager.createImageOutputFileInDir(
            dir = targetDir,
            extension = "png",
            timestampEpochMs = 1_700_000_000_000L
        )
        assertTrue(file1.absolutePath != file2.absolutePath)
    }

    @Test
    fun extensionForMimeType_andSanitizeExtension_handleSupportedFormatsSafely() {
        assertEquals("png", ImageFileManager.extensionForMimeType("image/png"))
        assertEquals("webp", ImageFileManager.extensionForMimeType("image/webp"))
        assertEquals("heic", ImageFileManager.extensionForMimeType("image/heic"))
        assertEquals("jpg", ImageFileManager.extensionForMimeType("image/jpeg"))
        assertEquals("jpg", ImageFileManager.extensionForMimeType(null))

        assertEquals("jpg", ImageFileManager.sanitizeExtension("../../etc/passwd"))
        assertEquals("jpeg", ImageFileManager.sanitizeExtension(".JPEG"))
    }

    @Test
    fun ingestImageFile_persistsImageNoteAndEnqueuesWorker() = runTest {
        val dao = InMemoryFakeNoteDao()
        val imagesDir = tempFolder.newFolder("images")
        val imageFile = File(imagesDir, "whiteboard.jpg").apply {
            writeBytes(ByteArray(128) { 0x11 })
        }

        val enqueuedIds = mutableListOf<Long>()
        val noteId = ImageIngestionCoordinator.ingestImageFile(
            dao = dao,
            imageFile = imageFile,
            createdAtEpochMs = 1_700_000_000_000L,
            enqueueWorker = { enqueuedIds += it }
        )

        assertNotNull(noteId)
        assertEquals(listOf(noteId!!), enqueuedIds)

        val persisted = dao.byId(noteId)!!
        assertEquals(NoteSourceType.IMAGE.name, persisted.sourceType)
        assertEquals(imageFile.absolutePath, persisted.imagePath)
        assertEquals("", persisted.audioPath)
        assertEquals("INBOX", persisted.bucketKind)
        assertEquals(NoteStatus.RECORDED.name, persisted.status)
    }

    @Test
    fun ingestImageFile_ignoresMissingOrEmptyFile() = runTest {
        val dao = InMemoryFakeNoteDao()
        val emptyFile = tempFolder.newFile("empty.jpg")
        val missingFile = File(tempFolder.root, "missing.jpg")
        val enqueuedIds = mutableListOf<Long>()

        assertNull(ImageIngestionCoordinator.ingestImageFile(dao, emptyFile) { enqueuedIds += it })
        assertNull(ImageIngestionCoordinator.ingestImageFile(dao, missingFile) { enqueuedIds += it })
        assertTrue(enqueuedIds.isEmpty())
        assertTrue(dao.notes.isEmpty())
    }
}
