buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        // AGP 9's built-in Kotlin ships KGP 2.2.10. Pin the KGP version
        // explicitly so the Kotlin compiler plugins (compose,
        // serialization) resolve against the same Kotlin.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
