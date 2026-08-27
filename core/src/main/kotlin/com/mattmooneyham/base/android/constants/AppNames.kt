package com.mattmooneyham.base.android.constants

/**
 * Every name the app stamps onto things it creates, in ONE place:
 * change [FILE_BASE_NAME] (or the scheme) here and every consumer
 * follows, derived names included. Managers reference these constants
 * instead of declaring their own literals.
 *
 * Two places cannot read Kotlin constants and must be kept in sync BY
 * HAND when renaming:
 *  - the deep-link scheme literal in app/src/main/AndroidManifest.xml
 *  - the log-directory exclusion in
 *    app/src/main/res/xml/data_extraction_rules.xml
 */
object AppNames {

    /** The stem of every file this app creates in its files dir. */
    const val FILE_BASE_NAME = "base_app"

    /** Naming template for log files: LogManager splits it into stem
     * and extension to derive the daily dated files
     * (base_app-2026-01-01.log), their numbered size rolls
     * (base_app-2026-01-01.1.log), and the "-export.zip" snapshot. */
    const val LOG_FILE_NAME = "$FILE_BASE_NAME.log"

    /** Subdirectory of the files dir holding every log file; mirrored
     * by hand in app/src/main/res/xml/data_extraction_rules.xml. */
    const val LOG_DIRECTORY_NAME = "logs"

    /** The app's Preferences DataStore file. */
    const val PREFERENCES_STORE_FILE_NAME =
        "$FILE_BASE_NAME.preferences_pb"

    /** The debug-only feature-flag overrides DataStore file. */
    const val FLAG_OVERRIDES_STORE_FILE_NAME =
        "$FILE_BASE_NAME.flag_overrides.preferences_pb"

    /** Deep-link scheme (baseapp://...); mirrored as a literal in the
     * manifest's intent filter. */
    const val DEEP_LINK_SCHEME = "baseapp"
}
