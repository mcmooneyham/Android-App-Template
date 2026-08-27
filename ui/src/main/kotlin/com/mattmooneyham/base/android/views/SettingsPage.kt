package com.mattmooneyham.base.android.views

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.mattmooneyham.base.android.ui.R
import com.mattmooneyham.base.android.designSystem.AppDimens
import com.mattmooneyham.base.android.designSystem.AppSpacing
import com.mattmooneyham.base.android.managers.featureFlagManager.AppFlags
import com.mattmooneyham.base.android.managers.featureFlagManager.BooleanFlag
import com.mattmooneyham.base.android.managers.featureFlagManager.FeatureFlagsChanged
import com.mattmooneyham.base.android.managers.featureFlagManager.FlagSource
import com.mattmooneyham.base.android.managers.jokeManager.JokeAutoRetryOnReconnectFlag
import com.mattmooneyham.base.android.viewModels.SettingsViewModel
import com.mattmooneyham.base.android.views.components.FeatureFlagSheetContent
import com.mattmooneyham.base.android.views.components.FlagRowUiState
import com.mattmooneyham.base.android.views.components.SectionHeader
import com.mattmooneyham.base.android.views.components.SettingsGroupCard
import com.mattmooneyham.base.android.views.components.SettingsRow
import com.mattmooneyham.base.android.constants.BrandColors
import java.io.File
import kotlinx.coroutines.launch

/**
 * Settings tab: preference and log maintenance backed by the app core
 * managers, plus app version info. The share sheet for exporting logs is
 * fired here (UI concern); the log contents come from the LogManager.
 * Debug builds append a Debug section whose "Feature flags" row opens
 * a modal sheet listing every declared flag with a live tri-state
 * override control (see FeatureFlagSheetContent).
 */
@Composable
fun SettingsPage(settingsViewModel: SettingsViewModel) {
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()

    // Share-sheet copy is resolved IN COMPOSITION so a configuration
    // change (locale) recomposes fresh strings; the click callback
    // below captures the resolved values instead of querying the
    // context, which lint forbids (LocalContextGetResourceValueCall:
    // LocalContext reads are never invalidated by config changes).
    val logsExportSubject = stringResource(R.string.logs_export_subject)
    val logsExportNoneMessage = stringResource(R.string.logs_export_none)
    val logsExportChooserTitle =
        stringResource(R.string.logs_export_chooser_title)

    // The Debug section exists only in debug builds; release passes no
    // rows and renders no section (overrides are also locked at the
    // manager level, so this gate is cosmetic, not the enforcement).
    val flagSnapshot by eventStateOrNull(key = FeatureFlagsChanged)
    val debugFlagRows = if (settingsViewModel.isDebugBuild) {
        AppFlags.all.map { flag ->
            val resolvedFlag = flagSnapshot?.flagsByKey?.get(flag.flagKey)
            FlagRowUiState(
                flag = flag,
                resolvedEnabled = resolvedFlag?.enabled ?: flag.default,
                source = resolvedFlag?.source ?: FlagSource.DEFAULT,
            )
        }
    } else {
        emptyList()
    }

    SettingsContent(
        appVersionName = settingsViewModel.appVersionName,
        buildTimestampSeconds = settingsViewModel.buildTimestampSeconds,
        debugFlagRows = debugFlagRows,
        onFlagOverrideSelected = settingsViewModel::setFlagOverride,
        onClearWelcomeFlag = settingsViewModel::clearWelcomeFlag,
        onExportLogs = {
            exportScope.launch {
                val exportPath =
                    settingsViewModel.writeLogExportSnapshot()
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_SUBJECT, logsExportSubject)
                    if (exportPath != null) {
                        // The zip of the FULL history rides a content
                        // URI; a text extra cannot carry binary and can
                        // blow the ~1 MB Binder transaction cap.
                        type = "application/zip"
                        val exportUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.logexport",
                            File(exportPath),
                        )
                        putExtra(Intent.EXTRA_STREAM, exportUri)
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } else {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            logsExportNoneMessage,
                        )
                    }
                }
                context.startActivity(
                    Intent.createChooser(
                        sendIntent,
                        logsExportChooserTitle,
                    ),
                )
            }
        },
        onClearLogs = settingsViewModel::clearLogs,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    appVersionName: String,
    buildTimestampSeconds: Long,
    debugFlagRows: List<FlagRowUiState>,
    onFlagOverrideSelected: (BooleanFlag, Boolean?) -> Unit,
    onClearWelcomeFlag: () -> Unit,
    onExportLogs: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Clearing logs is destructive, so the row asks before acting.
    var isClearLogsDialogVisible by rememberSaveable { mutableStateOf(false) }

    // The feature-flags list lives in a modal sheet behind one Debug
    // row; the rows keep resolving live while the sheet is open.
    var isFlagSheetVisible by rememberSaveable { mutableStateOf(false) }

    if (isFlagSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { isFlagSheetVisible = false },
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
            ),
            // The page background, so the grouped cards inside render
            // exactly as they do on the Settings page itself.
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            FeatureFlagSheetContent(
                flagRows = debugFlagRows,
                onOverrideSelected = onFlagOverrideSelected,
            )
        }
    }

    if (isClearLogsDialogVisible) {
        AlertDialog(
            onDismissRequest = { isClearLogsDialogVisible = false },
            title = {
                Text(
                    text = stringResource(
                        R.string.clear_logs_dialog_title,
                    ),
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.clear_logs_dialog_body),
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
                        text = stringResource(
                            R.string.clear_logs_dialog_confirm,
                        ),
                        color = Color(BrandColors.DANGER),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { isClearLogsDialogVisible = false }) {
                    Text(
                        text = stringResource(
                            R.string.clear_logs_dialog_cancel,
                        ),
                    )
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.screenEdge),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(
                top = AppSpacing.md,
                bottom = AppSpacing.xs,
            ),
        )

        SectionHeader(
            title = stringResource(R.string.settings_section_preferences),
        )
        SettingsGroupCard {
            SettingsRow(
                icon = Icons.Filled.Refresh,
                title = stringResource(
                    R.string.settings_clear_welcome_title,
                ),
                supportingText = stringResource(
                    R.string.settings_clear_welcome_supporting,
                ),
                onClick = onClearWelcomeFlag,
            )
        }

        SectionHeader(
            title = stringResource(R.string.settings_section_logs),
        )
        SettingsGroupCard {
            SettingsRow(
                icon = Icons.Filled.Share,
                title = stringResource(R.string.settings_export_logs_title),
                supportingText = stringResource(
                    R.string.settings_export_logs_supporting,
                ),
                onClick = onExportLogs,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = AppDimens.dividerInset),
            )
            SettingsRow(
                icon = Icons.Filled.Delete,
                title = stringResource(R.string.settings_clear_logs_title),
                supportingText = stringResource(
                    R.string.settings_clear_logs_supporting,
                ),
                isDestructive = true,
                onClick = { isClearLogsDialogVisible = true },
            )
        }

        SectionHeader(
            title = stringResource(R.string.settings_section_about),
        )
        SettingsGroupCard {
            SettingsRow(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.settings_version_title),
                trailingValue = appVersionName,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = AppDimens.dividerInset),
            )
            SettingsRow(
                icon = Icons.Filled.Build,
                title = stringResource(R.string.settings_build_title),
                trailingValue = buildTimestampSeconds.toString(),
            )
        }

        if (debugFlagRows.isNotEmpty()) {
            SectionHeader(
                title = stringResource(R.string.settings_section_debug),
            )
            SettingsGroupCard {
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = stringResource(
                        R.string.settings_feature_flags_title,
                    ),
                    supportingText = stringResource(
                        R.string.settings_feature_flags_supporting,
                    ),
                    onClick = { isFlagSheetVisible = true },
                )
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.pageBottom))
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsContentPreview() {
    BaseAppTheme {
        SettingsContent(
            appVersionName = "1.0",
            buildTimestampSeconds = 1_784_660_000L,
            debugFlagRows = listOf(
                FlagRowUiState(
                    flag = JokeAutoRetryOnReconnectFlag,
                    resolvedEnabled = true,
                    source = FlagSource.DEFAULT,
                ),
            ),
            onFlagOverrideSelected = { _, _ -> },
            onClearWelcomeFlag = {},
            onExportLogs = {},
            onClearLogs = {},
        )
    }
}
