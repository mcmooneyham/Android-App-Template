package com.mattmooneyham.base.android.constants

/**
 * App build metadata for code outside :app. Only :app owns the
 * application BuildConfig (a library module's BuildConfig has no
 * versionName or build stamp), so :app constructs this value from
 * BuildConfig and provides it through Hilt; the UI layer injects it
 * instead of reading compile-time constants it cannot see. Tests
 * construct it directly with known values, which also makes About
 * assertions exact (BuildConfig constants are inlined per compilation
 * unit and cannot be compared across modules reliably).
 */
data class BuildInfo(
    val versionName: String,
    val buildTimestampSeconds: Long,
    val isDebugBuild: Boolean,
)
