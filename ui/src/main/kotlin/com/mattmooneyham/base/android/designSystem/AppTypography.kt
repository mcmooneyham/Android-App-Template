package com.mattmooneyham.base.android.designSystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The type scale, fed to MaterialTheme by BaseAppTheme so every
 * `MaterialTheme.typography.x` read in the app resolves here. EVERY
 * Material role is pinned explicitly (at Material 3's default metrics
 * today, so adopting this file changed nothing visually): a role a
 * screen starts using tomorrow is already covered, and no role can
 * silently fall back to a default that ignores the app's font.
 */

/** Swap point for a brand font: replace this one value with a
 * FontFamily built from font resources and the whole app changes. */
val AppFontFamily: FontFamily = FontFamily.Default

/** Letter spacing for the all-caps section headers, which sit outside
 * the Material roles. */
val SectionHeaderLetterSpacing = 1.2.sp

private fun appTextStyle(
    fontSize: Int,
    lineHeight: Int,
    fontWeight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
): TextStyle = TextStyle(
    fontFamily = AppFontFamily,
    fontWeight = fontWeight,
    fontSize = fontSize.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

val AppTypography = Typography(
    displayLarge = appTextStyle(57, 64, letterSpacing = -0.25),
    displayMedium = appTextStyle(45, 52),
    displaySmall = appTextStyle(36, 44),
    // Page titles ("Home", "Settings"); call sites add Bold.
    headlineLarge = appTextStyle(32, 40),
    // The hero card's headline.
    headlineMedium = appTextStyle(28, 36),
    headlineSmall = appTextStyle(24, 32),
    // Sheet titles (the feature-flag sheet).
    titleLarge = appTextStyle(22, 28),
    // Card and row titles.
    titleMedium = appTextStyle(16, 24, FontWeight.Medium, 0.15),
    titleSmall = appTextStyle(14, 20, FontWeight.Medium, 0.1),
    // Primary content (joke setups, dialog bodies).
    bodyLarge = appTextStyle(16, 24, letterSpacing = 0.5),
    // Secondary content (punchlines, supporting rows).
    bodyMedium = appTextStyle(14, 20, letterSpacing = 0.25),
    // Fine print (card subtitles, inline errors).
    bodySmall = appTextStyle(12, 16, letterSpacing = 0.4),
    // Chips and hero CTA.
    labelLarge = appTextStyle(14, 20, FontWeight.Medium, 0.1),
    // Section headers and eyebrows.
    labelMedium = appTextStyle(12, 16, FontWeight.Medium, 0.5),
    // The smallest labels (tab bar).
    labelSmall = appTextStyle(11, 16, FontWeight.Medium, 0.5),
)
