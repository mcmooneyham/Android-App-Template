package com.mattmooneyham.base.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architecture guard for the composition root: the process container is
 * [com.mattmooneyham.base.android.di.AppComponent], and the retired
 * global-singleton container it replaced must never come back. Walks
 * the shipped source tree at TEST runtime (never at Gradle
 * configuration time, which would fight the configuration cache) and
 * fails on any reference to the retired name, comments included.
 */
class CompositionRootGuardTest {

    @Test
    fun sourceTreeContainsNoReferenceToTheRetiredContainer() {
        val scannedFiles = guardedSourceRoots().flatMap { sourceRoot ->
            sourceRoot.walkTopDown()
                .filter { candidate ->
                    candidate.isFile &&
                        candidate.extension in TEXT_FILE_EXTENSIONS
                }
                .toList()
        }
        assertTrue(
            "The guard found no source files to scan; its source-root " +
                "lookup is broken and the guard is vacuous",
            scannedFiles.isNotEmpty(),
        )

        val offendingPaths = scannedFiles
            .filter { sourceFile ->
                sourceFile.readText().contains(RETIRED_CONTAINER_NAME)
            }
            .map { sourceFile -> sourceFile.path }
        assertTrue(
            "'$RETIRED_CONTAINER_NAME' must not appear anywhere in " +
                "shipped sources; found in: $offendingPaths",
            offendingPaths.isEmpty(),
        )
    }

    /** The existing source sets under app/src, minus this test's own. */
    private fun guardedSourceRoots(): List<File> {
        val appModuleDirectory = resolveAppModuleDirectory()
        return GUARDED_SOURCE_SETS
            .map { sourceSetName ->
                appModuleDirectory.resolve("src/$sourceSetName")
            }
            .filter { sourceRoot -> sourceRoot.isDirectory }
    }

    /**
     * Gradle runs JVM unit tests with user.dir at the module directory
     * (ROOT/app); an IDE may use the repository root instead. Probe
     * both so the guard never silently scans nothing.
     */
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
        // Assembled from halves so this file itself would not trip a
        // future guard that also scans test sources.
        const val RETIRED_CONTAINER_NAME = "Base" + "Sdk"

        // src/test is deliberately absent: it holds this guard.
        val GUARDED_SOURCE_SETS = listOf("main", "androidTest")

        // Text formats that can carry code or docs; binaries (launcher
        // icons and the like) are skipped rather than read as text.
        val TEXT_FILE_EXTENSIONS = setOf("kt", "kts", "xml", "pro", "md")
    }
}
