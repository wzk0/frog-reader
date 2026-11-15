package com.example.frogreader.ui.library

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.DuplicateMatch
import com.example.frogreader.data.FolderScanner
import com.example.frogreader.data.ImportMode
import com.example.frogreader.data.model.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** How far a batch has got, for the bar above the button. */
data class ScanImportProgress(val done: Int, val total: Int)

/**
 * Where a scanned folder's books should be filed, from the two switches the
 * screen offers.
 *
 * Only one of them can apply: a folder with subfolders is question A, a folder
 * without is question B. Resolving that here, once per batch, keeps the two
 * switches from being able to contradict each other halfway through a scan.
 */
internal data class ScanShelfPlan(
    /** One shelf per subfolder, named after it. */
    val perSubfolder: Boolean,
    /** One shelf for everything, named after the picked folder. */
    val wholeFolder: String?,
) {
    /** The shelf a file from [folder] belongs on, or null to leave it loose. */
    fun shelfFor(folder: String): String? = when {
        perSubfolder && folder.isNotEmpty() -> folder
        else -> wholeFolder
    }
}

internal fun scanShelfPlan(
    subfolders: List<String>,
    subfolderShelves: Boolean,
    folderShelf: Boolean,
    folderName: String,
): ScanShelfPlan = ScanShelfPlan(
    perSubfolder = subfolderShelves && subfolders.isNotEmpty(),
    wholeFolder = folderName.trim()
        .takeIf { folderShelf && subfolders.isEmpty() && it.isNotEmpty() },
)

/**
 * Everything the scan screen knows, and everything it does.
 *
 * Held in a `remember` rather than a ViewModel, deliberately. The screen is a
 * full-screen dialog, and inside a dialog `LocalViewModelStoreOwner` resolves
 * to the activity — a ViewModel created there is never cleared, so its scan
 * would go on parsing files long after the screen was gone. Tied to the
 * composition instead, closing the screen cancels the scan, which is what
 * closing it means.
 *
 * State is Compose snapshot state, not flows: a resolved row is a single
 * element swap in [rows], and the runtime already coalesces those into one
 * recomposition per frame. A flow of whole lists would rebuild the list on
 * every one of two hundred files.
 */
@Stable
class ScanFolderState(
    private val repository: BookRepository,
    private val scope: CoroutineScope,
    private val cacheDir: File,
) {

    val rows = mutableStateListOf<ScanRow>()

    /** Phase A is still walking the tree. */
    var scanning by mutableStateOf(true)
        private set

    /** The folder became unreadable — a one-shot grant lost to process death. */
    var accessLost by mutableStateOf(false)
        private set

    var query by mutableStateOf("")

    var importing by mutableStateOf<ScanImportProgress?>(null)
        private set

    /**
     * Whether a finished batch should also build one shelf per subfolder.
     *
     * On by default: the folders are already the user's own classification, and
     * a library scanned from `Books/` with `Novels/` and `Poems/` inside it is
     * asking to be filed that way. The row that shows this switch only appears
     * when the scan actually found a subfolder, so a flat folder never sees it.
     */
    var subfolderShelves by mutableStateOf(true)

    /**
     * Whether a folder with no subfolders becomes one shelf of its own.
     *
     * The same offer, for the flat case: a picked folder called `科幻` holding
     * twelve files is a shelf called 科幻, whether or not it has any folders
     * inside it. Only one of the two can apply at a time — the scan knows which
     * by the time it starts adding.
     */
    var folderShelf by mutableStateOf(true)

    /** Non-blank subfolders found so far, in natural order, for the switch row. */
    val subfolders: List<String>
        get() = rows.map { it.folder }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(NaturalOrder)

    /** Questions raised by the batch. Its own, so it cannot collide with the library's. */
    val conflicts = ConflictPrompt()

    val visibleRows: List<ScanRow> get() = filterScanRows(rows, query)

    val selectedCount: Int get() = rows.count { it.selected }

    val alreadyInLibraryCount: Int get() = rows.count { it.state == ScanRowState.IN_LIBRARY }

    private val coverDir = File(cacheDir, "scan-covers")
    private var started = false

    fun start(context: Context, treeUri: Uri) {
        if (started) return
        started = true
        coverDir.deleteRecursively()
        coverDir.mkdirs()

        // Phase B pulls from here as phase A fills it, so parsing starts on the
        // first file rather than waiting for the walk to finish.
        val work = Channel<String>(Channel.UNLIMITED)

        repeat(RESOLVE_WORKERS) {
            scope.launch {
                for (id in work) resolve(id)
            }
        }

        scope.launch {
            try {
                FolderScanner.enumerate(context, treeUri).collect { candidate ->
                    val row = ScanRow(
                        id = candidate.documentId,
                        uri = candidate.uri,
                        name = candidate.name,
                        format = candidate.format,
                        sizeBytes = candidate.sizeBytes,
                        lastModifiedMillis = candidate.lastModifiedMillis,
                        folder = candidate.folder,
                        // Something readable in the row from the first frame.
                        // The real title replaces it a moment later.
                        title = candidate.name.substringBeforeLast('.'),
                    )
                    rows += row
                    work.send(row.id)
                }
            } catch (e: SecurityException) {
                accessLost = true
            } finally {
                scanning = false
                work.close()
            }
        }
    }

    private suspend fun resolve(id: String) {
        val row = rows.firstOrNull { it.id == id } ?: return
        val inspected = withContext(Dispatchers.IO) {
            runCatching { repository.inspectFile(row.uri) }.getOrNull()
        }
        if (inspected == null) {
            update(id) { it.copy(state = ScanRowState.FAILED, selected = false) }
            return
        }

        val cover = inspected.coverBytes?.let { bytes ->
            withContext(Dispatchers.IO) {
                runCatching {
                    // Named by content hash, NOT by document id. A SAF document
                    // id looks like "primary:Download/book.epub" — colons and
                    // slashes and all — so using it as a file name asked to
                    // write into a directory that does not exist, the write
                    // threw, runCatching swallowed it, and every cover on this
                    // screen silently came out blank. The hash is hex.
                    File(coverDir, "${inspected.contentHash}.img").apply { writeBytes(bytes) }
                }.getOrNull()
            }
        }

        update(id) {
            it.copy(
                title = inspected.title,
                author = inspected.author,
                cover = cover,
                state = if (inspected.duplicateOf == null) {
                    ScanRowState.READY
                } else {
                    ScanRowState.IN_LIBRARY
                },
                match = inspected.duplicateOf?.match,
                // Whatever the user has already said about this row stands.
                // Rows start unticked, so a book that turns out to be one they
                // already own is still never ticked on its own — but a row
                // ticked before it finished resolving was ticked by them, and
                // unticking it behind their back is not ours to do.
                selected = it.selected,
            )
        }
    }

    private fun update(id: String, transform: (ScanRow) -> ScanRow) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = transform(rows[index])
    }

    fun toggleSelected(id: String) {
        update(id) { if (it.selectable) it.copy(selected = !it.selected) else it }
    }

    /**
     * Ticks or unticks everything on screen — not what the search is hiding.
     *
     * Everything means everything. This used to skip books already in the
     * library, which had two consequences: "select all" quietly did less than
     * it said, and because the header checkbox could then never reach a fully
     * ticked state it never flipped to checked — so it could not be used to
     * untick either, and the control was stuck. Books already held are still
     * never ticked ON THEIR OWN; being asked directly is a different matter.
     */
    fun setAllSelected(selected: Boolean) {
        val visible = visibleRows.mapTo(HashSet()) { it.id }
        // Gathered first, applied after: writing into the list while iterating
        // it is what SnapshotStateList's iterator refuses to allow.
        val updates = rows.mapIndexedNotNull { index, row ->
            if (row.id !in visible || !row.selectable) return@mapIndexedNotNull null
            if (row.selected == selected) null else index to row.copy(selected = selected)
        }
        updates.forEach { (index, row) -> rows[index] = row }
    }

    fun retry(id: String) {
        update(id) { it.copy(state = ScanRowState.PENDING) }
        scope.launch { resolve(id) }
    }

    fun answerConflict(choice: ConflictChoice, applyToRest: Boolean) {
        conflicts.answer(choice, applyToRest)
    }

    /**
     * Adds every ticked row, one at a time.
     *
     * Sequential on purpose: every commit serializes on the repository's index
     * lock anyway, and a conflict can only be put to the user one at a time.
     */
    fun addSelected(folderName: String, onFinished: (added: Int, failed: Int) -> Unit) {
        if (importing != null) return
        val targets = rows.filter { it.selected }
        if (targets.isEmpty()) return

        // Shelves → the books that go on them, in the order they were added.
        // Built during the batch because a shelf can only be made from the ids
        // the repository handed back.
        val shelved = LinkedHashMap<String, MutableList<String>>()
        // Resolved once, before the first book: one shelf per subfolder, or the
        // whole folder as one shelf. The two cannot both apply.
        val plan = scanShelfPlan(
            subfolders = subfolders,
            subfolderShelves = subfolderShelves,
            folderShelf = folderShelf,
            folderName = folderName,
        )

        scope.launch {
            var added = 0
            var failed = 0
            try {
                targets.forEachIndexed { index, row ->
                    importing = ScanImportProgress(done = index, total = targets.size)
                    val outcome = runCatching {
                        addOne(row, remaining = targets.size - index - 1)
                    }
                    when {
                        outcome.isFailure -> {
                            failed++
                            update(row.id) { it.copy(state = ScanRowState.FAILED, selected = false) }
                        }
                        else -> outcome.getOrThrow()?.let { book ->
                            added++
                            // Only books the user actually added, and only when
                            // they left the shelf switch on.
                            plan.shelfFor(row.folder)?.let { shelf ->
                                shelved.getOrPut(shelf) { mutableListOf() } += book.id
                            }
                            update(row.id) {
                                it.copy(
                                    state = ScanRowState.IN_LIBRARY,
                                    match = DuplicateMatch.SAME_FILE,
                                    selected = false,
                                )
                            }
                        } ?: update(row.id) { it.copy(selected = false) }
                    }
                }
                // One shelf per folder, reusing one the user already has under
                // that name rather than making a second with the same label.
                shelved.forEach { (folder, ids) -> runCatching { shelve(folder, ids) } }
            } finally {
                importing = null
                conflicts.reset()
            }
            // After the finally, not inside it: a batch cancelled by the screen
            // closing should not try to report itself to a screen that is gone.
            onFinished(added, failed)
        }
    }

    /** Files the books under a shelf named after their folder, making it if needed. */
    private suspend fun shelve(folder: String, bookIds: List<String>) {
        val existing = repository.shelves.value.firstOrNull { it.name == folder }
        if (existing == null) {
            repository.createShelf(bookIds, name = folder)
        } else {
            repository.addToShelf(existing.id, bookIds)
        }
    }

    /** The book that was added or replaced, or null when the user backed out. */
    private suspend fun addOne(row: ScanRow, remaining: Int): Book? {
        val staged = repository.stageImport(row.uri)
        var committed = false
        try {
            val duplicate = staged.duplicateOf
            val mode = if (duplicate == null) {
                ImportMode.New
            } else {
                when (conflicts.ask(repository.conflictFor(staged, duplicate, remaining))) {
                    ConflictChoice.CANCEL -> null
                    ConflictChoice.CLONE -> ImportMode.Clone
                    ConflictChoice.REPLACE -> ImportMode.Replace(duplicate.book.id)
                }
            } ?: return null

            val book = repository.commitImport(staged, mode)
            committed = true
            return book
        } finally {
            // Backing out of the screen mid-batch must not leave a book-sized
            // file in staging for the sweep to find half an hour later.
            if (!committed) withContext(NonCancellable) { repository.discardImport(staged) }
        }
    }

    /** Called when the screen goes away: the cached cover art has no other owner. */
    fun dispose() {
        conflicts.reset()
        coverDir.deleteRecursively()
    }

    private companion object {
        /**
         * Parsing is CPU- and IO-bound in roughly equal measure, and each worker
         * holds one book's cover in memory while it writes it out. Three keeps
         * the list filling visibly faster than one without putting a phone's
         * worth of cover art on the heap at once.
         */
        const val RESOLVE_WORKERS = 3
    }
}
