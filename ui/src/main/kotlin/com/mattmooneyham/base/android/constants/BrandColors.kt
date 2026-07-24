package com.mattmooneyham.base.android.constants

/**
 * Brand palette, expressed as ARGB values and wrapped with Compose's
 * Color(Long) at the point of use.
 *
 * Source of truth: the "PM Color Reference Guide" (Figma Scaffold WEB
 * LIBRARY chips are canonical). These are the SEMANTIC tokens; reference
 * them in product UI rather than raw hex values.
 */
object BrandColors {

    // Brand & interactive
    val BRAND = 0xFF3F48E9            // Primary actions, buttons (blue 600)
    val BRAND_HOVER = 0xFF363DC6      // Pressed/hover states (blue 700)
    val BRAND_DISABLED = 0xFFACB0F6   // Disabled primary, selection (blue 300)
    val MAGIC = 0xFF8F00FF            // Magic / AI actions
    val MAGIC_LIGHT = 0xFFFBF5FF
    val MAGIC_DARK = 0xFF7200CC

    // States & messaging
    val SUCCESS = 0xFF33AA5B          // Success, positive states
    val WARNING = 0xFFEFA513          // Non-blocking warnings
    val DANGER = 0xFFD93F3F           // Errors, destructive actions

    // Dark-mode surfaces. Not part of the reference guide (it defines no
    // dark palette); derived for a consistent dark mode.
    // Matches iOS secondarySystemGroupedBackground (dark), the familiar
    // grouped-card tone.
    val SURFACE_DARK = 0xFF1C1C1E     // Cards / grouped surfaces on dark
    val OUTLINE_DARK = 0xFF3A3A3A     // Dividers on dark

    // Neutral, surface & text
    val BLACK = 0xFF1F1F1F            // Header background, primary text
    val TEXT = 0xFF757575             // Secondary text (gray 700)
    val GRAY_600 = 0xFF989898         // Graphical elements and text
    val GRAY_500 = 0xFFBBBBBB         // Disabled state
    val GRAY_300 = 0xFFDEDEDE         // Input borders
    val GRAY_200 = 0xFFEEEEEE         // Dividers
    val BG_LIGHT = 0xFFF7F7F8         // Backgrounds (gray 100)
    val WHITE = 0xFFFFFFFF            // Card / menu surfaces
}
