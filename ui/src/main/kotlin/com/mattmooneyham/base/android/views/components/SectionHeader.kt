package com.mattmooneyham.base.android.views.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mattmooneyham.base.android.designSystem.AppSpacing
import com.mattmooneyham.base.android.designSystem.SectionHeaderLetterSpacing
import com.mattmooneyham.base.android.views.BaseAppTheme

/** Uppercase section label used to group cards on a settings screen. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = SectionHeaderLetterSpacing,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            start = AppSpacing.sm,
            top = 24.dp,
            bottom = AppSpacing.sm,
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun SectionHeaderPreview() {
    BaseAppTheme {
        SectionHeader(title = "Preferences")
    }
}
