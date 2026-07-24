package com.mattmooneyham.base.android.views.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.mattmooneyham.base.android.designSystem.AppDimens
import com.mattmooneyham.base.android.designSystem.AppShapes
import com.mattmooneyham.base.android.views.BaseAppTheme

/**
 * Rounded surface that groups related rows (settings sections, info
 * blocks), in the style of grouped platform settings screens.
 */
@Composable
fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = AppShapes.card,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = AppDimens.cardElevation,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsGroupCardPreview() {
    BaseAppTheme {
        SettingsGroupCard {
            Text(
                text = "Grouped content",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
