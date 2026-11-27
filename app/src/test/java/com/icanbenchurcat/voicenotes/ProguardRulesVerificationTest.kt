// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProguardRulesVerificationTest {

    private fun findProjectRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "app/proguard-rules.pro").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        return File(".").canonicalFile
    }

    private fun readProguardRules(): String {
        val rulesFile = File(findProjectRoot(), "app/proguard-rules.pro")
        assertTrue("Expected proguard-rules.pro to exist at ${rulesFile.absolutePath}", rulesFile.exists())
        return rulesFile.readText()
    }

    @Test
    fun proguardRules_keepComponentRegistrarConstructorsForR8FullMode() {
        val rules = readProguardRules()
        val registrarRuleRegex = Regex(
            pattern = """-keep\s+class\s+\*\s+implements\s+com\.google\.firebase\.components\.ComponentRegistrar\s*\{[^}]*<init>\(\);[^}]*\}""",
            options = setOf(RegexOption.DOT_MATCHES_ALL)
        )
        assertTrue(
            "app/proguard-rules.pro must keep ComponentRegistrar no-arg constructors (<init>()) under R8 full mode",
            registrarRuleRegex.containsMatchIn(rules)
        )
    }

    @Test
    fun proguardRules_keepMlKitGenAiAndGenerableProviders() {
        val rules = readProguardRules()
        assertTrue(
            "app/proguard-rules.pro must keep com.google.mlkit.genai.**",
            rules.contains("-keep class com.google.mlkit.genai.**")
        )
        assertTrue(
            "app/proguard-rules.pro must keep GenerableProvider implementations",
            rules.contains("-keep class * implements com.google.mlkit.genai.schema.guided.GenerableProvider")
        )
    }
}
