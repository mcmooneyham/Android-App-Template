package com.mattmooneyham.base.android.designSystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale: every padding, margin, and gap in the app comes
 * from here, so rhythm changes are one edit, not a screen-by-screen
 * hunt. The base scale is a 4-point grid (with a 2 dp step for
 * hairline gaps); the semantic aliases below it name the RECURRING
 * layout decisions, and call sites should prefer the alias that says
 * what the space is for.
 */
object AppSpacing {

    // The scale.
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp

    // Content gaps inside components (between an icon and its text,
    // between stacked text rows, between a card header and its body).
    val contentGapSmall: Dp = 6.dp
    val contentGapMedium: Dp = 10.dp
    val contentGapLarge: Dp = 14.dp

    // Semantic aliases: the recurring layout decisions.
    /** Horizontal inset of every page from the screen edge. */
    val screenEdge: Dp = xl

    /** Inner padding of cards and grouped surfaces. */
    val cardPadding: Dp = xl

    /** Vertical rhythm between a page's sections. */
    val sectionGap: Dp = lg

    /** Breathing room under a page's last element. */
    val pageBottom: Dp = xxl
}
