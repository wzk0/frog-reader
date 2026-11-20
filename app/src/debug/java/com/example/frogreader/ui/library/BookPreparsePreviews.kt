package com.example.frogreader.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(locale = "zh")
@Composable
private fun BookPreparseProgressPreview() {
    MaterialTheme {
        BookPreparseDialog("阅读示例", BookPreparseState.Running(0.42f), onDismiss = {})
    }
}

@Preview(widthDp = 320, locale = "zh")
@Composable
private fun BookPreparseWithoutDiskCachePreview() {
    MaterialTheme {
        BookPreparseDialog("阅读示例", BookPreparseState.Complete(cached = false), onDismiss = {})
    }
}
