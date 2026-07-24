package com.mattmooneyham.base.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mechanical enforcement of two conventions stated in prose (the
 * single-publisher rule in the EventManager/EventKey KDocs, provider
 * completeness in the AppModule KDoc), in the same zero-dependency
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
        val sources = allMainKotlinFiles()
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

    /** The composition root's own files live in :app. */
    private fun appSourceFile(relativePath: String): File =
        repositoryRoot()
            .resolve("app/src/main/kotlin/com/mattmooneyham/base/android")
            .resolve(relativePath)

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
    }
}
