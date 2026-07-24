package com.mattmooneyham.base.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architecture guard for the feature-flag registry: every
 * `object X : BooleanFlag(...)` declared in the main sources must be
 * listed in AppFlags.kt, or the FeatureFlagManager would silently
 * skip resolving it and the debug UI would not show it. Walks the
 * shipped source tree at TEST runtime (never at Gradle configuration
 * time, which would fight the configuration cache).
 */
class FeatureFlagRegistryGuardTest {

    @Test
    fun everyDeclaredFlagIsListedInTheRegistry() {
        val mainKotlinFiles = allMainKotlinFiles()
        val declaredFlagNames = mainKotlinFiles
            .flatMap { sourceFile ->
                FLAG_DECLARATION_PATTERN
                    .findAll(sourceFile.readText())
                    .map { match -> match.groupValues[1] }
            }
        assertTrue(
            "The guard found no BooleanFlag declarations; either the " +
                "flag system was removed (delete this guard) or the " +
                "lookup is broken and the guard is vacuous",
            declaredFlagNames.isNotEmpty(),
        )

        val registryFile = mainKotlinFiles
            .firstOrNull { candidate -> candidate.name == "AppFlags.kt" }
            ?: error("AppFlags.kt not found in any module's main sources")
        val registrySource = registryFile.readText()

        val unregisteredFlagNames = declaredFlagNames
            .filterNot { flagName -> registrySource.contains(flagName) }
        assertTrue(
            "Every declared flag must be listed in AppFlags.all; " +
                "missing: $unregisteredFlagNames",
            unregisteredFlagNames.isEmpty(),
        )
    }

    private companion object {
        // Any object extending BooleanFlag, however it is spaced.
        val FLAG_DECLARATION_PATTERN =
            Regex("""object\s+(\w+)\s*:\s*BooleanFlag\s*\(""")
    }
}
