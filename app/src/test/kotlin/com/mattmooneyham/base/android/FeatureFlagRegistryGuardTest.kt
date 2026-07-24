package com.mattmooneyham.base.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architecture guard for the feature-flag registry: every
 * `object X : BooleanFlag(...)` declared in the main sources must be
 * listed in AppFlags.kt, or the FeatureFlagManager would silently
 * skip resolving it and the debug UI would not show it. Walks the
 * shipped source tree at TEST runtime, like CompositionRootGuardTest.
 */
class FeatureFlagRegistryGuardTest {

    @Test
    fun everyDeclaredFlagIsListedInTheRegistry() {
        val mainKotlinRoot = resolveAppModuleDirectory()
            .resolve("src/main/kotlin")
        val declaredFlagNames = mainKotlinRoot.walkTopDown()
            .filter { candidate ->
                candidate.isFile && candidate.extension == "kt"
            }
            .flatMap { sourceFile ->
                FLAG_DECLARATION_PATTERN
                    .findAll(sourceFile.readText())
                    .map { match -> match.groupValues[1] }
            }
            .toList()
        assertTrue(
            "The guard found no BooleanFlag declarations; either the " +
                "flag system was removed (delete this guard) or the " +
                "lookup is broken and the guard is vacuous",
            declaredFlagNames.isNotEmpty(),
        )

        val registryFile = mainKotlinRoot.walkTopDown()
            .firstOrNull { candidate -> candidate.name == "AppFlags.kt" }
            ?: error("AppFlags.kt not found under src/main/kotlin")
        val registrySource = registryFile.readText()

        val unregisteredFlagNames = declaredFlagNames
            .filterNot { flagName -> registrySource.contains(flagName) }
        assertTrue(
            "Every declared flag must be listed in AppFlags.all; " +
                "missing: $unregisteredFlagNames",
            unregisteredFlagNames.isEmpty(),
        )
    }

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
        // Any object extending BooleanFlag, however it is spaced.
        val FLAG_DECLARATION_PATTERN =
            Regex("""object\s+(\w+)\s*:\s*BooleanFlag\s*\(""")
    }
}
