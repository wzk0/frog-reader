package com.example.frogreader.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.ui.theme.floatingSurface

@Composable
internal fun BookPreparseDialog(title: String, state: BookPreparseState, onDismiss: () -> Unit) {
    val running = state is BookPreparseState.Running
    val fraction = (state as? BookPreparseState.Running)?.fraction
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.floatingSurface,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.library_menu_preparse)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (running) {
                    if (fraction == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        val animatedFraction by animateFloatAsState(fraction, label = "Preparse progress")
                        LinearProgressIndicator(
                            progress = { animatedFraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                val status = when (state) {
                    is BookPreparseState.Running -> when {
                        fraction == null -> R.string.library_preparse_running
                        fraction >= 1f -> R.string.library_preparse_caching
                        else -> R.string.library_preparse_percentage
                    }
                    is BookPreparseState.Complete -> if (state.cached) R.string.library_preparse_complete
                        else R.string.library_preparse_memory_only
                    BookPreparseState.Failed -> R.string.library_preparse_failed
                }
                val percent = ((fraction ?: 0f) * 100).toInt()
                AnimatedContent(
                    targetState = status to percent,
                    contentKey = { it.first },
                    label = "Preparse status",
                ) { (message, value) ->
                    Text(
                        if (message == R.string.library_preparse_percentage) {
                            stringResource(message, value)
                        } else stringResource(message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (running) R.string.library_preparse_background else R.string.settings_close))
            }
        },
    )
}
