package com.example.frogreader.ui.settings

import com.example.frogreader.ui.components.rememberPredictiveBackMotion
import com.example.frogreader.ui.components.SpaceBeforeHeading
import com.example.frogreader.ui.components.SpaceAfterHeading
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import com.example.frogreader.ui.components.FlatDropdownMenu
import android.net.Uri
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.frogreader.ui.components.AppSubpageNavHost
import androidx.navigation.compose.rememberNavController
import android.provider.OpenableColumns
import android.os.SystemClock
import androidx.compose.material.icons.outlined.FolderOpen
import com.example.frogreader.data.SourceSubscription
import com.example.frogreader.data.MissingSourceChaptersException
import com.example.frogreader.data.MissingSourceContentException
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.frogreader.ui.theme.floatingSurface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.SelectAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import com.example.frogreader.ui.components.connectedCardShape
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.R
import com.example.frogreader.data.BookSource
import com.example.frogreader.data.BookSourceReader
import com.example.frogreader.data.SourceBook
import com.example.frogreader.data.SourceDiscovery
import com.example.frogreader.data.discoveryCategories
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.example.frogreader.data.SourceConnection
import java.net.SocketTimeoutException
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Job
import com.example.frogreader.data.ReplacementRulesFileException
import com.example.frogreader.data.UnsupportedSourceSearchException
import com.example.frogreader.ui.components.ConnectedCardGap
import org.jsoup.HttpStatusException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private suspend fun <T> List<BookSource>.mapConcurrent(
    transform: suspend (Int, BookSource) -> T,
): List<T> = coroutineScope {
    val permits = Semaphore(8)
    mapIndexed { index, source ->
        async { permits.withPermit { transform(index, source) } }
    }.awaitAll()
}

private const val SubscriptionsRoute = "subscriptions"
private const val SubscriptionId = "subscriptionId"

private data class SourceMeasurement(val connection: SourceConnection, val millis: Long? = null)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookSourcesSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = (context.applicationContext as FrogReaderApp).bookSources
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(emptyList<BookSource>()) }
    val navController = rememberNavController()
    val groups = remember(sources) {
        sources.flatMap { source -> source.subscriptions.map { it to source } }
            .groupBy { it.first.id }
    }
    val legacyTitle = stringResource(R.string.book_sources_subscriptions_legacy)
    var importingLink by rememberSaveable { mutableStateOf(false) }
    var link by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val clipboard = LocalClipboard.current
    var message by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val sourceUpdates = remember { kotlinx.coroutines.sync.Mutex() }
    var importing by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var toDelete by remember { mutableStateOf<Pair<Set<String>, String?>?>(null) }
    var checkResult by remember { mutableStateOf<List<BookSource>?>(null) }
    var checkedTotal by remember { mutableIntStateOf(0) }
    var checkedSubscriptionId by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val connections = remember { mutableStateMapOf<String, SourceMeasurement>() }
    val checkingSources = remember { mutableStateMapOf<String, Unit>() }
    fun toggleSelection(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun change(error: Int, updatesRows: Boolean = false, operation: suspend () -> List<BookSource>) {
        if (busy) return
        if (!updatesRows) {
            busy = true
            importing = error == R.string.book_sources_import_failed || error == R.string.book_sources_link_failed
        }
        scope.launch {
            try {
                sourceUpdates.withLock {
                    sources = operation()
                    message = 0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ReplacementRulesFileException) {
                message = R.string.book_sources_replacement_rules
            } catch (_: Exception) {
                message = error
            } finally {
                if (!updatesRows) {
                    importing = false
                    busy = false
                }
            }
        }
    }
    fun updateFavorites(ids: Set<String>, favorite: (BookSource) -> Boolean) {
        change(R.string.book_sources_manage_failed, updatesRows = true) {
            store.update { all ->
                all.map { source ->
                    if (source.bookSourceUrl in ids) source.copy(favorite = favorite(source)) else source
                }
            }
        }
    }

    fun deleteSources(ids: Set<String>, group: String?) {
        change(R.string.book_sources_manage_failed, updatesRows = true) {
            store.update { all ->
                all.mapNotNull { source ->
                    if (source.bookSourceUrl !in ids) return@mapNotNull source
                    val remaining = source.subscriptions.filterNot { group == null || it.id == group }
                    source.copy(subscriptions = remaining).takeIf { remaining.isNotEmpty() }
                }
            }.also { updated ->
                selection = emptySet()
                if (group != null && navController.currentBackStackEntry?.arguments?.getString(SubscriptionId) == group && updated.none { source -> source.subscriptions.any { it.id == group } }) {
                    navController.popBackStack()
                }
            }
        }
    }

    fun checkConnections(candidates: List<BookSource>, subscriptionId: String?) {
        if (busy || candidates.isEmpty()) return
        busy = true
        checking = true
        checkedTotal = candidates.size
        checkedSubscriptionId = subscriptionId
        connections.clear()
        scope.launch {
            try {
                checkResult = candidates.mapConcurrent { _, source ->
                    val id = source.bookSourceUrl
                    checkingSources[id] = Unit
                    val result = try {
                        val start = SystemClock.elapsedRealtime()
                        BookSourceReader.checkConnection(source)
                        SourceMeasurement(SourceConnection.Connected, SystemClock.elapsedRealtime() - start)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: SocketTimeoutException) {
                        SourceMeasurement(SourceConnection.TimedOut)
                    } catch (_: Exception) {
                        SourceMeasurement(SourceConnection.Failed)
                    } finally {
                        checkingSources.remove(id)
                    }
                    connections[id] = result
                    source.takeIf { result.connection != SourceConnection.Connected }
                }.filterNotNull()
            } finally {
                try {
                    val checked = connections.toMap()
                    if (checked.isNotEmpty()) {
                        withContext(NonCancellable) {
                            sourceUpdates.withLock {
                                sources = store.update { all ->
                                    all.map { source ->
                                        checked[source.bookSourceUrl]?.let { source.copy(connection = it.connection, responseMillis = it.millis) } ?: source
                                    }
                                }
                            }
                        }
                    }
                } catch (_: IOException) {
                    message = R.string.book_sources_manage_failed
                } finally {
                    connections.clear()
                    checkingSources.clear()
                    checking = false
                    busy = false
                }
            }
        }
    }

    LaunchedEffect(store) { change(R.string.book_sources_manage_failed) { store.read() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) change(R.string.book_sources_import_failed) {
            withContext(Dispatchers.IO) {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    ?: uri.lastPathSegment.orEmpty()
                context.contentResolver.openInputStream(uri)?.use {
                    store.importFromStream(it, SourceSubscription("file:$uri", name))
                }
                    ?: error("Missing document")
            }
        }
    }
    val pageContent = rememberUpdatedState<@Composable (String?) -> Unit> { openedId ->
        val subscription = openedId?.let { groups[it]?.firstOrNull()?.first }
        val scopedSources = if (openedId == null) sources else groups[openedId].orEmpty().map { it.second }
        val subscriptionTitle = subscription?.name?.ifBlank { legacyTitle }
        val connectedCount = scopedSources.count { (connections[it.bookSourceUrl]?.connection ?: it.connection) == SourceConnection.Connected }
        val timeoutCount = scopedSources.count { (connections[it.bookSourceUrl]?.connection ?: it.connection) == SourceConnection.TimedOut }
        val navigateBack: () -> Unit = {
            when {
                openedId == null -> onBack()
                selection.isNotEmpty() -> selection = emptySet()
                else -> navController.popBackStack()
            }
        }
        val backMotion = rememberPredictiveBackMotion(openedId != null && selection.isNotEmpty()) {
            selection = emptySet()
        }
        SettingsPageScaffold(
            subscriptionTitle ?: stringResource(R.string.book_sources_title),
            navigateBack,
            scrollContent = false,
            modifier = backMotion,
        ) {
            SourceListWithTopAction {
                item(key = "import-controls") {
                    Column {
                        SettingsSectionLabel(stringResource(R.string.book_sources_operations))
                        SettingsRows {
                            if (openedId == null) {
                                SettingsCardRow(last = false) {
                                    SettingsNavigationRow(
                                        icon = Icons.Outlined.FileOpen,
                                        title = stringResource(R.string.book_sources_import),
                                        subtitle = stringResource(R.string.book_sources_import_file_summary),
                                        enabled = !busy,
                                        onClick = { picker.launch(arrayOf("*/*")) },
                                    )
                                }
                                SettingsCardRow(first = false, last = false) {
                                    SettingsNavigationRow(
                                        icon = Icons.Outlined.Link,
                                        title = stringResource(R.string.book_sources_import_link),
                                        subtitle = stringResource(R.string.book_sources_import_link_summary),
                                        enabled = !busy,
                                        onClick = {
                                            message = 0
                                            importingLink = true
                                        },
                                    )
                                }
                            }
                            SettingsCardRow(first = openedId != null) {
                                SettingsNavigationRow(
                                    icon = Icons.Outlined.NetworkCheck,
                                    title = stringResource(if (openedId == null) R.string.book_sources_speed_all else R.string.book_sources_speed_subscription),
                                    subtitle = stringResource(R.string.book_sources_check_summary),
                                    enabled = !busy && scopedSources.isNotEmpty(),
                                    onClick = { checkConnections(scopedSources, openedId) },
                                )
                            }
                        }
                        AnimatedVisibility((importing && !importingLink) || checking) {
                            Card(Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(28.dp)) {
                                Column(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.NetworkCheck, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            stringResource(if (checking) R.string.book_sources_checking else R.string.book_sources_importing),
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (checking) {
                                            val completed = connections.size
                                            Text(stringResource(R.string.book_sources_check_progress, completed, checkedTotal),
                                                style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                    if (checking) {
                                        val completed = connections.size
                                        LinearProgressIndicator(
                                            progress = { completed.toFloat() / checkedTotal.coerceAtLeast(1) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            }
                        }
                        if (message != 0 && !importingLink) {
                            Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                        }

                    }
                }
                item(key = "source-list-heading") {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(top = SpaceBeforeHeading - ConnectedCardGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SettingsSectionLabel(
                                if (openedId == null) stringResource(R.string.book_sources_subscriptions_count, groups.size)
                                else stringResource(R.string.book_sources_list_count, scopedSources.size),
                                bottomSpacing = 0.dp,
                            )
                            Text(
                                stringResource(R.string.book_sources_connection_counts, connectedCount, timeoutCount),
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        AnimatedVisibility(selection.isNotEmpty(), modifier = Modifier.align(Alignment.End)) {
                            Row {
                                IconButton(enabled = !busy, onClick = {
                                    selection = scopedSources.mapTo(mutableSetOf(), BookSource::bookSourceUrl)
                                }) { Icon(Icons.Outlined.SelectAll, stringResource(R.string.book_sources_select_all)) }
                                SourceBatchFavoriteButton(
                                    enabled = !busy && scopedSources.any { it.bookSourceUrl in selection && !it.favorite },
                                    onClick = { updateFavorites(selection) { true } },
                                )
                                IconButton(enabled = !busy, onClick = { toDelete = selection to openedId }) {
                                    Icon(Icons.Outlined.Delete, stringResource(R.string.book_sources_delete_selected))
                                }
                                IconButton(enabled = !busy, onClick = { selection = emptySet() }) {
                                    Icon(Icons.Outlined.Close, stringResource(R.string.book_sources_clear_selection))
                                }
                            }
                        }
                        if (sources.isEmpty() && !busy) SettingsHelper(stringResource(R.string.book_sources_empty))
                        else Spacer(Modifier.height(SpaceAfterHeading - ConnectedCardGap))
                    }
                }
                if (openedId == null) {
                    itemsIndexed(groups.values.toList(), key = { _, group -> group.first().first.id }) { index, group ->
                        val entry = group.first().first
                        val measured = group.mapNotNull { (_, source) ->
                            val latest = connections[source.bookSourceUrl]
                            if (latest != null) latest.millis else source.responseMillis
                        }
                        SourceSubscriptionRow(
                            title = entry.name.ifBlank { legacyTitle },
                            count = group.size,
                            averageMillis = measured.takeIf { it.isNotEmpty() }?.average()?.toLong(),
                            first = index == 0,
                            last = index == groups.size - 1,
                            enabled = !busy,
                            onClick = { navController.navigate("$SubscriptionsRoute/${Uri.encode(entry.id)}") { launchSingleTop = true } },
                        )
                    }
                } else itemsIndexed(scopedSources, key = { _, source -> source.bookSourceUrl }) { index, source ->
                    val first = index == 0
                    val last = index == scopedSources.lastIndex
                    SourceSwipeActions(
                        modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                        shape = connectedCardShape(first, last),
                        favorite = source.favorite,
                        enabled = !busy && selection.isEmpty(),
                        onFavorite = { updateFavorites(setOf(source.bookSourceUrl)) { !it.favorite } },
                        onDelete = { toDelete = setOf(source.bookSourceUrl) to openedId },
                    ) {
                        SettingsCardRow(first = first, last = last) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .combinedClickable(
                                        enabled = !busy,
                                        onClick = { if (selection.isNotEmpty()) toggleSelection(source.bookSourceUrl) },
                                        onLongClick = { toggleSelection(source.bookSourceUrl) },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                AnimatedVisibility(selection.isNotEmpty()) {
                                    Checkbox(checked = source.bookSourceUrl in selection, onCheckedChange = null)
                                }
                                SourceConnectionIndicator(
                                    state = connections[source.bookSourceUrl]?.connection ?: source.connection,
                                    checking = source.bookSourceUrl in checkingSources,
                                )
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (source.favorite) {
                                            Icon(
                                                Icons.Outlined.Favorite,
                                                stringResource(R.string.book_sources_favorite_status),
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        Text(source.bookSourceName, style = MaterialTheme.typography.titleMedium)
                                    }
                                    Text(source.bookSourceUrl, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    val latest = connections[source.bookSourceUrl]
                                    val millis = if (latest != null) latest.millis else source.responseMillis
                                    if (millis != null) Text(stringResource(R.string.book_sources_speed_millis, millis),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                if (selection.isEmpty()) {
                                    Switch(
                                        checked = source.enabled,
                                        onCheckedChange = { enabled ->
                                            change(R.string.book_sources_manage_failed, updatesRows = true) {
                                                store.update { all ->
                                                    all.map { current ->
                                                        if (current.bookSourceUrl == source.bookSourceUrl) current.copy(enabled = enabled)
                                                        else current
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !busy,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    AppSubpageNavHost(navController, SubscriptionsRoute) {
        composable(SubscriptionsRoute) { pageContent.value(null) }
        composable(
            "$SubscriptionsRoute/{$SubscriptionId}",
            arguments = listOf(navArgument(SubscriptionId) { type = NavType.StringType }),
        ) { entry ->
            pageContent.value(entry.arguments?.getString(SubscriptionId))
        }
    }
    toDelete?.let { (ids, subscriptionId) ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.book_sources_delete_selected)) },
            text = { Text(stringResource(R.string.book_sources_delete_confirmation, ids.size)) },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    deleteSources(ids, subscriptionId)
                }) { Text(stringResource(R.string.book_sources_delete_selected)) }
            },
        )
    }
    checkResult?.let { unreachable ->
        AlertDialog(
            onDismissRequest = { checkResult = null },
            title = { Text(stringResource(R.string.book_sources_check_complete)) },
            text = {
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.book_sources_check_result, checkedTotal - unreachable.size, unreachable.size))
                    unreachable.forEach { Text(it.bookSourceName, modifier = Modifier.padding(top = 6.dp)) }
                }
            },
            confirmButton = {
                if (unreachable.isNotEmpty()) {
                    TextButton(onClick = {
                        checkResult = null
                        deleteSources(unreachable.mapTo(mutableSetOf(), BookSource::bookSourceUrl), checkedSubscriptionId)
                    }) { Text(stringResource(R.string.book_sources_delete_unreachable)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { checkResult = null }) { Text(stringResource(R.string.settings_close)) }
            },
        )
    }
    if (importingLink) AlertDialog(
        containerColor = MaterialTheme.colorScheme.floatingSurface,
        tonalElevation = 0.dp,
        onDismissRequest = { if (!busy) importingLink = false },
        title = { Text(stringResource(R.string.book_sources_import_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = link,
                    onValueChange = {
                        link = it
                        message = 0
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                    label = { Text(stringResource(R.string.book_sources_link_label)) },
                    trailingIcon = {
                        IconButton(enabled = !busy, onClick = {
                            scope.launch {
                                val clip = clipboard.getClipEntry()?.clipData
                                val pasted = if (clip != null && clip.itemCount > 0) {
                                    clip.getItemAt(0).coerceToText(context)?.toString()?.trim()
                                } else null
                                if (!pasted.isNullOrEmpty()) {
                                    link = TextFieldValue(pasted, TextRange(pasted.length))
                                    message = 0
                                }
                            }
                        }) {
                            Icon(Icons.Outlined.ContentPaste, stringResource(R.string.book_sources_paste_link))
                        }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    ),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message == R.string.book_sources_link_failed || message == R.string.book_sources_replacement_rules) {
                    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = link.text.isNotBlank() && !busy, onClick = {
                message = 0
                change(R.string.book_sources_link_failed) {
                    store.importFromUrl(link.text).also {
                        link = TextFieldValue()
                        importingLink = false
                    }
                }
            }) { Text(stringResource(R.string.book_sources_import_link)) }
        },
    )
}

@Composable
internal fun SourceBatchFavoriteButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(enabled = enabled, onClick = onClick) {
        Icon(Icons.Outlined.FavoriteBorder, stringResource(R.string.book_sources_favorite_selected))
    }
}

@Composable
private fun SourceSwipeActions(
    modifier: Modifier,
    shape: Shape,
    favorite: Boolean,
    enabled: Boolean,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val swipe = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.4f })
    val favoriteAction by rememberUpdatedState(onFavorite)
    val deleteAction by rememberUpdatedState(onDelete)
    val gesturesEnabled by rememberUpdatedState(enabled)
    val favoriteLabel = stringResource(if (favorite) R.string.book_sources_unfavorite else R.string.book_sources_favorite)
    val deleteLabel = stringResource(R.string.book_sources_remove)
    LaunchedEffect(swipe.settledValue) {
        val direction = swipe.settledValue
        if (direction != SwipeToDismissBoxValue.Settled) {
            if (gesturesEnabled) {
                if (direction == SwipeToDismissBoxValue.StartToEnd) favoriteAction() else deleteAction()
            }
            swipe.reset()
        }
    }
    SwipeToDismissBox(
        state = swipe,
        gesturesEnabled = enabled,
        modifier = modifier.clip(shape).semantics {
            if (enabled) {
                customActions = listOf(
                    CustomAccessibilityAction(favoriteLabel) { onFavorite(); true },
                    CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                )
            }
        },
        backgroundContent = {
            val direction = swipe.dismissDirection
            if (direction != SwipeToDismissBoxValue.Settled) {
                SourceSwipeBackground(towardFavorite = direction == SwipeToDismissBoxValue.StartToEnd, favorite = favorite)
            }
        },
    ) {
        content()
    }
}

@Composable
internal fun SourceSwipeBackground(towardFavorite: Boolean, favorite: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val foreground = if (towardFavorite) scheme.onPrimaryContainer else scheme.onErrorContainer
    val (icon, label) = when {
        !towardFavorite -> Icons.Outlined.Delete to R.string.book_sources_remove
        favorite -> Icons.Outlined.FavoriteBorder to R.string.book_sources_unfavorite
        else -> Icons.Outlined.Favorite to R.string.book_sources_favorite
    }
    Row(
        Modifier.fillMaxSize()
            .background(if (towardFavorite) scheme.primaryContainer else scheme.errorContainer)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (towardFavorite) Alignment.Start else Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = foreground)
        Text(stringResource(label), color = foreground, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun SourceSubscriptionRow(
    title: String,
    count: Int,
    averageMillis: Long?,
    first: Boolean,
    last: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val size = stringResource(R.string.book_sources_subscription_size, count)
    SettingsCardRow(first = first, last = last) {
        SettingsNavigationRow(
            icon = Icons.Outlined.FolderOpen,
            title = title,
            subtitle = if (averageMillis == null) size else "$size · " +
                stringResource(R.string.book_sources_speed_average, averageMillis),
            enabled = enabled,
            onClick = onClick,
        )
    }
}

@Composable
private fun SourceConnectionIndicator(state: SourceConnection?, checking: Boolean) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(when {
        checking -> R.string.book_sources_connecting
        state == SourceConnection.Connected -> R.string.book_sources_check_connected
        state == SourceConnection.TimedOut -> R.string.book_sources_timed_out
        state == SourceConnection.Failed -> R.string.book_sources_offline
        else -> R.string.book_sources_waiting
    })
    Box(Modifier.size(18.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        if (checking) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        else Box(Modifier.size(9.dp).background(
            when (state) {
                SourceConnection.Connected -> colors.primary
                SourceConnection.TimedOut -> colors.tertiary
                SourceConnection.Failed -> colors.error
                null -> colors.outlineVariant
            }, CircleShape,
        ))
    }
}

private enum class SourceSearchStage {
    WAITING, CONNECTING, READING, FOUND, EMPTY, UNSUPPORTED, HTTP_ERROR, OFFLINE, CLEARTEXT, RULE_ERROR,
}

private data class SourceSearchStatus(
    val name: String,
    val stage: SourceSearchStage = SourceSearchStage.WAITING,
    val count: Int = 0,
)

@Composable
private fun SourceSearchStatus.label(): String = when (stage) {
    SourceSearchStage.WAITING -> stringResource(R.string.book_sources_waiting)
    SourceSearchStage.CONNECTING -> stringResource(R.string.book_sources_connecting)
    SourceSearchStage.READING -> stringResource(R.string.book_sources_reading)
    SourceSearchStage.FOUND -> stringResource(R.string.book_sources_connected_found, count)
    SourceSearchStage.EMPTY -> stringResource(R.string.book_sources_connected_empty)
    SourceSearchStage.UNSUPPORTED -> stringResource(R.string.book_sources_unsupported)
    SourceSearchStage.HTTP_ERROR -> stringResource(R.string.book_sources_http_error, count)
    SourceSearchStage.CLEARTEXT -> stringResource(R.string.book_sources_cleartext)
    SourceSearchStage.OFFLINE -> stringResource(R.string.book_sources_offline)
    SourceSearchStage.RULE_ERROR -> stringResource(R.string.book_sources_rule_error)
}

internal enum class SourceDownloadStage { Queued, Downloading, Importing }

@Composable
internal fun BookSourceSearchPage(
    initialKeyword: String = "",
    onBack: () -> Unit,
    onBookAdded: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FrogReaderApp
    var availableSources by remember { mutableStateOf(emptyList<BookSource>()) }
    var searchScope by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(app) {
        try {
            availableSources = app.bookSources.read()
            if (searchScope != null && searchScope != FavoriteSourceScope &&
                availableSources.none { source -> source.subscriptions.any { it.id == searchScope } }
            ) searchScope = null
        } catch (_: IOException) {
            availableSources = emptyList()
        }
    }
    val subscriptions = remember(availableSources) { availableSources.flatMap(BookSource::subscriptions).distinctBy { it.id } }
    val discovery = remember(availableSources, searchScope) {
        availableSources.filter { it.isInSearchScope(searchScope) }.flatMap { it.discoveryCategories() }
    }
    val scope = rememberCoroutineScope()
    var keyword by rememberSaveable { mutableStateOf(initialKeyword) }
    var startedInitialSearch by rememberSaveable { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<SourceBook>()) }
    val downloadQueue = remember { mutableStateMapOf<String, SourceDownloadStage>() }
    val downloadJobs = remember { mutableMapOf<String, Job>() }
    val downloadSlots = remember { Semaphore(3) }
    val importSlot = remember { Semaphore(1) }
    val downloadResults = remember { mutableStateMapOf<String, Int>() }
    var message by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val categories = remember(results) { results.map(SourceBook::category).distinct() }
    val visibleResults = remember(results, category) {
        if (category == null) results else results.filter { it.category == category }
    }
    val statuses = remember { mutableStateListOf<SourceSearchStatus>() }
    val searchLog = remember { mutableStateListOf<SourceSearchStatus>() }
    val current = statuses.firstOrNull {
        it.stage == SourceSearchStage.CONNECTING || it.stage == SourceSearchStage.READING
    }
    val completed = statuses.count { it.stage.ordinal >= SourceSearchStage.FOUND.ordinal }

    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        searching = false
    }

    fun search(discover: SourceDiscovery? = null) {
        val key = keyword.trim()
        if ((discover == null && key.isEmpty()) || searching) return
        results = emptyList()
        message = 0
        searching = true
        statuses.clear()
        searchLog.clear()
        category = null
        val selectedScope = searchScope
        searchJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val sources = if (discover == null) app.bookSources.read().filter { it.isInSearchScope(selectedScope) }
                    else listOf(discover.source)
                statuses.addAll(sources.map { SourceSearchStatus(it.bookSourceName) })
                sources.mapConcurrent { index, source ->
                    val status = statuses[index]
                    statuses[index] = status.copy(stage = SourceSearchStage.CONNECTING)
                    var connected = false
                    statuses[index] = try {
                        val onConnected: suspend () -> Unit = {
                            withContext(Dispatchers.Main.immediate) {
                                connected = true
                                statuses[index] = status.copy(stage = SourceSearchStage.READING)
                            }
                        }
                        val found = if (discover == null) BookSourceReader.search(source, key, onConnected)
                        else BookSourceReader.explore(discover, onConnected)
                        results = results + found
                        status.copy(
                            stage = if (found.isEmpty()) SourceSearchStage.EMPTY else SourceSearchStage.FOUND,
                            count = found.size,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: UnsupportedSourceSearchException) {
                        status.copy(stage = if (connected) SourceSearchStage.RULE_ERROR else SourceSearchStage.UNSUPPORTED)
                    } catch (e: HttpStatusException) {
                        status.copy(stage = SourceSearchStage.HTTP_ERROR, count = e.statusCode)
                    } catch (e: IOException) {
                        val stage = when {
                            connected -> SourceSearchStage.RULE_ERROR
                            e.message?.contains("cleartext", ignoreCase = true) == true -> SourceSearchStage.CLEARTEXT
                            else -> SourceSearchStage.OFFLINE
                        }
                        status.copy(stage = stage)
                    } catch (_: Exception) {
                        status.copy(stage = if (connected) SourceSearchStage.RULE_ERROR else SourceSearchStage.UNSUPPORTED)
                    }
                    searchLog.add(statuses[index])
                }
                if (sources.isEmpty()) {
                    message = if (selectedScope == null) R.string.book_sources_empty else R.string.book_sources_scope_empty
                }
                else if (results.isEmpty()) message = R.string.book_sources_no_results
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message = R.string.book_sources_manage_failed
            } finally {
                if (kotlin.coroutines.coroutineContext[Job] === searchJob) {
                    searchJob = null
                    searching = false
                }
            }
        }
        searchJob?.start()
    }

    LaunchedEffect(initialKeyword) {
        if (initialKeyword.isNotBlank() && !startedInitialSearch) {
            startedInitialSearch = true
            search()
        }
    }

    fun add(book: SourceBook) {
        val key = book.resultKey
        when (downloadQueue[key]) {
            SourceDownloadStage.Queued, SourceDownloadStage.Downloading -> {
                val job = downloadJobs.remove(key)
                downloadQueue.remove(key)
                job?.cancel()
                return
            }
            SourceDownloadStage.Importing -> return
            null -> Unit
        }
        if (downloadResults[key] == R.string.book_sources_added) return
        downloadResults.remove(key)
        downloadQueue[key] = SourceDownloadStage.Queued
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            var file: File? = null
            try {
                val added = downloadSlots.withPermit {
                    downloadQueue[key] = SourceDownloadStage.Downloading
                    val temporary = withContext(Dispatchers.IO) {
                        File.createTempFile("source-", ".md", context.cacheDir).also { file = it }
                    }
                    BookSourceReader.writeBook(book, temporary)
                    importSlot.withPermit {
                        downloadQueue[key] = SourceDownloadStage.Importing
                        app.bookRepository.importSourceBook(Uri.fromFile(temporary))
                    }
                }
                downloadResults[key] = R.string.book_sources_added
                onBookAdded(added.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                downloadResults[key] = when (e) {
                    is UnsupportedSourceSearchException -> R.string.book_sources_download_unsupported
                    is MissingSourceChaptersException -> R.string.book_sources_no_chapters
                    is MissingSourceContentException -> R.string.book_sources_no_content
                    is HttpStatusException, is SocketTimeoutException, is java.net.UnknownHostException,
                    is java.net.ConnectException -> R.string.book_sources_download_connection
                    else -> R.string.book_sources_add_failed
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { file?.delete() }
                val job = kotlin.coroutines.coroutineContext[Job]
                if (downloadJobs[key] === job) {
                    downloadQueue.remove(key)
                    downloadJobs.remove(key)
                }
            }
        }
        if (key in downloadQueue) downloadJobs[key] = job
    }

    SettingsPageScaffold(stringResource(R.string.book_sources_search), onBack, scrollContent = false) {
        SourceListWithTopAction {
            item(key = "search-controls") {
                Column(verticalArrangement = Arrangement.spacedBy(SpaceBeforeHeading)) {
                    OutlinedTextField(keyword, { keyword = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.book_sources_keyword)) })
                    SourceSearchScopePicker(
                        subscriptions = subscriptions,
                        selected = searchScope,
                        enabled = !searching,
                        onSelect = { searchScope = it },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { search() }, enabled = !searching && keyword.isNotBlank()) {
                            Text(stringResource(R.string.book_sources_search))
                        }
                        AnimatedVisibility(searching) {
                            OutlinedButton(onClick = ::cancelSearch) {
                                Text(stringResource(R.string.book_sources_cancel_search))
                            }
                        }
                    }
                    if (discovery.isNotEmpty()) {
                        Column {
                            SettingsSectionLabel(stringResource(R.string.book_sources_discovery))
                            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(discovery.size) { index ->
                                    val entry = discovery[index]
                                    SourceDiscoveryChip(
                                        label = entry.title + " · " + entry.source.bookSourceName,
                                        enabled = !searching,
                                        onClick = { search(entry) },
                                    )
                                }
                            }
                        }
                    }
                    if (searching || statuses.isNotEmpty()) {
                        SourceSearchLog(searchLog, current.takeIf { searching }, searching, completed, statuses.size)
                    }
                    if (message != 0) Text(stringResource(message))
                    if (categories.any(String::isNotBlank)) {
                        SourceCategoryFilters(categories, category) { category = it }
                    }
                }
            }
            if (searching || statuses.isNotEmpty() || visibleResults.isNotEmpty()) item(key = "results-heading") {
                Column(Modifier.padding(top = SpaceBeforeHeading - ConnectedCardGap)) {
                    SettingsSectionLabel(
                        stringResource(R.string.book_sources_results_count, visibleResults.size),
                        bottomSpacing = SpaceAfterHeading - ConnectedCardGap,
                    )
                }
            }
            itemsIndexed(visibleResults, key = { _, book -> book.resultKey }) { index, book ->
                val result = downloadResults[book.resultKey]
                val download = downloadQueue[book.resultKey]
                val canCancel = download == SourceDownloadStage.Queued || download == SourceDownloadStage.Downloading
                Box(Modifier.animateItem()) {
                    SettingsCardRow(first = index == 0, last = index == visibleResults.lastIndex) {
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable(
                                    enabled = canCancel || (download == null && result != R.string.book_sources_added),
                                    onClickLabel = stringResource(
                                        if (canCancel) R.string.book_sources_cancel_download else R.string.book_sources_add_home,
                                    ),
                                ) { add(book) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(book.title, style = MaterialTheme.typography.titleMedium)
                            SourceResultChips(book)
                            if (download != null) SourceDownloadStatus(download)
                            if (result != null) {
                                Text(
                                    stringResource(result),
                                    color = if (result == R.string.book_sources_added) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val FavoriteSourceScope = ""

private fun BookSource.isInSearchScope(scope: String?): Boolean = enabled && when (scope) {
    null -> true
    FavoriteSourceScope -> favorite
    else -> subscriptions.any { it.id == scope }
}

@Composable
internal fun SourceSearchScopePicker(
    subscriptions: List<SourceSubscription>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val all = stringResource(R.string.book_sources_scope_all)
    val favorites = stringResource(R.string.book_sources_scope_favorites)
    val legacy = stringResource(R.string.book_sources_subscriptions_legacy)
    val options = listOf(null to all, FavoriteSourceScope to favorites) +
        subscriptions.map { it.id to it.name.ifBlank { legacy } }
    val current = options.firstOrNull { it.first == selected }?.second ?: all
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text(
                stringResource(R.string.book_sources_scope_current, current),
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.ExpandMore, contentDescription = null)
        }
        FlatDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            name,
                            color = if (id == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

@Composable
internal fun SourceDownloadStatus(stage: SourceDownloadStage) {
    val status = when (stage) {
        SourceDownloadStage.Queued -> R.string.book_sources_download_queued_cancel
        SourceDownloadStage.Downloading -> R.string.book_sources_downloading_cancel
        SourceDownloadStage.Importing -> R.string.book_sources_download_importing
    }
    Text(stringResource(status), style = MaterialTheme.typography.labelMedium)
    if (stage != SourceDownloadStage.Queued) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
internal fun SourceCategoryFilters(
    categories: List<String>,
    selectedCategory: String?,
    onSelect: (String?) -> Unit,
) {
    val colors = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(categories.size + 1) { index ->
            val value = if (index == 0) null else categories[index - 1]
            val label = when {
                value == null -> stringResource(R.string.book_sources_all_categories)
                value.isBlank() -> stringResource(R.string.book_sources_uncategorized)
                else -> value
            }
            FilterChip(
                selected = selectedCategory == value,
                onClick = { onSelect(value) },
                label = { Text(label.onSingleLine(), maxLines = 1, softWrap = false) },
                shape = CircleShape,
                border = null,
                colors = colors,
            )
        }
    }
}

private fun String.onSingleLine() = lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString(" ")

@Composable
internal fun SourceDiscoveryChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        enabled = enabled,
        shape = CircleShape,
        border = null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

private val SourceBook.resultKey get() = source.bookSourceUrl + "\u0000" + url

@Composable
private fun SourceListWithTopAction(content: LazyListScope.() -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showTop by remember(list) {
        derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 80 }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = list,
            verticalArrangement = Arrangement.spacedBy(ConnectedCardGap),
            contentPadding = PaddingValues(bottom = 84.dp),
            content = content,
        )
        AnimatedVisibility(
            visible = showTop,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            SmallFloatingActionButton(onClick = { scope.launch { list.animateScrollToItem(0) } }) {
                Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.book_sources_scroll_top))
            }
        }
    }
}

@Composable
private fun SourceResultChips(book: SourceBook) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (label in listOf(book.author, book.source.bookSourceName, book.category).filter(String::isNotBlank)) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    label.onSingleLine(),
                    Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun SourceSearchLog(
    statuses: List<SourceSearchStatus>,
    current: SourceSearchStatus?,
    searching: Boolean,
    completed: Int,
    total: Int,
) {
    val scroll = rememberLazyListState()
    val density = LocalDensity.current
    val lineHeight = with(density) { 18.sp.toDp() }
    val linePixels = with(density) { lineHeight.roundToPx() }
    val totalRows = statuses.size + if (current == null) 0 else 1
    LaunchedEffect(totalRows) {
        withFrameNanos { }
        val first = (totalRows - 3).coerceAtLeast(0)
        if (first > scroll.firstVisibleItemIndex + 3) scroll.scrollToItem(first)
        else {
            val distance = (first - scroll.firstVisibleItemIndex) * linePixels - scroll.firstVisibleItemScrollOffset
            if (distance != 0) scroll.animateScrollBy(distance.toFloat(), tween(130))
        }
    }
    SettingsCardRow {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.book_sources_log), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium)
                if (total > 0) Text(stringResource(R.string.book_sources_progress, completed, total),
                    style = MaterialTheme.typography.labelSmall)
            }
            if (searching) {
                if (total == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { completed.toFloat() / total }, Modifier.fillMaxWidth())
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(lineHeight * 3)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Transparent, 0.25f to Color.Black,
                                0.75f to Color.Black, 1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
                state = scroll,
                userScrollEnabled = false,
            ) {
                items(statuses.size) { index -> SourceLogLine(statuses[index], Modifier.height(lineHeight)) }
                if (current != null) item(key = "connecting") { SourceLogLine(current, Modifier.height(lineHeight)) }
            }
        }
    }
}

@Composable
private fun SourceLogLine(status: SourceSearchStatus, modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Text(
            stringResource(R.string.book_sources_current, status.name, status.label()),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
        )
    }
}
