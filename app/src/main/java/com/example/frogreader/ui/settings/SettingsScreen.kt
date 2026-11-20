package com.example.frogreader.ui.settings

import com.example.frogreader.ui.components.AppSubpageNavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frogreader.R
import com.example.frogreader.data.AppLockDelay
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.BackupFrequency
import com.example.frogreader.data.effectiveTheme
import com.example.frogreader.ui.components.ConnectedCardGap
import com.example.frogreader.ui.components.FrogSnackbarHost
import com.example.frogreader.ui.components.connectedCard
import com.example.frogreader.ui.components.connectedCardShape
import com.example.frogreader.ui.theme.displayNameRes
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class SettingsDestination {
    ROOT,
    THEME,
    READING,
    OPDS,
    PRIVACY,
    BACKUP,
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
    backupViewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory),
) {
    val settings = viewModel.appSettings.collectAsStateWithLifecycle()
    val backupFrequency = backupViewModel.frequency.collectAsStateWithLifecycle()
    val lastBackupAt = backupViewModel.lastBackupAt.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val externalAppSaveFailed = stringResource(R.string.external_text_save_failed)
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val navController = rememberNavController()

    Box(Modifier.fillMaxSize()) {
        AppSubpageNavHost(navController, SettingsDestination.ROOT.name) {
            composable(SettingsDestination.ROOT.name) {
                SettingsEntryPage(
                    settings = settings.value,
                    backupFrequency = backupFrequency.value,
                    lastBackupAt = lastBackupAt.value,
                    onBack = onBack,
                    onOpenTheme = { navController.navigate(SettingsDestination.THEME.name) { launchSingleTop = true } },
                    onOpenReading = { navController.navigate(SettingsDestination.READING.name) { launchSingleTop = true } },
                    onOpenOpds = { navController.navigate(SettingsDestination.OPDS.name) { launchSingleTop = true } },
                    onOpenPrivacy = { navController.navigate(SettingsDestination.PRIVACY.name) { launchSingleTop = true } },
                    onOpenBackup = { navController.navigate(SettingsDestination.BACKUP.name) { launchSingleTop = true } },
                    onOpenAbout = { showAbout = true },
                )
            }

            composable(SettingsDestination.THEME.name) {
                ThemeSettingsPage(
                    settings = settings.value,
                    onUpdate = viewModel::update,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(SettingsDestination.READING.name) {
                ReadingSettingsPage(
                    settings = settings.value,
                    onUpdate = viewModel::update,
                    saveExternalApp = viewModel::saveExternalApp,
                    onExternalAppSaveFailed = {
                        scope.launch { snackbarHostState.showSnackbar(externalAppSaveFailed) }
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(SettingsDestination.OPDS.name) {
                OpdsSettingsPage { navController.popBackStack() }
            }

            composable(SettingsDestination.PRIVACY.name) {
                PrivacySettingsPage(
                    settings = settings.value,
                    onUpdate = viewModel::update,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(SettingsDestination.BACKUP.name) {
                BackupSettingsPage(
                    settings = settings.value,
                    onUpdate = viewModel::update,
                    viewModel = backupViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        FrogSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        )
    }

    if (showAbout) {
        AboutDialog(
            onDismiss = { showAbout = false },
            onOpenFailed = { message ->
                showAbout = false
                scope.launch { snackbarHostState.showSnackbar(message) }
            },
        )
    }
}

@Composable
internal fun SettingsEntryPage(
    settings: AppSettings,
    backupFrequency: BackupFrequency,
    lastBackupAt: Long?,
    onBack: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenOpds: () -> Unit = {},
) {
    val systemDark = isSystemInDarkTheme()
    val effectiveTheme = settings.effectiveTheme(systemDark)
    val scheme = MaterialTheme.colorScheme
    val versionName = installedVersionName()

    SettingsPageScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ConnectedCardGap)) {
            SettingsNavigationRow(
                modifier = Modifier.connectedCard(scheme, connectedCardShape(last = false)),
                icon = Icons.Rounded.Palette,
                title = stringResource(R.string.settings_theme_title),
                subtitle = if (settings.followSystemTheme) {
                    stringResource(
                        R.string.settings_theme_summary_system,
                        stringResource(effectiveTheme.displayNameRes),
                    )
                } else {
                    stringResource(
                        R.string.settings_theme_summary_fixed,
                        stringResource(settings.theme.displayNameRes),
                    )
                },
                onClick = onOpenTheme,
                trailing = { ThemeSwatch() },
            )
            SettingsNavigationRow(
                modifier = Modifier.connectedCard(scheme, connectedCardShape(first = false, last = false)),
                icon = Icons.AutoMirrored.Rounded.MenuBook,
                title = stringResource(R.string.settings_reading_title),
                subtitle = readingSummary(settings),
                onClick = onOpenReading,
            )
            SettingsNavigationRow(
                modifier = Modifier.connectedCard(scheme, connectedCardShape(first = false)),
                icon = Icons.Outlined.Language,
                title = stringResource(R.string.opds_title),
                subtitle = stringResource(R.string.opds_summary),
                onClick = onOpenOpds,
            )
        }

        Spacer(Modifier.height(24.dp))

        Column(verticalArrangement = Arrangement.spacedBy(ConnectedCardGap)) {
            SettingsNavigationRow(
                modifier = Modifier.connectedCard(scheme, connectedCardShape(last = false)),
                icon = Icons.Rounded.Fingerprint,
                title = stringResource(R.string.settings_app_lock_title),
                subtitle = lockSummary(settings),
                onClick = onOpenPrivacy,
            )
            SettingsNavigationRow(
                modifier = Modifier.connectedCard(scheme, connectedCardShape(first = false)),
                icon = Icons.Rounded.CloudUpload,
                title = stringResource(R.string.settings_backup_title),
                subtitle = backupSummary(lastBackupAt, backupFrequency),
                onClick = onOpenBackup,
            )
        }

        Spacer(Modifier.height(24.dp))

        SettingsNavigationRow(
            modifier = Modifier.connectedCard(scheme, connectedCardShape()),
            icon = Icons.Rounded.Info,
            title = stringResource(R.string.app_name),
            subtitle = stringResource(R.string.settings_about_summary, versionName),
            onClick = onOpenAbout,
        )
    }
}

@Composable
private fun readingSummary(settings: AppSettings): String {
    val enabled = buildList {
        if (settings.keepScreenOn) add(stringResource(R.string.settings_summary_screen))
        if (settings.volumeKeyPaging) add(stringResource(R.string.settings_summary_volume))
        if (settings.haptics) add(stringResource(R.string.settings_summary_vibration))
    }
    return enabled.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ?: stringResource(R.string.settings_summary_helpers_off)
}

@Composable
private fun lockSummary(settings: AppSettings): String {
    if (!settings.appLock) return stringResource(R.string.settings_lock_off)
    val delay = when (settings.appLockDelay) {
        AppLockDelay.IMMEDIATE -> stringResource(R.string.settings_lock_delay_immediate)
        AppLockDelay.ONE_MINUTE -> stringResource(R.string.settings_lock_delay_one_minute)
        AppLockDelay.FIFTEEN_MINUTES -> stringResource(R.string.settings_lock_delay_fifteen_minutes)
    }
    return stringResource(R.string.settings_lock_on_summary, delay)
}

@Composable
private fun backupSummary(lastBackupAt: Long?, frequency: BackupFrequency): String {
    val schedule = when (frequency) {
        BackupFrequency.OFF -> stringResource(R.string.backup_schedule_off)
        BackupFrequency.DAILY -> stringResource(R.string.backup_schedule_daily)
        BackupFrequency.WEEKLY -> stringResource(R.string.backup_schedule_weekly)
    }
    return lastBackupAt?.let {
        stringResource(
            R.string.settings_backup_summary,
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)),
            schedule.lowercase(),
        )
    } ?: stringResource(R.string.settings_backup_summary_never, schedule.lowercase())
}

@Composable
private fun ThemeSwatch() {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(scheme.surface),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(scheme.primary),
        )
    }
}

@Composable
private fun installedVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
}
