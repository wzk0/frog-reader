package com.example.frogreader.ui.onboarding

import com.example.frogreader.ui.components.SpaceBeforeHeading
import com.example.frogreader.ui.components.SpaceAfterHeading
import com.example.frogreader.ui.settings.SettingsRows
import com.example.frogreader.ui.settings.SettingsCardRow
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.R
import com.example.frogreader.ui.components.FrogSegmentedControl
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.StartupDestination
import com.example.frogreader.data.effectiveTheme
import com.example.frogreader.ui.reader.readerColors
import com.example.frogreader.ui.settings.ReaderChromeStylePicker
import com.example.frogreader.ui.settings.SettingsControlHeader
import com.example.frogreader.ui.settings.SettingsSectionLabel
import com.example.frogreader.ui.settings.ThemeAutomaticControls
import com.example.frogreader.ui.settings.ThemePreviewPicker

/** First-run preferences use the same specimens and palettes as Settings. */
@Composable
fun WelcomeScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onContinue: () -> Unit,
    isSaving: Boolean = false,
    errorMessage: String? = null,
) {
    val theme = settings.effectiveTheme(isSystemInDarkTheme())
    val scheme = MaterialTheme.colorScheme
    val colors = remember(theme, scheme) { readerColors(theme, scheme) }
    Surface(Modifier.fillMaxSize(), color = scheme.surface) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            // One scroll container keeps every choice and Continue reachable
            // on short screens, in landscape, and with enlarged text.
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(SpaceBeforeHeading),
            ) {
                Column {
                    WelcomeHeader()
                    SettingsRows {
                        WelcomeCard(first = true, last = false) {
                            SettingsControlHeader(
                                Icons.Rounded.ViewAgenda,
                                stringResource(R.string.welcome_controls_title),
                                stringResource(R.string.welcome_controls_subtitle),
                            )
                            ReaderChromeStylePicker(
                                selected = settings.readerChromeStyle,
                                colors = colors,
                                hapticsEnabled = settings.haptics,
                                enabled = !isSaving,
                                onSelected = { if (!isSaving) onSettingsChange(settings.copy(readerChromeStyle = it)) },
                            )
                        }
                        WelcomeCard(first = false, last = false) {
                            SettingsControlHeader(
                                Icons.AutoMirrored.Rounded.Login,
                                stringResource(R.string.settings_startup_title),
                                stringResource(R.string.welcome_startup_subtitle),
                            )
                            WelcomeStartupPicker(
                                selected = settings.startupDestination,
                                enabled = !isSaving,
                                onSelected = { onSettingsChange(settings.copy(startupDestination = it)) },
                            )
                        }
                        WelcomeCard(first = false, last = true) {
                            SettingsControlHeader(
                                Icons.Rounded.Palette,
                                stringResource(R.string.welcome_theme_title),
                                stringResource(R.string.welcome_theme_subtitle),
                            )
                            ThemePreviewPicker(
                                settings = settings,
                                effectiveTheme = theme,
                                enabled = !isSaving,
                                onSelect = {
                                    if (!isSaving) onSettingsChange(settings.copy(theme = it, followSystemTheme = false))
                                },
                            )
                        }
                    }
                }
                Column {
                    SettingsSectionLabel(stringResource(R.string.settings_theme_automatic))
                    ThemeAutomaticControls(
                        settings = settings,
                        enabled = !isSaving,
                        onUpdate = { transform ->
                            if (!isSaving) onSettingsChange(transform(settings))
                        },
                    )
                }
                errorMessage?.let {
                    Text(
                        it,
                        color = scheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Button(
                    onClick = onContinue,
                    enabled = !isSaving,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(
                        stringResource(if (isSaving) R.string.welcome_saving else R.string.welcome_continue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeHeader() {
    Column(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = SpaceAfterHeading),
        verticalArrangement = Arrangement.spacedBy(SpaceAfterHeading),
    ) {
        Text(
            stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 34.sp,
                lineHeight = 39.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.welcome_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun WelcomeCard(first: Boolean, last: Boolean, content: @Composable ColumnScope.() -> Unit) {
    SettingsCardRow(first = first, last = last) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(SpaceAfterHeading),
            content = content,
        )
    }
}

/** Flexible-height segments preserve both labels at large font sizes. */
@Composable
private fun WelcomeStartupPicker(
    selected: StartupDestination,
    enabled: Boolean,
    onSelected: (StartupDestination) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    FrogSegmentedControl(
        count = StartupDestination.entries.size,
        selectedIndex = StartupDestination.entries.indexOf(selected),
        enabled = enabled,
        containerColor = colors.surfaceContainer,
        indicatorColor = colors.primary,
        contentColor = colors.onSurface,
        selectedContentColor = colors.onPrimary,
        gap = 4.dp,
        containerRadius = 28.dp,
        indicatorRadius = 24.dp,
        minItemHeight = 48.dp,
        onSelect = { onSelected(StartupDestination.entries[it]) },
    ) { index, _, foreground ->
        val destination = StartupDestination.entries[index]
        Text(
            stringResource(
                if (destination == StartupDestination.LIBRARY) R.string.nav_library
                else R.string.settings_startup_last_book,
            ),
            color = foreground,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}
