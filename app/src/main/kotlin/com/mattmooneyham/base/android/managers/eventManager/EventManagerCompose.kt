package com.mattmooneyham.base.android.managers.eventManager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The event bus for the composition. Provide it at the root of the app
 * (e.g. from the activity) so any composable can observe events directly
 * without going through a viewmodel:
 *
 * ```
 * CompositionLocalProvider(LocalEventManager provides eventManager) {...}
 * ```
 */
val LocalEventManager = staticCompositionLocalOf<EventManager> {
    error("LocalEventManager is not provided above this composable")
}

/**
 * Observes [key] as Compose state. The value starts at the key's cached
 * payload (or [initialValue] if it has not fired) and updates on every
 * matching event. The payload type comes from the key itself.
 */
@Composable
inline fun <reified PayloadType : Any> eventState(
    key: StateKey<PayloadType>,
    initialValue: PayloadType,
): State<PayloadType> {
    val eventManager = LocalEventManager.current
    val eventFlow = remember(eventManager, key) {
        eventManager.typedEventsOf<PayloadType>(key)
    }
    return eventFlow.collectAsState(
        initial = eventManager.currentValue(key) as? PayloadType
            ?: initialValue,
    )
}

/**
 * Like [eventState] but null until the key fires for the first time,
 * for "still loading" UI states.
 */
@Composable
inline fun <reified PayloadType : Any> eventStateOrNull(
    key: StateKey<PayloadType>,
): State<PayloadType?> {
    val eventManager = LocalEventManager.current
    val eventFlow = remember(eventManager, key) {
        eventManager.typedEventsOf<PayloadType>(key)
    }
    return eventFlow.collectAsState(
        initial = eventManager.currentValue(key) as? PayloadType,
    )
}
