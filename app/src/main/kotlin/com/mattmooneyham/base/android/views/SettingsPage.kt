package com.mattmooneyham.base.android.views

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mattmooneyham.base.android.viewModels.SettingsViewModel
import com.mattmooneyham.base.android.views.components.SectionHeader
import com.mattmooneyham.base.android.views.components.SettingsGroupCard
import com.mattmooneyham.base.android.views.components.SettingsRow
import com.mattmooneyham.base.android.constants.BrandColors
import kotlinx.coroutines.launch

/**
 * Settings tab: preference and log maintenance backed by the app core
 * managers, plus app version info. The share sheet for exporting logs is
 * fired here (UI concern); the log contents come from the LogManager.
 */
@Composable
fun SettingsPage(settingsViewModel: SettingsViewModel) {
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    SettingsContent(
        appVersionName = settingsViewModel.appVersionName,
        buildTimestampSeconds = settingsViewModel.buildTimestampSeconds,
        onClearWelcomeFlag = settingsViewModel::clearWelcomeFlag,
        onExportLogs = {
            exportScope.launch {
                val logContents = settingsViewModel.readLogsForExport()
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Base App logs")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        logContents.ifEmpty { "No logs recorded yet." },
                    )
                }
                context.startActivity(
                    Intent.createChooser(sendIntent, "Export logs"),
                )
            }
        },
        onClearLogs = settingsViewModel::clearLogs,
    )
}

@Composable
private fun SettingsContent(
    appVersionName: String,
    buildTimestampSeconds: Long,
    onClearWelcomeFlag: () -> Unit,
    onExportLogs: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Clearing logs is destructive, so the row asks before acting.
    var isClearLogsDialogVisible by rememberSaveable { mutableStateOf(false) }

    if (isClearLogsDialogVisible) {
        AlertDialog(
            onDismissRequest = { isClearLogsDialogVisible = false },
            title = { Text(text = "Clear logs?") },
            text = {
                Text(
                    text = "This deletes the app's log file and cannot " +
                        "be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isClearLogsDialogVisible = false
                        onClearLogs()
                    },
                ) {
                    Text(
                        text = "Clear logs",
                        color = Color(BrandColors.DANGER),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { isClearLogsDialogVisible = false }) {
                    Text(text = "Cancel")
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )

        SectionHeader(title = "Preferences")
        SettingsGroupCard {
            SettingsRow(
                icon = Icons.Filled.Refresh,
                title = "Clear welcome flag",
                supportingText = "Home shows \"First launch\" until restart",
                onClick = onClearWelcomeFlag,
            )
        }

        SectionHeader(title = "Logs")
        SettingsGroupCard {
            SettingsRow(
                icon = Icons.Filled.Share,
                title = "Export logs",
                supportingText = "Share the app's log file contents",
                onClick = onExportLogs,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 68.dp),
            )
            SettingsRow(
                icon = Icons.Filled.Delete,
                title = "Clear logs",
                supportingText = "Delete the app's log file",
                isDestructive = true,
                onClick = { isClearLogsDialogVisible = true },
            )
        }

        SectionHeader(title = "About")
        SettingsGroupCard {
            SettingsRow(
                icon = Icons.Filled.Info,
                title = "Version",
                trailingValue = appVersionName,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 68.dp),
            )
            SettingsRow(
                icon = Icons.Filled.Build,
                title = "Build",
                trailingValue = buildTimestampSeconds.toString(),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsContentPreview() {
    BaseAppTheme {
        SettingsContent(
            appVersionName = "1.0",
            buildTimestampSeconds = 1_784_660_000L,
            onClearWelcomeFlag = {},
            onExportLogs = {},
            onClearLogs = {},
        )
    }
}
