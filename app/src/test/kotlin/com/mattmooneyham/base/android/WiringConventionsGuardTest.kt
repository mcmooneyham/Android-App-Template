package com.mattmooneyham.base.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mechanical enforcement of conventions stated in prose (the
 * single-publisher rule in the EventManager/EventKey KDocs, provider
 * completeness in the AppModule KDoc), by scanning the shipped source
 * tree at TEST runtime with zero extra dependencies (never at Gradle
 * configuration time, which would fight the configuration cache):
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
                    when (val declaringFile =
                        declaringFileByKey[keyName]) {
                        // An identifier that is not a declared key is
                        // an alias (`val k = SomeKey`), which would
                        // otherwise slip this scan entirely: trigger
                        // through the key object itself, always.
                        null ->
                            "${sourceFile.name} triggers '$keyName', " +
                                "which is not a declared key object " +
                                "(no aliasing)"
                        sourceFile -> null
                        else ->
                            "${sourceFile.name} triggers $keyName, " +
                                "declared in ${declaringFile.name}"
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
        // KNOWN LIMIT: the property scan sees constructor-call
        // initializers (`val x = XManager(...)`). A manager built
        // through a factory function AND missing its provider is
        // invisible to this mirror; the convention is therefore that
        // AppComponent always constructs managers directly, which is
        // also what keeps declaration order the construction order.
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

    @Test
    fun everyDestinationIsDataWithAStableToString() {
        // TabStackHost keys per-screen saveable state by
        // destination.toString(). A data class or data object prints
        // stable content; a PLAIN class or object inherits the
        // identity-hash toString, which differs in a new process, so
        // its saveable state would silently fail to restore after
        // process death. This scan turns that latent trap into a
        // build failure.
        val navigationSources = allMainKotlinFiles().filter { file ->
            file.path.contains("/navigation/")
        }
        assertTrue(
            "The guard found no navigation sources; its scan is " +
                "broken and the guard is vacuous",
            navigationSources.isNotEmpty(),
        )

        val violations = navigationSources.flatMap { sourceFile ->
            DESTINATION_DECLARATION
                .findAll(commentFreeTextOf(sourceFile))
                .mapNotNull { match ->
                    val modifierList = match.groupValues[1]
                    val declarationName = match.groupValues[3]
                    val hasStableToString = "data" in modifierList ||
                        "enum" in modifierList ||
                        "sealed" in modifierList
                    if (hasStableToString) {
                        null
                    } else {
                        "${sourceFile.name}: $declarationName must " +
                            "be a data class or data object"
                    }
                }
                .toList()
        }
        assertTrue(
            "Destination toString is the saveable-state key and must " +
                "be stable across process death; found: $violations",
            violations.isEmpty(),
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

        /** Concrete declarations whose supertype list names a
         * Destination type; group 1 holds the modifier keywords. */
        val DESTINATION_DECLARATION = Regex(
            """(?m)^\s*((?:\w+\s+)*)(class|object)\s+(\w+)""" +
                """[^\n{]*:\s*[^\n{]*Destination\b""",
        )
    }
}
