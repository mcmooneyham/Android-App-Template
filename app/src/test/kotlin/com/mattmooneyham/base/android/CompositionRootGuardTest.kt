package com.mattmooneyham.base.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architecture guards for the composition root: the process container
 * is [com.mattmooneyham.base.android.di.AppComponent], the retired
 * global-singleton container it replaced must never come back, and
 * this template is a standalone APP, so nothing in the main sources
 * may describe its own core with library-kit terminology (see
 * [FORBIDDEN_CORE_TERM]; the retired config and Hilt module names are
 * now AppConfig and AppModule).
 * Walks the shipped source tree at TEST runtime (never at Gradle
 * configuration time, which would fight the configuration cache) and
 * fails on any reference to the retired names, comments included.
 * Android platform terms (Build.VERSION.SDK_INT and the Gradle
 * compileSdk family) are allowlisted.
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

    @Test
    fun mainSourcesContainNoOwnSdkTerminology() {
        val kotlinFiles = allMainKotlinFiles()
        assertTrue(
            "The guard found no Kotlin files under any module's " +
                "src/main/kotlin; its source-root lookup is broken " +
                "and the guard is vacuous",
            kotlinFiles.isNotEmpty(),
        )

        val offendingLines = kotlinFiles.flatMap { sourceFile ->
            sourceFile.readLines()
                .mapIndexedNotNull { lineIndex, sourceLine ->
                    // Remove allowlisted platform terms, then flag any
                    // surviving use of the forbidden term in code,
                    // comments, or strings, in any letter case.
                    val strippedLine = ALLOWED_PLATFORM_TERMS
                        .fold(sourceLine) { partiallyStripped, term ->
                            partiallyStripped.replace(term, "")
                        }
                    val offends = strippedLine.contains(
                        FORBIDDEN_CORE_TERM,
                        ignoreCase = true,
                    )
                    if (offends) {
                        "${sourceFile.path}:${lineIndex + 1}"
                    } else {
                        null
                    }
                }
        }
        assertTrue(
            "This template is a standalone app; " +
                "'$FORBIDDEN_CORE_TERM' terminology (any case) must " +
                "not appear in src/main/kotlin outside the " +
                "allowlisted platform terms $ALLOWED_PLATFORM_TERMS; " +
                "found at: $offendingLines",
            offendingLines.isEmpty(),
        )
    }

    /** The guarded source sets: every module's main set plus the
     * instrumented tests (shipped-adjacent, and not where the guards
     * live). Only the JVM unit-test sets, which hold the guards
     * themselves, are excluded. */
    private fun guardedSourceRoots(): List<File> =
        GUARDED_SOURCE_SETS
            .map { sourceSetPath ->
                repositoryRoot().resolve(sourceSetPath)
            }
            .filter { sourceRoot -> sourceRoot.isDirectory }

    private companion object {
        // Assembled from halves so this file itself would not trip a
        // future guard that also scans test sources.
        const val RETIRED_CONTAINER_NAME = "Base" + "Sdk"

        // The standalone-app rule: the template must never describe
        // its own core with this library-kit term. Matched
        // case-insensitively, so it catches identifiers, comments,
        // and file-name strings alike. Assembled from halves like the
        // retired container name above.
        const val FORBIDDEN_CORE_TERM = "Sd" + "k"

        // Android platform terms that legitimately contain the word;
        // stripped from a line before the forbidden-term check.
        val ALLOWED_PLATFORM_TERMS = listOf(
            "SDK_INT",
            "compileSdk",
            "minSdk",
            "targetSdk",
            "sdk.dir",
        )

        // The JVM unit-test sets are deliberately absent (they hold
        // the guards themselves); androidTest IS scanned, since flow
        // tests ship conventions worth guarding too.
        val GUARDED_SOURCE_SETS = listOf(
            "core/src/main",
            "ui/src/main",
            "app/src/main",
            "app/src/androidTest",
        )

        // Text formats that can carry code or docs; binaries (launcher
        // icons and the like) are skipped rather than read as text.
        val TEXT_FILE_EXTENSIONS = setOf("kt", "kts", "xml", "pro", "md")
    }
}
