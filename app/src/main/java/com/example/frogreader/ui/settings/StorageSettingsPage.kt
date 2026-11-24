package com.example.frogreader.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TableRows
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.frogreader.R
import com.example.frogreader.data.model.BookStorage
import com.example.frogreader.ui.components.ConnectedCardGap
import com.example.frogreader.ui.components.cardSurface
import com.example.frogreader.ui.theme.floatingSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a deleted row takes to collapse before the book is actually removed. */
private const val StorageRowExitMillis = 240

/**
 * What the app keeps on disk, and the two things a user can do about it.
 *
 * Books are the only irreplaceable storage here, so deleting one asks first and
 * says what goes with it. Caches are listed with their sizes but share a single
 * action: all of them are rebuilt on the next open, and one button is easier to
 * reason about than three that behave identically.
 */
@Composable
internal fun StorageSettingsPage(
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: StorageViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<BookStorage?>(null) }
    // Rows that are collapsing right now; a book leaves storage once its row has
    // finished, so the list animates instead of losing an item mid-frame.
    var leavingIds by remember { mutableStateOf(emptySet<String>()) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        onMessage(
            when (current) {
                is StorageViewModel.Message.Deleted ->
                    context.getString(R.string.storage_deleted, current.title)
                is StorageViewModel.Message.Cleared ->
                    context.getString(R.string.storage_cleared, formatBytes(current.bytes))
                is StorageViewModel.Message.Failed -> context.getString(R.string.storage_failed)
            },
        )
        viewModel.consumeMessage()
    }

    StoragePageContent(
        state = state,
        leavingIds = leavingIds,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onDelete = { pendingDelete = it },
        onClear = { confirmClear = true },
    )

    pendingDelete?.let { book ->
        DeleteBookDialog(
            book = book,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    leavingIds = leavingIds + book.id
                    delay(StorageRowExitMillis.toLong())
                    viewModel.deleteBook(book)
                    leavingIds = leavingIds - book.id
                }
            },
        )
    }

    if (confirmClear) {
        ClearCachesDialog(
            bytes = state.caches.totalBytes,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                scope.launch { viewModel.clearCaches() }
            },
        )
    }
}

/** The page without its dialogs, so previews and tests can render a State. */
@Composable
internal fun StoragePageContent(
    state: StorageViewModel.State,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: (BookStorage) -> Unit,
    onClear: () -> Unit,
    leavingIds: Set<String> = emptySet(),
) {
    SettingsPageScaffold(
        title = stringResource(R.string.storage_title),
        onBack = onBack,
    ) {
        StorageHero(state = state, onRefresh = onRefresh)

        Spacer(Modifier.height(24.dp))

        SettingsSectionLabel(stringResource(R.string.storage_books_heading))
        SettingsRows {
            if (state.books.isEmpty()) {
                SettingsCardRow {
                    Text(
                        text = stringResource(
                            if (state.loading) {
                                R.string.storage_books_loading
                            } else {
                                R.string.storage_books_empty
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                    )
                }
            } else {
                // The framework's own placement spring: the list reflows instead of
                // jumping when a row is removed.
                Column(Modifier.animateContentSize()) {
                    state.books.forEachIndexed { index, book ->
                        // The row keeps its slot while it collapses; the delete
                        // itself lands one exit animation later, so the list
                        // reflows instead of dropping an item mid-frame.
                        AnimatedVisibility(
                            visible = book.id !in leavingIds,
                            enter = fadeIn(tween(180)) +
                                expandVertically(tween(StorageRowExitMillis)),
                            exit = fadeOut(tween(140, easing = FastOutSlowInEasing)) +
                                shrinkVertically(tween(StorageRowExitMillis)),
                        ) {
                            Column {
                                StorageCardRow(
                                    first = index == 0,
                                    last = index == state.books.lastIndex,
                                ) {
                                    StorageBookRow(
                                        book = book,
                                        enabled = !state.working,
                                        onDelete = { onDelete(book) },
                                    )
                                }
                                if (index != state.books.lastIndex) {
                                    Spacer(Modifier.height(ConnectedCardGap))
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        SettingsSectionLabel(stringResource(R.string.storage_caches_heading))
        SettingsRows {
            StorageCacheRow(
                icon = Icons.Rounded.Image,
                title = stringResource(R.string.storage_cache_images),
                subtitle = stringResource(R.string.storage_cache_images_hint),
                bytes = state.caches.imagesBytes,
                first = true,
                last = false,
            )
            StorageCacheRow(
                icon = Icons.Rounded.TableRows,
                title = stringResource(R.string.storage_cache_pagination),
                subtitle = stringResource(R.string.storage_cache_pagination_hint),
                bytes = state.caches.paginationBytes,
                first = false,
                last = false,
            )
            StorageCacheRow(
                icon = Icons.AutoMirrored.Rounded.StickyNote2,
                title = stringResource(R.string.storage_cache_parsed),
                subtitle = stringResource(R.string.storage_cache_parsed_hint),
                bytes = state.caches.parsedBytes,
                first = false,
                last = true,
            )
        }

        Spacer(Modifier.height(16.dp))

        FilledTonalButton(
            onClick = onClear,
            enabled = !state.working && state.caches.totalBytes > 0L,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.storage_clear_caches))
        }

        SettingsHelper(stringResource(R.string.storage_clear_caches_note))
    }
}

@Composable
private fun StorageHero(state: StorageViewModel.State, onRefresh: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = SettingsCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    AnimatedBytes(
                        bytes = state.totalBytes,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = 24.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    AnimatedBytes(
                        bytes = state.bookBytes,
                        cacheBytes = state.caches.totalBytes,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        template = R.string.storage_summary,
                    )
                }
                Crossfade(
                    targetState = state.loading,
                    animationSpec = tween(200),
                    label = "storage refresh",
                ) { loading ->
                    if (loading) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.storage_refresh),
                            )
                        }
                    }
                }
            }
            StorageSplitRail(
                bookBytes = state.bookBytes,
                cacheBytes = state.caches.totalBytes,
            )
        }
    }
}

/**
 * How the used space splits between books and caches, drawn with the same
 * `LinearProgressIndicator` the pre-parse dialog uses so the two screens read as
 * one interface. The filled part is the books: they are the bytes that stay.
 */
@Composable
private fun StorageSplitRail(bookBytes: Long, cacheBytes: Long) {
    val total = (bookBytes + cacheBytes).coerceAtLeast(1L)
    val booksTarget = (bookBytes.toDouble() / total).toFloat()
    val books by animateFloatAsState(
        targetValue = booksTarget,
        animationSpec = tween(480, easing = FastOutSlowInEasing),
        label = "storage rail",
    )
    LinearProgressIndicator(
        progress = { books },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A byte count that rolls to its new value instead of replacing it. */
@Composable
private fun AnimatedBytes(
    bytes: Long,
    style: TextStyle,
    color: Color = Color.Unspecified,
    cacheBytes: Long? = null,
    template: Int? = null,
) {
    // Megabytes as the animation axis: byte-exact floats lose precision above
    // 16 MiB, and no one can read the last three digits of a moving counter.
    val targetMb = bytes / (1024f * 1024f)
    val animatedMb by animateFloatAsState(
        targetValue = targetMb,
        animationSpec = tween(480, easing = FastOutSlowInEasing),
        label = "storage bytes",
    )
    val shown = (animatedMb * 1024f * 1024f).toLong().coerceAtLeast(0L)
    Text(
        text = if (template == null) {
            formatBytes(shown)
        } else {
            stringResource(template, formatBytes(shown), formatBytes(cacheBytes ?: 0L))
        },
        style = style,
        color = color,
    )
}

/**
 * A list row that owns its rounded corners so they can morph when the first or
 * last item leaves. The shared [SettingsCardRow] computes the shape once, which
 * would pop the corner radius at the moment its neighbour disappears.
 */
@Composable
private fun StorageCardRow(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    val top by animateDpAsState(
        targetValue = if (first) 24.dp else 6.dp,
        animationSpec = tween(220),
        label = "storage card top",
    )
    val bottom by animateDpAsState(
        targetValue = if (last) 24.dp else 6.dp,
        animationSpec = tween(220),
        label = "storage card bottom",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
            .background(MaterialTheme.colorScheme.cardSurface),
        content = { content() },
    )
}

@Composable
private fun StorageBookRow(book: BookStorage, enabled: Boolean, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIconCircle(
            icon = Icons.AutoMirrored.Rounded.MenuBook,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    book.author?.takeIf { it.isNotBlank() },
                    book.format.name,
                    formatBytes(book.bytes),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = stringResource(R.string.storage_delete_book),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StorageCacheRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    bytes: Long,
    first: Boolean,
    last: Boolean,
) {
    SettingsCardRow(first = first, last = last) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIconCircle(
                icon = icon,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = formatBytes(bytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeleteBookDialog(book: BookStorage, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.floatingSurface,
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        shape = SettingsCardShape,
        title = { Text(stringResource(R.string.storage_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.storage_delete_body,
                        book.title,
                        formatBytes(book.bytes),
                    ),
                )
                Text(
                    text = stringResource(R.string.storage_delete_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.storage_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_cancel)) }
        },
    )
}

@Composable
private fun ClearCachesDialog(bytes: Long, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.floatingSurface,
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        shape = SettingsCardShape,
        title = { Text(stringResource(R.string.storage_clear_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.storage_clear_body, formatBytes(bytes)))
                Text(
                    text = stringResource(R.string.storage_clear_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.storage_clear_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_cancel)) }
        },
    )
}