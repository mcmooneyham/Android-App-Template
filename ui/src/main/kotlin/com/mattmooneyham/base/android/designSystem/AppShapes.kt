package com.mattmooneyham.base.android.designSystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Corner language: the few radii the app speaks, named by role.
 * Fully round elements (status dots, selection pills, the refresh
 * slot) use Compose's CircleShape directly; everything with a finite
 * radius comes from here.
 */
object AppShapes {

    /** Standard cards and grouped surfaces. */
    val card = RoundedCornerShape(24.dp)

    /** The hero card: one step softer than a standard card. */
    val heroCard = RoundedCornerShape(28.dp)

    /** Square icon tiles inside rows and card headers. */
    val iconTile = RoundedCornerShape(10.dp)

    /** Skeleton placeholder lines (half their 14 dp height). */
    val skeletonLine = RoundedCornerShape(7.dp)

    /** Slim vertical accent bars (the punchline quote bar). */
    val accentBar = RoundedCornerShape(2.dp)
}
