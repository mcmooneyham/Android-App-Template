package com.mattmooneyham.base.android.views.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            start = 8.dp,
            top = 24.dp,
            bottom = 8.dp,
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
