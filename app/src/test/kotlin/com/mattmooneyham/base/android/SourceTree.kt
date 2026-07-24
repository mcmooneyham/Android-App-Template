package com.mattmooneyham.base.android

import java.io.File

// Shared source-tree access for the source-scanning guard tests,
// which span all three modules (:core, :ui, :app) since the layer
// split. One definition for the main-kotlin scans, so those guards
// cannot drift apart; CompositionRootGuardTest deliberately scans a
// WIDER tree of its own (androidTest plus kts/xml/pro/md files, raw
// text because comments are in scope there).

internal val GUARDED_MODULES = listOf("core", "ui", "app")

/**
 * The repository root, located by probing for settings.gradle.kts
 * upward from the test's working directory (Gradle runs JVM unit
 * tests with user.dir at the module directory; IDEs may use the
 * repository root).
 */
internal fun repositoryRoot(): File {
    var candidate: File? = File(System.getProperty("user.dir"))
    while (candidate != null) {
        if (candidate.resolve("settings.gradle.kts").isFile) {
            return candidate
        }
        candidate = candidate.parentFile
    }
    error("Cannot locate the repository root (no settings.gradle.kts)")
}

/** Every module's src/main/kotlin root that exists. */
internal fun mainKotlinRoots(): List<File> = GUARDED_MODULES
    .map { moduleName ->
        repositoryRoot().resolve("$moduleName/src/main/kotlin")
    }
    .filter(File::isDirectory)

/** Every main-source Kotlin file across the guarded modules. */
internal fun allMainKotlinFiles(): List<File> = mainKotlinRoots()
    .flatMap { sourceRoot ->
        sourceRoot.walkTopDown()
            .filter { candidate ->
                candidate.isFile && candidate.extension == "kt"
            }
            .toList()
    }

/**
 * Block and line comments removed, so KDoc usage examples (the
 * EventManager and EventKey docs both show trigger calls) never trip
 * the scans. Line stripping truncates string literals that contain
 * "//" (the Joke API URL), which is harmless: none of the scanned
 * patterns live inside strings.
 */
internal fun commentFreeTextOf(sourceFile: File): String =
    sourceFile.readText()
        .replace(BLOCK_COMMENT_PATTERN, "")
        .lineSequence()
        .joinToString("\n") { line -> line.substringBefore("//") }

private val BLOCK_COMMENT_PATTERN =
    Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
