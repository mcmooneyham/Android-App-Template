package com.mattmooneyham.base.android.managers.featureFlagManager

/**
 * A boolean feature flag, declared as an `object` beside the feature
 * that consumes it, exactly like an event key:
 *
 * ```
 * object JokeAutoRetryOnReconnectFlag : BooleanFlag(
 *     flagKey = "joke.autoRetryOnReconnect",
 *     default = false,
 *     description =
 *         "Auto-refresh a failed joke when connectivity returns",
 * )
 * ```
 *
 * Every declared flag must also be listed in [AppFlags.all] (the
 * debug UI and resolution walk that registry); the
 * FeatureFlagRegistryGuardTest fails the build when a declaration is
 * missing from the registry.
 *
 * [flagKey] follows the event-name convention "namespace.flagName": a
 * lowercase namespace naming the consuming feature, a dot, then a
 * camelCase flag description. The key is the wire/storage identity
 * (provider payloads and persisted overrides key on it), so renaming
 * one is a breaking change.
 *
 * @param flagKey stable identity, "namespace.flagName".
 * @param default the compiled-in value; the ONLY value release builds
 *   resolve unless a remote provider supplies one.
 * @param description one line shown in the debug flags UI.
 */
abstract class BooleanFlag(
    val flagKey: String,
    val default: Boolean,
    val description: String,
)
