package com.example.frogreader.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.BookStorage
import com.example.frogreader.data.model.CacheStorage
import com.example.frogreader.ui.theme.FrogReaderTheme

private val sampleStorage = StorageViewModel.State(
    loading = false,
    books = listOf(
        BookStorage(
            id = "1",
            title = "凡人修仙之仙界篇（校对版全本）",
            author = "忘语",
            format = BookFormat.EPUB,
            bytes = 7_645_570,
        ),
        BookStorage(
            id = "2",
            title = "蛊真人",
            author = null,
            format = BookFormat.TXT,
            bytes = 22_127_687,
        ),
    ),
    caches = CacheStorage(
        imagesBytes = 12_400_000,
        paginationBytes = 2_100_000,
        parsedBytes = 8_400_000,
    ),
)

@Preview(widthDp = 380, heightDp = 900)
@Composable
private fun StoragePageLightPreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        StoragePageContent(
            state = sampleStorage,
            onBack = {},
            onRefresh = {},
            onDelete = {},
            onClear = {},
        )
    }
}

@Preview(widthDp = 380, heightDp = 900)
@Composable
private fun StoragePageEmptyPreview() {
    FrogReaderTheme(AppTheme.OLED) {
        StoragePageContent(
            state = StorageViewModel.State(loading = false),
            onBack = {},
            onRefresh = {},
            onDelete = {},
            onClear = {},
        )
    }
}