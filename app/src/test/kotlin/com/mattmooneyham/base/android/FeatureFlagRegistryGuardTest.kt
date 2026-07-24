package com.mattmooneyham.base.android

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
        // Comment-free text: the BooleanFlag KDoc shows a declaration
        // example that must never count as a declared flag.
        val declaredFlagNames = mainKotlinFiles
            .flatMap { sourceFile ->
                FLAG_DECLARATION_PATTERN
                    .findAll(commentFreeTextOf(sourceFile))
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
        val registeredFlagNames =
            registeredFlagNamesIn(commentFreeTextOf(registryFile))

        // Exact identifier membership in the listOf(...) block: a flag
        // name that merely APPEARS elsewhere in AppFlags.kt (an import,
        // a stale mention) must not satisfy the guard.
        val unregisteredFlagNames = declaredFlagNames
            .filterNot { flagName -> flagName in registeredFlagNames }
        assertTrue(
            "Every declared flag must be listed in AppFlags.all; " +
                "missing: $unregisteredFlagNames",
            unregisteredFlagNames.isEmpty(),
        )
    }

    /** The identifiers listed inside AppFlags.all's listOf(...) block.
     * Entries are plain object references, so the block contains no
     * nested parentheses and a first-`)` match is safe. */
    private fun registeredFlagNamesIn(registrySource: String): Set<String> {
        val registryListBody = REGISTRY_LIST_PATTERN
            .find(registrySource)
            ?.groupValues
            ?.get(1)
            ?: error("AppFlags.all's listOf(...) block not found")
        return IDENTIFIER_PATTERN
            .findAll(registryListBody)
            .map { match -> match.value }
            .toSet()
    }

    private companion object {
        // Any object extending BooleanFlag, however it is spaced.
        val FLAG_DECLARATION_PATTERN =
            Regex("""object\s+(\w+)\s*:\s*BooleanFlag\s*\(""")

        // AppFlags.all's initializer up to the list's closing paren.
        val REGISTRY_LIST_PATTERN =
            Regex("""val\s+all\b[^=]*=\s*listOf\(([^)]*)\)""")

        val IDENTIFIER_PATTERN = Regex("""[A-Za-z_]\w*""")
    }
}
