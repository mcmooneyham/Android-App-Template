package com.mattmooneyham.base.android.views

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.mattmooneyham.base.android.constants.BrandColors

/**
 * App theme built from the shared [BrandColors] semantic tokens
 * (PM Color Reference Guide), so every screen renders the same brand
 * palette from one source of truth.
 */
@Composable
fun BaseAppTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(BrandColors.BRAND_DISABLED),
            onPrimary = Color(BrandColors.BLACK),
            secondary = Color(BrandColors.MAGIC),
            tertiary = Color(BrandColors.WARNING),
            error = Color(BrandColors.DANGER),
            background = Color(BrandColors.BLACK),
            surface = Color(BrandColors.SURFACE_DARK),
            onBackground = Color(BrandColors.GRAY_200),
            onSurface = Color(BrandColors.GRAY_200),
            onSurfaceVariant = Color(BrandColors.GRAY_500),
            outline = Color(BrandColors.TEXT),
            outlineVariant = Color(BrandColors.OUTLINE_DARK),
        )
    } else {
        lightColorScheme(
            primary = Color(BrandColors.BRAND),
            onPrimary = Color(BrandColors.WHITE),
            secondary = Color(BrandColors.MAGIC),
            tertiary = Color(BrandColors.WARNING),
            error = Color(BrandColors.DANGER),
            background = Color(BrandColors.BG_LIGHT),
            surface = Color(BrandColors.WHITE),
            onBackground = Color(BrandColors.BLACK),
            onSurface = Color(BrandColors.BLACK),
            onSurfaceVariant = Color(BrandColors.TEXT),
            outline = Color(BrandColors.GRAY_300),
            outlineVariant = Color(BrandColors.GRAY_200),
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

@Preview(showBackground = true)
@Composable
private fun BaseAppThemePreview() {
    BaseAppTheme {
        Text(text = "Base App themed text")
    }
}
