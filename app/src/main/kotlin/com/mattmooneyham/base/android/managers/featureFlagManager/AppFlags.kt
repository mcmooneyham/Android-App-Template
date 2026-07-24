package com.mattmooneyham.base.android.managers.featureFlagManager

import com.mattmooneyham.base.android.managers.JokeAutoRetryOnReconnectFlag

/**
 * The registry of every declared [BooleanFlag]. Flags stay DECLARED
 * beside their consuming feature (like event keys); this list only
 * enumerates them so the FeatureFlagManager can resolve them all and
 * the Settings debug UI can render them all.
 *
 * Add every new flag here. FeatureFlagRegistryGuardTest fails the
 * build when a `: BooleanFlag(` declaration in the main sources is
 * missing from this file, so the registry cannot silently drift.
 */
object AppFlags {

    val all: List<BooleanFlag> = listOf(
        JokeAutoRetryOnReconnectFlag,
    )
}
