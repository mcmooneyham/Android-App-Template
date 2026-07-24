package com.mattmooneyham.base.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mechanical enforcement of two composition-root conventions the
 * AppComponent KDoc states in prose, in the same zero-dependency
 * scan-at-test-runtime style as CompositionRootGuardTest:
 *
 * 1. SINGLE PUBLISHER: an event key may be triggered only from the
 *    file that declares it (keys live beside their manager); any
 *    other code only listens.
 * 2. PROVIDER COMPLETENESS: AppModule exposes exactly the managers
 *    the AppComponent declares, so a forgotten (or leftover) Hilt
 *    provider is a test failure instead of a reviewer's catch.
 */
class WiringConventionsGuardTest {

    @Test
    fun everyKeyIsTriggeredOnlyFromItsDeclaringFile() {
        val sources = mainKotlinFiles()
        val declaringFileByKey = sources
            .flatMap { sourceFile ->
                KEY_DECLARATION.findAll(commentFreeTextOf(sourceFile))
                    .map { match -> match.groupValues[1] to sourceFile }
            }
            .toMap()
        assertTrue(
            "The guard found no event-key declarations; its scan is " +
                "broken and the guard is vacuous",
            declaringFileByKey.isNotEmpty(),
        )

        val violations = sources.flatMap { sourceFile ->
            TRIGGER_CALL.findAll(commentFreeTextOf(sourceFile))
                .mapNotNull { match ->
                    val keyName = match.groupValues[1]
                    val declaringFile = declaringFileByKey[keyName]
                    if (declaringFile != null &&
                        declaringFile != sourceFile
                    ) {
                        "${sourceFile.name} triggers $keyName, " +
                            "declared in ${declaringFile.name}"
                    } else {
                        null
                    }
                }
                .toList()
        }
        assertTrue(
            "Only the declaring manager's file may trigger a key " +
                "(the single-publisher rule); found: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun appModuleExposesExactlyTheComponentsManagers() {
        val componentManagerTypes = PUBLIC_MANAGER_PROPERTY
            .findAll(commentFreeTextOf(appSourceFile("di/AppComponent.kt")))
            .map { match -> match.groupValues[1] }
            .toSet()
        assertTrue(
            "The guard found no manager properties on AppComponent; " +
                "its scan is broken and the guard is vacuous",
            componentManagerTypes.isNotEmpty(),
        )

        val providedManagerTypes = PROVIDER_RETURN
            .findAll(commentFreeTextOf(appSourceFile("di/AppModule.kt")))
            .map { match -> match.groupValues[1] }
            .toSet()

        assertEquals(
            "AppModule must expose exactly the component's managers " +
                "(one @Provides per public manager val, no leftovers)",
            componentManagerTypes,
            providedManagerTypes,
        )
    }

    private fun mainKotlinFiles(): List<File> =
        resolveAppModuleDirectory()
            .resolve("src/main/kotlin")
            .walkTopDown()
            .filter { candidate ->
                candidate.isFile && candidate.extension == "kt"
            }
            .toList()

    private fun appSourceFile(relativePath: String): File =
        resolveAppModuleDirectory()
            .resolve("src/main/kotlin/com/mattmooneyham/base/android")
            .resolve(relativePath)

    /**
     * Block and line comments removed, so KDoc usage examples (the
     * EventManager and EventKey docs both show trigger calls) never
     * trip the scans. Line stripping truncates string literals that
     * contain "//" (the Joke API URL), which is harmless here: none
     * of the scanned patterns live inside strings.
     */
    private fun commentFreeTextOf(sourceFile: File): String =
        sourceFile.readText()
            .replace(BLOCK_COMMENT, "")
            .lineSequence()
            .joinToString("\n") { line -> line.substringBefore("//") }

    /** Same working-directory probing as CompositionRootGuardTest. */
    private fun resolveAppModuleDirectory(): File {
        val workingDirectory = File(System.getProperty("user.dir"))
        val candidateDirectories = listOf(
            workingDirectory,
            workingDirectory.resolve("app"),
        )
        return candidateDirectories.firstOrNull { candidate ->
            candidate.resolve("src/main").isDirectory
        } ?: error(
            "Cannot locate the app module from ${workingDirectory.path}",
        )
    }

    private companion object {
        val KEY_DECLARATION =
            Regex("""object\s+(\w+)\s*:\s*(?:StateKey|SignalKey)""")

        /** Matches `bus.trigger(Key` and `bus?.trigger(Key`. */
        val TRIGGER_CALL = Regex("""\.trigger\(\s*(\w+)""")

        /** Public manager vals on the component: `val x = XManager(`.
         * The leading anchor excludes `private val` members. */
        val PUBLIC_MANAGER_PROPERTY = Regex(
            """(?m)^\s*val\s+\w+\s*=\s*(\w*Manager)\(""",
        )

        /** Provider return types in AppModule: `): XManager =`. */
        val PROVIDER_RETURN = Regex("""\)\s*:\s*(\w*Manager)\s*=""")

        val BLOCK_COMMENT =
            Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    }
}
