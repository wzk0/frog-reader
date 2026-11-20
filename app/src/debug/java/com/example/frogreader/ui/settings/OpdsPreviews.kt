package com.example.frogreader.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.OpdsCatalog
import com.example.frogreader.data.OpdsPublication
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(widthDp = 340)
@Composable
private fun OpdsAddressFieldPreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        Surface(Modifier.padding(16.dp)) {
            OpdsAddressField("https://example.org/opds", enabled = true, onChange = {})
        }
    }
}

@Preview(widthDp = 340)
@Composable
private fun OpdsNetworkStatusPreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        Surface(Modifier.padding(16.dp)) {
            OpdsStatusCard(R.string.opds_connection_error, onRetry = {})
        }
    }
}

@Preview(widthDp = 340)
@Composable
private fun OpdsCatalogLatencyPreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        Surface(Modifier.padding(16.dp)) {
            OpdsCatalogLabel(
                OpdsCatalog("https://example.org/opds", "示例目录", responseMillis = 238, reachable = true),
                checking = false,
            )
        }
    }
}

@Preview(widthDp = 320, locale = "zh")
@Composable
private fun OpdsSearchPreview() {
    FrogReaderTheme(AppTheme.WHITE) {
        Surface(Modifier.padding(16.dp)) {
            Column {
                OpdsSearchInput("小说", {}, false, {}, {})
                SettingsCard {
                    OpdsPublicationRow(
                        OpdsPublication("示例图书", "较长的作者名称示例", emptyList(), "较长的目录名称示例"),
                        status = null,
                        onSelect = {},
                    )
                    OpdsPublicationRow(
                        OpdsPublication("下载中的图书", "作者", emptyList(), "示例目录"),
                        status = R.string.opds_downloading,
                        progress = OpdsTransferProgress(0.55f),
                        onSelect = {},
                    )
                }
            }
        }
    }
}
