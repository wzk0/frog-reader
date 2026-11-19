package com.example.frogreader.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.AppTheme
import com.example.frogreader.ui.components.SpaceBeforeHeading
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(widthDp = 360)
@Composable
private fun BookSourcesSubscriptionsPreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        Surface(Modifier.padding(16.dp)) {
            Column {
                SettingsSectionLabel(stringResource(R.string.book_sources_operations))
                SettingsCardRow {
                    androidx.compose.material3.Text(
                        stringResource(R.string.book_sources_speed_all),
                        Modifier.padding(16.dp),
                    )
                }
                Spacer(Modifier.height(SpaceBeforeHeading))
                SettingsSectionLabel(stringResource(R.string.book_sources_subscriptions_count, 2))
                SettingsRows {
                    SourceSubscriptionRow(
                        title = stringResource(R.string.book_sources_subscriptions_legacy),
                        count = 12,
                        averageMillis = null,
                        first = true,
                        last = false,
                        enabled = true,
                        onClick = {},
                    )
                    SourceSubscriptionRow(
                        title = "books.json",
                        count = 8,
                        averageMillis = 236,
                        first = false,
                        last = true,
                        enabled = true,
                        onClick = {},
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun BookSourceDiscoveryChipPreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        Surface(Modifier.padding(16.dp)) {
            SourceDiscoveryChip(label = "热门 · 示例书源", enabled = true, onClick = {})
        }
    }
}

@Preview(widthDp = 320, fontScale = 1.5f)
@Composable
private fun BookSourceCategoryFiltersPreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        Surface(Modifier.padding(16.dp)) {
            SourceCategoryFilters(
                categories = listOf("历史架空与古代言情", "都市生活\n悬疑推理", ""),
                selectedCategory = null,
                onSelect = {},
            )
        }
    }
}

@Preview(widthDp = 320)
@Composable
private fun BookSourceDownloadStatusPreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        Surface(Modifier.padding(16.dp)) {
            Column {
                SourceDownloadStatus(SourceDownloadStage.Downloading)
            }
        }
    }
}

@Preview(widthDp = 320)
@Composable
private fun BookSourceScopePickerPreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        Surface(Modifier.padding(16.dp)) {
            SourceSearchScopePicker(
                subscriptions = listOf(com.example.frogreader.data.LegacySourceSubscription),
                selected = "",
                enabled = true,
                onSelect = {},
            )
        }
    }
}

@Preview
@Composable
private fun BookSourceSwipePreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        Column {
            Surface(Modifier.size(width = 320.dp, height = 64.dp)) {
                SourceSwipeBackground(towardFavorite = true, favorite = false)
            }
            Surface(Modifier.size(width = 320.dp, height = 64.dp)) {
                SourceSwipeBackground(towardFavorite = false, favorite = true)
            }
        }
    }
}

@Preview
@Composable
private fun BookSourceBatchFavoritePreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        Surface {
            SourceBatchFavoriteButton(enabled = true, onClick = {})
        }
    }
}
