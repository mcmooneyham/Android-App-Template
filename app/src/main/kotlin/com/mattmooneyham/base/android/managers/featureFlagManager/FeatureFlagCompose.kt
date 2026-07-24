package com.mattmooneyham.base.android.managers.featureFlagManager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import com.mattmooneyham.base.android.managers.eventManager.eventStateOrNull

/**
 * Observes [flag] as Compose state: the resolved value now, updating
 * live on every [FeatureFlagsChanged] snapshot (provider refreshes,
 * debug overrides). Gate UI with it in one line:
 *
 * ```
 * val isNewCardEnabled by flagState(NewCardFlag)
 * if (isNewCardEnabled) { NewCard() }
 * ```
 */
@Composable
fun flagState(flag: BooleanFlag): State<Boolean> {
    val snapshotState = eventStateOrNull(key = FeatureFlagsChanged)
    return remember(flag, snapshotState) {
        derivedStateOf {
            snapshotState.value
                ?.flagsByKey?.get(flag.flagKey)?.enabled
                ?: flag.default
        }
    }
}
