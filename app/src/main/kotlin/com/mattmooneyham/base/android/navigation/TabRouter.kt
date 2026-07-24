package com.mattmooneyham.base.android.navigation

import androidx.compose.runtime.mutableStateListOf

/**
 * One tab's back stack: a typed destination list driving that tab's
 * stack host, mirroring the iOS sibling's HomeRouter.path. An empty
 * stack means the tab is showing its root page. Snapshot-state
 * backed, so the composition recomposes on change while JVM specs
 * drive it as a plain class.
 */
class TabRouter<Destination : Any>(
    initialBackStack: List<Destination> = emptyList(),
) {

    private val mutableBackStack =
        mutableStateListOf<Destination>().apply {
            addAll(initialBackStack)
        }

    /** Pushed destinations, bottom to top; the root is implicit. */
    val backStack: List<Destination> get() = mutableBackStack

    val canPop: Boolean get() = mutableBackStack.isNotEmpty()

    fun push(destination: Destination) {
        mutableBackStack.add(destination)
    }

    /** Pops the top destination; false when already at the root. */
    fun pop(): Boolean {
        if (mutableBackStack.isEmpty()) return false
        mutableBackStack.removeAt(mutableBackStack.lastIndex)
        return true
    }

    /** Clears the stack back to the tab's root page. */
    fun popToRoot() {
        mutableBackStack.clear()
    }

    /** Replaces the whole stack (deep links state the WHOLE intent). */
    fun replaceAll(vararg destinations: Destination) {
        mutableBackStack.clear()
        mutableBackStack.addAll(destinations)
    }
}
