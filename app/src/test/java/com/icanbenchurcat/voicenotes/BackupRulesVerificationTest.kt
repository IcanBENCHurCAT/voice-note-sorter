// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupRulesVerificationTest {

    private val projectRoot = findProjectRoot()

    private fun findProjectRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "app/src/main/AndroidManifest.xml").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        return File(".").canonicalFile
    }

    private fun parseXml(file: File): Element {
        assertTrue("Expected XML file to exist at ${file.absolutePath}", file.exists())
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        return builder.parse(file).documentElement
    }

    @Test
    fun manifest_declaresDataExtractionRulesAndFullBackupContent() {
        val manifestFile = File(projectRoot, "app/src/main/AndroidManifest.xml")
        val root = parseXml(manifestFile)
        val appNodes = root.getElementsByTagName("application")
        assertEquals(1, appNodes.length)
        val app = appNodes.item(0) as Element

        val dataExtractionRules = app.getAttribute("android:dataExtractionRules")
        val fullBackupContent = app.getAttribute("android:fullBackupContent")

        assertEquals("@xml/data_extraction_rules", dataExtractionRules)
        assertEquals("@xml/backup_rules", fullBackupContent)
    }

    @Test
    fun dataExtractionRules_excludesDatabaseAndFilesFromCloudAndDeviceTransfer() {
        val rulesFile = File(projectRoot, "app/src/main/res/xml/data_extraction_rules.xml")
        val root = parseXml(rulesFile)
        assertEquals("data-extraction-rules", root.tagName)

        val cloudBackupNodes = root.getElementsByTagName("cloud-backup")
        assertEquals(1, cloudBackupNodes.length)
        val cloudBackup = cloudBackupNodes.item(0) as Element
        val cloudExcludes = (0 until cloudBackup.childNodes.length)
            .mapNotNull { cloudBackup.childNodes.item(it) as? Element }
            .filter { it.tagName == "exclude" }
            .map { it.getAttribute("domain") to it.getAttribute("path") }
            .toSet()

        assertTrue(cloudExcludes.contains("database" to "."))
        assertTrue(cloudExcludes.contains("file" to "."))
        assertTrue(cloudExcludes.contains("root" to "."))

        val d2dNodes = root.getElementsByTagName("device-transfer")
        assertEquals(1, d2dNodes.length)
        val d2d = d2dNodes.item(0) as Element
        val d2dExcludes = (0 until d2d.childNodes.length)
            .mapNotNull { d2d.childNodes.item(it) as? Element }
            .filter { it.tagName == "exclude" }
            .map { it.getAttribute("domain") to it.getAttribute("path") }
            .toSet()

        assertTrue(d2dExcludes.contains("database" to "."))
        assertTrue(d2dExcludes.contains("file" to "."))
        assertTrue(d2dExcludes.contains("root" to "."))
    }

    @Test
    fun backupRules_excludesDatabaseAndFilesFromLegacyBackup() {
        val rulesFile = File(projectRoot, "app/src/main/res/xml/backup_rules.xml")
        val root = parseXml(rulesFile)
        assertEquals("full-backup-content", root.tagName)

        val excludes = (0 until root.childNodes.length)
            .mapNotNull { root.childNodes.item(it) as? Element }
            .filter { it.tagName == "exclude" }
            .map { it.getAttribute("domain") to it.getAttribute("path") }
            .toSet()

        assertTrue(excludes.contains("database" to "."))
        assertTrue(excludes.contains("file" to "."))
        assertTrue(excludes.contains("root" to "."))
    }

    @Test
    fun manifest_declaresQueriesForAiCoreAndSpeechRecognition() {
        val manifestFile = File(projectRoot, "app/src/main/AndroidManifest.xml")
        val root = parseXml(manifestFile)
        val queriesNodes = root.getElementsByTagName("queries")
        assertEquals(1, queriesNodes.length)
        val queries = queriesNodes.item(0) as Element

        val packages = queries.getElementsByTagName("package")
        var foundAiCore = false
        for (i in 0 until packages.length) {
            val pkg = packages.item(i) as Element
            if (pkg.getAttribute("android:name") == "com.google.android.aicore") {
                foundAiCore = true
            }
        }
        assertTrue("Expected <package android:name=\"com.google.android.aicore\" /> inside <queries>", foundAiCore)

        val intents = queries.getElementsByTagName("intent")
        var foundSpeech = false
        for (i in 0 until intents.length) {
            val intent = intents.item(i) as Element
            val actions = intent.getElementsByTagName("action")
            for (j in 0 until actions.length) {
                val action = actions.item(j) as Element
                if (action.getAttribute("android:name") == "android.speech.RecognitionService") {
                    foundSpeech = true
                }
            }
        }
        assertTrue("Expected <action android:name=\"android.speech.RecognitionService\" /> inside <queries>", foundSpeech)
    }
}
