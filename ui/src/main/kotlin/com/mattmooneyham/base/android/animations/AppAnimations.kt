package com.mattmooneyham.base.android.animations

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Central home for the app's animations and transitions, so motion stays
 * consistent across screens and components.
 */
object AppAnimations {

    const val TAB_TRANSITION_MILLIS = 300
    const val STATUS_TRANSITION_MILLIS = 300
    const val PRESS_FEEDBACK_MILLIS = 150
    const val SKELETON_PULSE_MILLIS = 900

    /** Alpha range the skeleton placeholders breathe between. */
    const val SKELETON_ALPHA_DIM = 0.35f
    const val SKELETON_ALPHA_BRIGHT = 0.75f

    /** Scale applied to pressable rows/buttons while pressed. */
    const val PRESSED_SCALE = 0.90f

    /** Color animation used by status-driven components (StatusChip). */
    val statusColorSpec: AnimationSpec<Color> =
        tween(durationMillis = STATUS_TRANSITION_MILLIS)

    /** Entrance for page content: fade in while rising slightly. */
    val contentEnterTransition: EnterTransition =
        fadeIn(tween(STATUS_TRANSITION_MILLIS)) +
            slideInVertically(tween(STATUS_TRANSITION_MILLIS)) { fullHeight ->
                fullHeight / 10
            }

    /**
     * Swap transform for small content changes (status text and similar):
     * the new content fades in while rising, the old one fades out.
     */
    fun <StateType> contentSwapTransform():
        AnimatedContentTransitionScope<StateType>.() -> ContentTransform = {
        (fadeIn(tween(STATUS_TRANSITION_MILLIS)) +
            slideInVertically(tween(STATUS_TRANSITION_MILLIS)) { height ->
                height / 6
            })
            .togetherWith(fadeOut(tween(STATUS_TRANSITION_MILLIS)))
    }

    /**
     * Keep-alive tab switching (see AppShellScaffold): pages stay
     * composed while opacity and a short shared-axis offset animate,
     * which reads far softer than a full-width slide.
     */
    val tabSwitchOffset: Dp = 40.dp

    val tabFadeSpec: AnimationSpec<Float> =
        tween(TAB_TRANSITION_MILLIS, easing = FastOutSlowInEasing)

    val tabOffsetSpec: AnimationSpec<Dp> =
        tween(TAB_TRANSITION_MILLIS, easing = FastOutSlowInEasing)

    /** Tint change of a tab bar item as selection moves on/off it. */
    val tabTintSpec: AnimationSpec<Color> =
        tween(TAB_TRANSITION_MILLIS, easing = FastOutSlowInEasing)
}

/**
 * Breathing alpha for skeleton placeholder shapes shown while content
 * loads.
 */
@Composable
fun skeletonPulseAlpha(): Float {
    val infinitePulse = rememberInfiniteTransition(label = "skeletonPulse")
    val pulseAlpha by infinitePulse.animateFloat(
        initialValue = AppAnimations.SKELETON_ALPHA_DIM,
        targetValue = AppAnimations.SKELETON_ALPHA_BRIGHT,
        animationSpec = infiniteRepeatable(
            animation = tween(AppAnimations.SKELETON_PULSE_MILLIS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonPulseAlpha",
    )
    return pulseAlpha
}

/**
 * Press feedback for tappable rows and buttons: scales the element down
 * while pressed. Pass the same [interactionSource] to the clickable so
 * presses are observed.
 */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) AppAnimations.PRESSED_SCALE else 1f,
        animationSpec = tween(AppAnimations.PRESS_FEEDBACK_MILLIS),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = pressScale
        scaleY = pressScale
    }
}
