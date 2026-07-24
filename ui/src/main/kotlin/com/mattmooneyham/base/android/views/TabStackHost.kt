package com.mattmooneyham.base.android.views

import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.tooling.preview.Preview
import com.mattmooneyham.base.android.animations.AppAnimations
import com.mattmooneyham.base.android.navigation.TabRouter

private const val TAB_ROOT_STATE_KEY = "tab.root"

/**
 * Renders one tab's typed stack: the top destination, or the root
 * page when nothing is pushed. Covered and popped entries keep or
 * drop their rememberSaveable state via the SaveableStateHolder;
 * push/pop reuses the shared content-swap motion (directional
 * push-vs-pop slides and predictive-back previews are deliberately
 * deferred to the Navigation 3 threshold in ARCHITECTURE-SCALING.md).
 */
@Composable
fun <Destination : Any> TabStackHost(
    tabRouter: TabRouter<Destination>,
    rootContent: @Composable () -> Unit,
    destinationContent: @Composable (Destination) -> Unit,
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    val backStack = tabRouter.backStack

    // Saved-state hygiene: a popped screen's saveable state must not
    // resurrect on the next push. removeState on a key that is still
    // composed (the exit animation) marks it do-not-save, so calling
    // it here, inside SideEffect, is exactly right. Keys are the
    // destinations' toString, which is stable ONLY for data
    // classes/data objects (a plain object's identity-hash toString
    // changes across process death and would silently skip restore);
    // a guard test enforces the data requirement. Identical
    // simultaneous pushes would share state, fine until a flow does
    // that (the scaling guide's Navigation 3 row covers the fix).
    val liveEntryKeys = backStack.map { destination ->
        destination.toString()
    }
    val previousEntryKeys = remember { mutableListOf<String>() }
    SideEffect {
        (previousEntryKeys - liveEntryKeys.toSet())
            .forEach(saveableStateHolder::removeState)
        previousEntryKeys.clear()
        previousEntryKeys.addAll(liveEntryKeys)
    }

    AnimatedContent(
        targetState = backStack.lastOrNull(),
        transitionSpec = AppAnimations.contentSwapTransform(),
        label = "tabStackTop",
    ) { topDestination ->
        // Each lambda captures ITS destination, so an exiting screen
        // keeps rendering while the pop animation runs even though
        // the router's stack no longer contains it.
        if (topDestination == null) {
            saveableStateHolder.SaveableStateProvider(
                key = TAB_ROOT_STATE_KEY,
            ) {
                rootContent()
            }
        } else {
            saveableStateHolder.SaveableStateProvider(
                key = topDestination.toString(),
            ) {
                destinationContent(topDestination)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TabStackHostPreview() {
    BaseAppTheme {
        TabStackHost(
            tabRouter = remember { TabRouter<String>() },
            rootContent = { Text(text = "Root page content") },
        ) { destination ->
            Text(text = "Pushed: $destination")
        }
    }
}
