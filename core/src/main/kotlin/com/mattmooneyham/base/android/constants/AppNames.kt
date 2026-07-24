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
 *  - the log-file exclusions in
 *    app/src/main/res/xml/data_extraction_rules.xml
 */
object AppNames {

    /** The stem of every file this app creates in its files dir. */
    const val FILE_BASE_NAME = "base_app"

    /** The live log file; rotation inserts ".1" before the extension
     * and the Settings export appends "-export" (LogManager derives
     * both from this name). */
    const val LOG_FILE_NAME = "$FILE_BASE_NAME.log"

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
