package com.example.frogreader.ui.settings

import com.example.frogreader.ui.components.FrogTextFieldShape
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import android.util.LruCache
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.ContentPaste
import android.content.ClipboardManager
import com.example.frogreader.ui.components.FrogDialogTextFieldShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.R
import com.example.frogreader.data.*
import com.example.frogreader.ui.components.AppSubpageNavHost
import com.example.frogreader.ui.components.ConnectedCardGap
import com.example.frogreader.ui.components.SpaceBeforeHeading
import com.example.frogreader.ui.components.SpaceAfterHeading
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import kotlin.math.roundToInt

private suspend fun <T> attemptOpds(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OpdsSettingsPage(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as FrogReaderApp
    val store = app.opdsCatalogs
    val scope = rememberCoroutineScope()
    val writes = remember { Mutex() }
    var catalogs by remember { mutableStateOf(emptyList<OpdsCatalog>()) }
    var initialCatalogUrls by remember { mutableStateOf<Set<String>?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableIntStateOf(0) }
    var linkError by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<OpdsCatalog?>(null) }
    var testing by remember { mutableStateOf(false) }
    val testingUrls = remember { mutableStateMapOf<String, Unit>() }
    val testResults = remember { mutableStateMapOf<String, OpdsCatalog>() }

    LaunchedEffect(store) {
        attemptOpds { store.read() }.onSuccess { saved ->
            catalogs = saved
            initialCatalogUrls = saved.mapTo(mutableSetOf(), OpdsCatalog::url)
        }.onFailure { message = R.string.opds_save_error }
    }
    fun update(change: (List<OpdsCatalog>) -> List<OpdsCatalog>) {
        scope.launch {
            attemptOpds { writes.withLock { catalogs = store.update(change) } }
                .onFailure { message = R.string.opds_save_error }
        }
    }
    fun testCatalogs() {
        if (busy || testing || catalogs.isEmpty()) return
        val candidates = catalogs
        testResults.clear()
        testing = true
        scope.launch {
            try {
                val permits = Semaphore(6)
                coroutineScope {
                    candidates.map { catalog ->
                        async {
                            permits.withPermit {
                                testingUrls[catalog.url] = Unit
                                try {
                                    val start = SystemClock.elapsedRealtime()
                                    val success = attemptOpds { Opds.feed(catalog.url) }.isSuccess
                                    val elapsed = if (success) SystemClock.elapsedRealtime() - start else null
                                    testResults[catalog.url] = catalog.copy(
                                        responseMillis = elapsed, reachable = success,
                                    )
                                } finally {
                                    testingUrls.remove(catalog.url)
                                }
                            }
                        }
                    }.awaitAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message = R.string.opds_save_error
            } finally {
                try {
                    // Keep results already shown even if the settings screen is closed.
                    withContext(NonCancellable) {
                        val measured = testResults.toMap()
                        if (measured.isNotEmpty()) {
                            attemptOpds {
                                writes.withLock {
                                    catalogs = store.update { entries ->
                                        entries.map { entry ->
                                            val result = measured[entry.url] ?: return@map entry
                                            entry.copy(responseMillis = result.responseMillis, reachable = result.reachable)
                                        }
                                    }
                                }
                            }.onFailure { message = R.string.opds_save_error }
                        }
                        testResults.clear()
                    }
                } finally {
                    testing = false
                }
            }
        }
    }
    val resolver = LocalContext.current.contentResolver
    val pickCatalog = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    attemptOpds {
                        val catalog = withContext(Dispatchers.IO) {
                            resolver.openInputStream(uri)?.use { Opds.fromDocument(it) } ?: throw InvalidOpdsFeed()
                        }
                        writes.withLock {
                            catalogs = store.update { entries ->
                                if (entries.none { it.url == catalog.url }) entries + catalog else entries
                            }
                        }
                    }.onSuccess { message = 0 }.onFailure { message = R.string.opds_file_error }
                } finally {
                    busy = false
                }
            }
        }
    }
    SettingsPageScaffold(stringResource(R.string.opds_title), onBack, scrollContent = false) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(ConnectedCardGap),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                SettingsSectionLabel(stringResource(R.string.opds_operations))
                SettingsRows {
                    SettingsCardRow(last = false) {
                        SettingsNavigationRow(Icons.Outlined.AddLink, stringResource(R.string.opds_add),
                            stringResource(R.string.opds_add_hint), onClick = { adding = true; linkError = 0 }, enabled = !busy)
                    }
                    SettingsCardRow(first = false, last = false) {
                        SettingsNavigationRow(Icons.Outlined.FileOpen, stringResource(R.string.opds_import_file),
                            stringResource(R.string.opds_import_hint), enabled = !busy,
                            onClick = { pickCatalog.launch(arrayOf("*/*")) })
                    }
                    SettingsCardRow(first = false) {
                        SettingsNavigationRow(
                            Icons.Outlined.NetworkCheck,
                            stringResource(R.string.opds_test),
                            stringResource(R.string.opds_test_hint),
                            onClick = ::testCatalogs,
                            enabled = !busy && catalogs.isNotEmpty(),
                            showChevron = false,
                            trailing = {
                                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                    AnimatedContent(testing, label = "OPDS connection test", contentAlignment = Alignment.Center) { running ->
                                        if (running) LoadingIndicator(Modifier.size(36.dp))
                                        else Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(22.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                        )
                    }
                }
                AnimatedVisibility(message != 0) {
                    OpdsStatusCard(message.takeIf { it != 0 } ?: R.string.opds_save_error)
                }
            }
            item {
                Spacer(Modifier.height(SpaceBeforeHeading - ConnectedCardGap))
                val initialUrls = initialCatalogUrls
                if (initialUrls != null) {
                    SettingsSectionLabel(stringResource(R.string.opds_catalog_count, catalogs.size))
                    AnimatedVisibility(catalogs.isEmpty()) { OpdsStatusCard(R.string.opds_empty) }
                }
            }
            itemsIndexed(catalogs, key = { _, it -> it.url }) { index, catalog ->
                val appear = if (catalog.url in initialCatalogUrls.orEmpty()) null else tween<Float>(180)
                Box(Modifier.animateItem(fadeInSpec = appear, fadeOutSpec = null)) {
                    SettingsCardRow(index == 0, index == catalogs.lastIndex) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OpdsCatalogLabel(
                                testResults[catalog.url] ?: catalog,
                                checking = catalog.url in testingUrls,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(catalog.enabled, { enabled ->
                                update { it.map { entry -> if (entry.url == catalog.url) entry.copy(enabled = enabled) else entry } }
                            })
                            IconButton(onClick = { pendingDelete = catalog }) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.opds_remove))
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) AlertDialog(
        onDismissRequest = { if (!busy) adding = false },
        title = { Text(stringResource(R.string.opds_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OpdsAddressField(address, enabled = !busy) { address = it; linkError = 0 }
                AnimatedVisibility(busy) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                AnimatedVisibility(linkError != 0) {
                    OpdsStatusCard(linkError.takeIf { it != 0 } ?: R.string.opds_add_error)
                }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { adding = false }) { Text(stringResource(R.string.library_delete_cancel)) } },
        confirmButton = {
            TextButton(enabled = !busy && address.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    try {
                        attemptOpds {
                            val url = Opds.address(address)
                            val feed = Opds.feed(url)
                            val title = feed.title.ifBlank { Uri.parse(url).host.orEmpty() }
                            writes.withLock {
                                catalogs = store.update { entries ->
                                    if (entries.none { it.url == url }) entries + OpdsCatalog(url, title) else entries
                                }
                            }
                        }.onSuccess { adding = false; address = "" }
                            .onFailure { linkError = R.string.opds_add_error }
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.opds_add)) }
        },
    )
    pendingDelete?.let { catalog ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.opds_remove)) },
            text = { Text(stringResource(R.string.opds_remove_confirmation, catalog.title)) },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.library_delete_cancel)) } },
            confirmButton = { TextButton(onClick = {
                pendingDelete = null
                update { it.filterNot { entry -> entry.url == catalog.url } }
            }) { Text(stringResource(R.string.opds_remove)) } },
        )
    }
}

@Composable
internal fun OpdsCatalogLabel(catalog: OpdsCatalog, checking: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val status = when {
        checking -> R.string.opds_testing
        catalog.reachable == true || catalog.responseMillis != null -> R.string.opds_reachable
        catalog.reachable == false -> R.string.opds_unreachable
        else -> R.string.opds_not_tested
    }
    val statusLabel = stringResource(status)
    val color by animateColorAsState(targetValue = when (status) {
        R.string.opds_testing -> scheme.tertiary
        R.string.opds_reachable -> scheme.primary
        R.string.opds_unreachable -> scheme.error
        else -> scheme.outlineVariant
    }, label = "OPDS reachability")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(18.dp).semantics { contentDescription = statusLabel },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(9.dp).background(color, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(catalog.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(catalog.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedVisibility(catalog.responseMillis != null) {
                AnimatedContent(catalog.responseMillis, label = "OPDS latency") { millis ->
                    if (millis != null) Text(
                        stringResource(R.string.opds_response_time, millis),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun OpdsAddressField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.opds_url)) },
        enabled = enabled,
        singleLine = true,
        shape = FrogDialogTextFieldShape,
        trailingIcon = {
            IconButton(enabled = enabled, onClick = {
                val clip = clipboard?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val item = clip.getItemAt(0)
                    val pasted = (item.text?.toString() ?: item.uri?.toString())?.trim()
                    if (!pasted.isNullOrBlank()) onChange(pasted)
                }
            }) {
                Icon(Icons.Outlined.ContentPaste, stringResource(R.string.opds_paste_url))
            }
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
        ),
    )
}

internal data class OpdsTransferProgress(val fraction: Float?)

private const val OpdsHome = "opds-home"
private const val OpdsBrowse = "opds-browse"

@Composable
internal fun OpdsSearchPage(initialKeyword: String, onBack: () -> Unit, onBookAdded: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FrogReaderApp
    val scope = rememberCoroutineScope()
    val nav = rememberNavController()
    var catalogs by remember { mutableStateOf(emptyList<OpdsCatalog>()) }
    var catalogsLoaded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf(initialKeyword) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var results by remember { mutableStateOf(emptyList<OpdsFeed>()) }
    var message by remember { mutableIntStateOf(0) }
    var searchProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var formatPicker by remember { mutableStateOf<OpdsPublication?>(null) }
    val downloadJobs = remember { mutableStateMapOf<String, Job>() }
    val downloadStatus = remember { mutableStateMapOf<String, Int>() }
    val downloadProgress = remember { mutableStateMapOf<String, MutableStateFlow<OpdsTransferProgress?>>() }
    val previousPages = remember { mutableStateMapOf<String, OpdsLink>() }
    val slots = remember { Semaphore(3) }
    val imports = remember { Mutex() }

    fun search() {
        val keyword = query.trim()
        if (keyword.isEmpty() || searchJob != null || !catalogsLoaded) return
        val selected = catalogs
        if (selected.isEmpty()) {
            message = R.string.opds_empty
            return
        }
        message = 0
        results = emptyList()
        searchProgress = 0 to selected.size
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val permits = Semaphore(6)
                val completed = coroutineScope {
                    selected.map { catalog -> async {
                        permits.withPermit {
                            val outcome = attemptOpds { Opds.search(catalog, keyword) }
                            val feed = outcome.getOrNull()
                            if (feed != null) results = results + feed
                            searchProgress = ((searchProgress?.first ?: 0) + 1) to selected.size
                            outcome
                        }
                    } }.awaitAll()
                }
                if (results.isEmpty()) {
                    message = if (completed.all { it.isSuccess }) R.string.opds_no_search else R.string.opds_connection_error
                } else if (results.none(OpdsFeed::hasContent)) {
                    message = R.string.opds_no_results
                }
            } finally {
                if (searchJob === coroutineContext[Job]) {
                    searchJob = null
                }
            }
        }
        searchJob = job
        job.start()
    }

    LaunchedEffect(app) {
        attemptOpds { app.opdsCatalogs.read() }
            .onSuccess { saved ->
                catalogs = saved.filter(OpdsCatalog::enabled)
                catalogsLoaded = true
                if (initialKeyword.isNotBlank()) search()
            }
            .onFailure { message = R.string.opds_save_error }
    }

    fun browse(link: OpdsLink, catalog: String = link.title) {
        // Every child has the same destination pattern but owns its own page state.
        nav.navigate("$OpdsBrowse/${Uri.encode(link.url)}/${Uri.encode(catalog.ifBlank { "OPDS" })}")
    }

    fun rememberPreviousPage(current: OpdsFeed, next: OpdsLink) {
        previousPages[next.url] = OpdsLink(current.url, current.title, "", emptySet())
    }

    fun download(book: OpdsPublication, link: OpdsLink) {
        val extension = Opds.extension(link) ?: return
        downloadStatus[book.key] = R.string.opds_queued
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var directory: File? = null
            try {
                slots.withPermit {
                    downloadStatus[book.key] = R.string.opds_downloading
                    val file = withContext(Dispatchers.IO) {
                        val temp = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "opds-").toFile()
                        directory = temp
                        val name = book.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim('.', ' ').take(64).ifBlank { "OPDS" }
                        File(temp, "$name.$extension")
                    }
                    val progress = MutableStateFlow<OpdsTransferProgress?>(null)
                    downloadProgress[book.key] = progress
                    try {
                        Opds.download(link, file) { progress.value = OpdsTransferProgress(it) }
                        downloadStatus[book.key] = R.string.opds_importing
                    } finally {
                        if (downloadProgress[book.key] === progress) downloadProgress.remove(book.key)
                    }
                    imports.withLock {
                        val added = app.bookRepository.importOpdsBook(Uri.fromFile(file))
                        downloadStatus[book.key] = R.string.opds_added
                        onBookAdded(added.id)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (downloadJobs[book.key] === coroutineContext[Job]) downloadStatus[book.key] = R.string.opds_download_error
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { directory?.deleteRecursively() }
                if (downloadJobs[book.key] === coroutineContext[Job]) downloadJobs.remove(book.key)
            }
        }
        downloadJobs[book.key] = job
        job.start()
    }

    fun selectBook(book: OpdsPublication) {
        when (downloadStatus[book.key]) {
            R.string.opds_added, R.string.opds_importing -> Unit
            R.string.opds_queued, R.string.opds_downloading -> {
                downloadJobs.remove(book.key)?.cancel()
                downloadStatus.remove(book.key)
                downloadProgress.remove(book.key)
            }
            else -> when (book.links.size) {
                0 -> {
                    downloadStatus[book.key] = R.string.opds_downloading
                    val job = scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val full = attemptOpds { Opds.acquisition(book) }.getOrNull()
                            if (downloadJobs[book.key] !== coroutineContext[Job]) return@launch
                            if (full == null) downloadStatus[book.key] = R.string.opds_download_error
                            else if (full.links.size == 1) download(full, full.links.first())
                            else {
                                downloadStatus.remove(book.key)
                                formatPicker = full
                            }
                        } finally {
                            if (downloadJobs[book.key] === coroutineContext[Job]) downloadJobs.remove(book.key)
                        }
                    }
                    downloadJobs[book.key] = job
                    job.start()
                }
                1 -> download(book, book.links.first())
                else -> formatPicker = book
            }
        }
    }

    val showFeed = rememberUpdatedState<@Composable (String, String) -> Unit> { url, catalog ->
        OpdsBrowsePage(
            url, catalog, { nav.popBackStack() }, ::browse, ::rememberPreviousPage, previousPages,
            downloadStatus, downloadProgress, ::selectBook,
        )
    }
    val showHome = rememberUpdatedState<@Composable () -> Unit> {
        SettingsPageScaffold(stringResource(R.string.opds_explore), onBack, scrollContent = false) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(ConnectedCardGap), contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "search") {
                    OpdsSearchInput(
                        query = query,
                        onQueryChange = { query = it },
                        searching = searchJob != null,
                        onSearch = ::search,
                        onCancel = {
                            searchJob?.cancel()
                            searchJob = null
                        },
                    )
                }
                item(key = "search-feedback") {
                    val progress = searchProgress
                    AnimatedContent(
                        targetState = when {
                            searchJob != null && progress != null -> -1
                            message != 0 -> message
                            else -> 0
                        },
                        label = "OPDS search feedback",
                    ) { state ->
                        when {
                            state == -1 -> OpdsSearchProgress(progress?.first ?: 0, progress?.second ?: 0)
                            state != 0 -> OpdsStatusCard(state, onRetry = if (state == R.string.opds_connection_error) ::search else null)
                        }
                    }
                }
                val visibleFeeds = results.filter(OpdsFeed::hasContent)
                if (visibleFeeds.isNotEmpty()) {
                    item(key = "results-heading") {
                        OpdsSectionHeading(stringResource(R.string.opds_results_count, visibleFeeds.sumOf { it.publicationCount }))
                    }
                    visibleFeeds.forEachIndexed { index, feed ->
                        opdsFeedItems(
                            feed, ::browse, downloadStatus, downloadProgress, ::selectBook,
                            keyPrefix = "search:${feed.url}:$index",
                            onPrevious = { link, catalog -> browse(link, catalog) },
                            onNext = { current, link, catalog ->
                                rememberPreviousPage(current, link)
                                browse(link, catalog)
                            },
                        )
                    }
                }
                if (catalogsLoaded) {
                    item(key = "catalogs-heading") {
                        OpdsSectionHeading(stringResource(R.string.opds_catalog_count, catalogs.size))
                    }
                }
                if (catalogs.isEmpty() && catalogsLoaded && message != R.string.opds_empty) {
                    item(key = "catalogs-empty") { OpdsStatusCard(R.string.opds_empty) }
                }
                itemsIndexed(catalogs, key = { _, it -> it.url }) { index, catalog ->
                    Box(Modifier.animateItem()) {
                        SettingsCardRow(index == 0, index == catalogs.lastIndex) {
                            SettingsNavigationRow(
                                Icons.Outlined.FolderOpen,
                                catalog.title, "",
                                onClick = { browse(OpdsLink(catalog.url, catalog.title, "", emptySet()), catalog.title) },
                            )
                        }
                    }
                }
            }
        }
    }
    AppSubpageNavHost(nav, OpdsHome) {
        composable(OpdsHome) { showHome.value() }
        composable(
            "$OpdsBrowse/{address}/{catalog}",
            arguments = listOf(navArgument("address") { type = NavType.StringType }, navArgument("catalog") { type = NavType.StringType }),
        ) { entry ->
            val url = entry.arguments?.getString("address")
            if (url != null) {
                key(entry.id) {
                    showFeed.value(url, entry.arguments?.getString("catalog").orEmpty())
                }
            }
        }
    }

    formatPicker?.let { book ->
        AlertDialog(onDismissRequest = { formatPicker = null }, title = { Text(stringResource(R.string.opds_choose_format)) },
            text = {
                SettingsRows {
                    book.links.distinctBy { it.url }.forEach { link ->
                        TextButton(onClick = { formatPicker = null; download(book, link) }) {
                            Text(Opds.extension(link).orEmpty().uppercase())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { formatPicker = null }) { Text(stringResource(R.string.settings_close)) } })
    }
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OpdsBrowsePage(
    url: String, catalog: String, onBack: () -> Unit, browse: (OpdsLink, String) -> Unit,
    rememberPrevious: (OpdsFeed, OpdsLink) -> Unit, previousPages: Map<String, OpdsLink>,
    statuses: Map<String, Int>, progress: Map<String, MutableStateFlow<OpdsTransferProgress?>>, onSelect: (OpdsPublication) -> Unit,
) {
    var pageUrl by rememberSaveable(url) { mutableStateOf(url) }
    var feed by remember(url) { mutableStateOf<OpdsFeed?>(null) }
    var displayedRequest by remember(url) { mutableStateOf(url) }
    var loading by remember(url) { mutableStateOf(true) }
    var failed by remember(url) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(pageUrl, retry) {
        loading = true
        failed = false
        try {
            val loaded = attemptOpds { Opds.feed(pageUrl, catalog) }.getOrNull()
            if (loaded == null) {
                failed = true
            } else {
                if (feed != null && loaded.url != feed?.url) listState.requestScrollToItem(0)
                displayedRequest = pageUrl
                feed = loaded
            }
        } finally {
            loading = false
        }
    }
    fun changePage(link: OpdsLink) {
        if (loading || link.url == displayedRequest) return
        loading = true
        if (link.url == pageUrl) retry++ else pageUrl = link.url
    }
    SettingsPageScaffold(feed?.title?.ifBlank { catalog } ?: catalog, onBack, scrollContent = false) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(ConnectedCardGap),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            val page = feed
            when {
                page != null && (page.hasContent || previousPages[displayedRequest] != null) -> {
                    opdsFeedItems(
                        page, browse, statuses, progress, onSelect, catalog,
                        previous = page.previous ?: previousPages[displayedRequest],
                        paging = loading,
                        onPrevious = { link, _ -> changePage(link) },
                        onNext = { current, link, _ ->
                            rememberPrevious(current, link)
                            changePage(link)
                        },
                    )
                    if (failed) item(key = "catalog-page-error") {
                        OpdsStatusCard(R.string.opds_connection_error, onRetry = { retry++ })
                    }
                }
                loading -> item(key = "catalog-loading") {
                    Box(Modifier.animateItem().fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator(Modifier.size(42.dp))
                    }
                }
                page == null -> item { OpdsStatusCard(R.string.opds_connection_error, onRetry = { retry++ }) }
                else -> item { OpdsStatusCard(R.string.opds_no_results, onRetry = { retry++ }) }
            }
        }
    }
}

@Composable
internal fun OpdsStatusCard(message: Int, onRetry: (() -> Unit)? = null) {
    val connectionIssue = message == R.string.opds_connection_error || message == R.string.opds_save_error
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIconCircle(
                if (connectionIssue) Icons.Outlined.CloudOff else Icons.Outlined.SearchOff,
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                stringResource(message),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (onRetry != null) TextButton(onClick = onRetry) {
                Text(stringResource(R.string.metadata_retry))
            }
        }
    }
}

@Composable
private fun OpdsSectionHeading(text: String) {
    Spacer(Modifier.height(SpaceBeforeHeading - ConnectedCardGap))
    SettingsSectionLabel(text, bottomSpacing = SpaceAfterHeading - ConnectedCardGap)
}

private val OpdsFeed.publicationCount: Int
    get() = publications.size + groups.sumOf { it.publicationCount }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.opdsFeedItems(
    feed: OpdsFeed, browse: (OpdsLink, String) -> Unit,
    statuses: Map<String, Int>, progress: Map<String, MutableStateFlow<OpdsTransferProgress?>>,
    onSelect: (OpdsPublication) -> Unit, catalog: String = feed.publications.firstOrNull()?.catalog ?: feed.title,
    keyPrefix: String = feed.url,
    previous: OpdsLink? = feed.previous,
    paging: Boolean = false,
    onPrevious: (OpdsLink, String) -> Unit,
    onNext: (OpdsFeed, OpdsLink, String) -> Unit,
) {
    val navigation = feed.navigation.distinctBy(OpdsLink::url)
    val books = feed.publications.distinctBy(OpdsPublication::key)
    itemsIndexed(navigation, key = { _, link -> "$keyPrefix:nav:${link.url}" }) { index, link ->
        Box(Modifier.animateItem()) {
            SettingsCardRow(index == 0, index == navigation.lastIndex) {
                SettingsNavigationRow(Icons.Outlined.FolderOpen, link.title.ifBlank { stringResource(R.string.opds_open_catalog) }, "",
                    onClick = { browse(link, catalog) })
            }
        }
    }
    if (navigation.isNotEmpty() && books.isNotEmpty()) {
        item(key = "$keyPrefix:section-gap") { Spacer(Modifier.height(SpaceAfterHeading - ConnectedCardGap)) }
    }
    itemsIndexed(books, key = { _, book -> "$keyPrefix:book:${book.key}" }) { index, book ->
        Box(Modifier.animateItem()) {
            SettingsCardRow(index == 0, index == books.lastIndex) {
                val received = progress[book.key]?.collectAsState()?.value
                OpdsPublicationRow(book, statuses[book.key], received) { onSelect(book) }
            }
        }
    }
    feed.groups.forEachIndexed { index, group ->
        val groupKey = "$keyPrefix:group:$index"
        if (group.title.isNotBlank()) item(key = groupKey) { OpdsSectionHeading(group.title) }
        opdsFeedItems(
            group, browse, statuses, progress, onSelect, catalog, groupKey,
            paging = paging, onPrevious = onPrevious, onNext = onNext,
        )
    }
    val next = feed.next
    if (previous != null || next != null) {
        item(key = "$keyPrefix:pagination") {
            Column(Modifier.animateItem().fillMaxWidth().padding(top = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                ) {
                    if (previous != null) OutlinedButton(enabled = !paging, onClick = { onPrevious(previous, catalog) }) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.opds_previous))
                    }
                    if (next != null) OutlinedButton(enabled = !paging, onClick = { onNext(feed, next, catalog) }) {
                        Text(stringResource(R.string.opds_next))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
                    }
                }
                Column(
                    Modifier.height(8.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AnimatedVisibility(paging, enter = fadeIn(tween(160, delayMillis = 140)), exit = fadeOut()) {
                        LinearProgressIndicator(Modifier.width(96.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun OpdsSearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
    onCancel: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val submit = {
        if (query.isNotBlank() && !searching) {
            focus.clearFocus()
            onSearch()
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        singleLine = true,
        shape = FrogTextFieldShape,
        placeholder = { Text(stringResource(R.string.opds_keyword), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { submit() }),
        trailingIcon = {
            Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(
                    query.isNotEmpty() && !searching,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                ) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.library_search_clear))
                    }
                }
                FilledIconButton(
                    onClick = if (searching) onCancel else submit,
                    enabled = searching || query.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = colors.surfaceContainerHighest,
                        contentColor = colors.onSurfaceVariant,
                    ),
                ) {
                    AnimatedContent(searching, label = "OPDS search action") { active ->
                        Icon(
                            if (active) Icons.Outlined.Close else Icons.Outlined.Search,
                            stringResource(if (active) R.string.opds_cancel_search else R.string.opds_search),
                        )
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OpdsSearchProgress(done: Int, total: Int) {
    SettingsCard {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LoadingIndicator(Modifier.size(32.dp))
            Text(
                stringResource(R.string.opds_search_progress, done, total),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OpdsPublicationRow(book: OpdsPublication, status: Int?, progress: OpdsTransferProgress? = null, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val enabled = status != R.string.opds_added && status != R.string.opds_importing
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(width = 48.dp, height = 68.dp).clip(RoundedCornerShape(9.dp)).background(colors.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Book, null, tint = colors.onSecondaryContainer)
            book.coverUrl?.let { OpdsCoverPreview(it) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.author.isNotBlank() || book.catalog.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (book.author.isNotBlank()) OpdsMetadataChip(book.author, Modifier.weight(1f, fill = false))
                    if (book.catalog.isNotBlank()) OpdsMetadataChip(book.catalog, Modifier.weight(1f, fill = false))
                }
            }
            val textState = when {
                status != R.string.opds_downloading -> status
                progress == null -> R.string.opds_downloading
                progress.fraction == null -> R.string.opds_download_unknown_progress
                else -> R.string.opds_download_progress
            }
            val percent = ((progress?.fraction ?: 0f) * 100).roundToInt()
            AnimatedContent(
                targetState = textState to percent,
                contentKey = { it.first },
                label = "OPDS book download state",
            ) { (state, value) ->
                val label = when (state) {
                    R.string.opds_download_progress -> stringResource(state, value)
                    null -> ""
                    else -> stringResource(state)
                }
                if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (state == R.string.opds_download_error) colors.error else colors.onSurfaceVariant)
            }
            AnimatedContent(
                targetState = progress.takeIf { status == R.string.opds_downloading },
                contentKey = { it != null },
                label = "OPDS download indicator",
            ) { transfer ->
                if (transfer != null) {
                    val fraction = transfer.fraction
                    if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        val animated by androidx.compose.animation.core.animateFloatAsState(fraction, label = "OPDS book download progress")
                        LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(status, label = "OPDS download icon", contentAlignment = Alignment.Center) { state ->
                when (state) {
                    R.string.opds_importing -> LoadingIndicator(Modifier.size(25.dp))
                    R.string.opds_queued, R.string.opds_downloading ->
                        Icon(Icons.Outlined.Close, stringResource(R.string.opds_cancel_download), tint = colors.primary)
                    R.string.opds_added -> Icon(Icons.Outlined.CheckCircle, null, tint = colors.primary)
                    else -> Icon(Icons.Outlined.FileDownload, stringResource(R.string.opds_download_book), tint = colors.primary)
                }
            }
        }
    }
}

@Composable
private fun OpdsMetadataChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private val opdsCoverRequests = Semaphore(4)
private val opdsCoverCache = object : LruCache<String, ByteArray>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ByteArray): Int = value.size
}

@Composable
private fun OpdsCoverPreview(url: String) {
    val context = LocalContext.current
    val cover by produceState(opdsCoverCache.get(url), url) {
        value = opdsCoverCache.get(url) ?: opdsCoverRequests.withPermit {
            opdsCoverCache.get(url) ?: attemptOpds { Opds.coverPreview(url) }.getOrNull()
                ?.also { opdsCoverCache.put(url, it) }
        }
    }
    cover?.let { bytes ->
        val request = remember(context, url, bytes) {
            ImageRequest.Builder(context)
                .data(bytes)
                .memoryCacheKey("opds-cover:$url")
                .crossfade(true)
                .build()
        }
        AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
