package com.mattmooneyham.base.android.designSystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fixed component dimensions: icon sizes, tiles, slots, and the tab
 * bar's metric block. Spacing belongs in [AppSpacing] and corners in
 * [AppShapes]; this file holds the SIZES of things.
 */
object AppDimens {

    /** Inline status icons (the error row's warning glyph). */
    val iconSmall: Dp = 14.dp

    /** Icons inside tiles and chips. */
    val iconMedium: Dp = 18.dp

    /** Standalone action icons (refresh). */
    val iconLarge: Dp = 20.dp

    /** Square icon tile in rows and card headers. */
    val iconTileSize: Dp = 34.dp

    /** The circular action slot that hosts an icon OR its spinner,
     * sized so the swap never shifts layout. */
    val actionSlotSize: Dp = 40.dp

    /** Start inset that aligns a divider with a row's text column
     * (past the icon tile and its gap). */
    val dividerInset: Dp = 68.dp

    /** Hairline separators (the tab bar's top divider). */
    val hairline: Dp = 0.5.dp

    /** The iOS-style tab bar's metric block. */
    object TabBar {
        val pillWidth: Dp = 56.dp
        val pillHeight: Dp = 30.dp
        val iconSize: Dp = 18.dp
        val labelSpacing: Dp = 4.dp
    }
}
