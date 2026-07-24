package com.mattmooneyham.base.android.designSystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The type scale, fed to MaterialTheme by BaseAppTheme so every
 * `MaterialTheme.typography.x` read in the app resolves here. The
 * roles the app uses are pinned EXPLICITLY (at Material 3's default
 * metrics today, so adopting this file changed nothing visually);
 * edit a size here and every screen follows.
 */

/** Swap point for a brand font: replace this one value with a
 * FontFamily built from font resources and the whole app changes. */
val AppFontFamily: FontFamily = FontFamily.Default

/** Letter spacing for the all-caps section headers, which sit outside
 * the Material roles. */
val SectionHeaderLetterSpacing = 1.2.sp

val AppTypography = Typography(
    // Page titles ("Home", "Settings", "Joke details"); call sites
    // add FontWeight.Bold.
    headlineLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    // Card and row titles.
    titleMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    // Primary content (joke setups, dialog bodies).
    bodyLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    // Secondary content (punchlines, supporting rows).
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    // Fine print (card subtitles, inline errors).
    bodySmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    // The smallest labels (tab bar, section headers, chips).
    labelSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)
