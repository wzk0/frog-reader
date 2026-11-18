package com.example.frogreader.ui.library

import com.example.frogreader.data.LibrarySortMode
import com.example.frogreader.data.model.Book

/**
 * One folder's worth of books, ready to draw under a heading.
 *
 * [folder] is the path the storage provider reported when the book was
 * imported, relative to that provider's root. Blank means the book carries no
 * folder at all — imported before this was recorded, restored from an older
 * backup, or picked from a provider that does not expose a path — and the
 * library shows those together under a localized "unsorted" heading rather
 * than hiding them.
 */
internal data class FolderSection(
    val folder: String,
    val books: List<Book>,
)

/**
 * What the grid draws, in order: a book or shelf slot, or a folder heading.
 *
 * A heading is a slot rather than a new kind of [LibraryEntry] on purpose: it is
 * a drawing concern for one screen, and the entry stream is shared with
 * selection, drag targets and the repository. Nothing outside the grid needs to
 * know that a folder heading exists.
 */
internal sealed interface LibrarySlot {
    /** LazyGrid key. Type-prefixed so a book id can never equal a folder's. */
    val id: String

    data class Entry(val entry: LibraryEntry) : LibrarySlot {
        override val id: String get() = entry.id
    }

    data class Folder(val folder: String, val bookCount: Int) : LibrarySlot {
        override val id: String get() = "f:$folder"
    }
}

/**
 * Turns the ordered entry stream into grid slots.
 *
 * Only folder grouping changes the shape of the stream: shelves keep their own
 * slots at the top (they are containers, not folders), then each folder gets a
 * heading and the books that live in it, in the order [arrangeEntries] already
 * gave them. Every other mode draws one slot per entry exactly as before.
 */
internal fun librarySlots(
    entries: List<LibraryEntry>,
    mode: LibrarySortMode,
    searching: Boolean,
): List<LibrarySlot> {
    if (searching || mode != LibrarySortMode.FOLDER) {
        return entries.map { LibrarySlot.Entry(it) }
    }

    val slots = ArrayList<LibrarySlot>(entries.size + 4)
    entries.filterIsInstance<LibraryEntry.ShelfEntry>().forEach { slots += LibrarySlot.Entry(it) }
    folderSections(entries).forEach { section ->
        slots += LibrarySlot.Folder(section.folder, section.books.size)
        section.books.forEach { book -> slots += LibrarySlot.Entry(LibraryEntry.BookEntry(book)) }
    }
    return slots
}

/**
 * Applies the user's chosen order to the grid stream.
 *
 * Shelves are never sorted into the books: they are containers the user made,
 * and a shelf does not have a file name to compare. They keep their own order
 * and stay at the top whenever the library is no longer ordered by time.
 *
 * [LibrarySortMode.RECENT] returns [entries] untouched: the repository already
 * ordered that stream, and re-sorting it here would only lose its tie-breaks.
 */
internal fun arrangeEntries(
    entries: List<LibraryEntry>,
    mode: LibrarySortMode,
): List<LibraryEntry> {
    if (mode == LibrarySortMode.RECENT) return entries

    val shelves = entries.filterIsInstance<LibraryEntry.ShelfEntry>()
    val books = entries.filterIsInstance<LibraryEntry.BookEntry>().map { it.book }

    val ordered = when (mode) {
        LibrarySortMode.RECENT -> books
        LibrarySortMode.FILENAME -> books.sortedWith(ByName)
        LibrarySortMode.FOLDER -> books.sortedWith(ByFolder)
    }

    return shelves + ordered.map { LibraryEntry.BookEntry(it) }
}

/** File name first; the folder only breaks ties. */
private val ByName: Comparator<Book> = Comparator { a, b ->
    var result = NaturalOrder.compare(a.sortName, b.sortName)
    if (result != 0) return@Comparator result
    result = NaturalOrder.compare(a.sortFolder, b.sortFolder)
    if (result != 0) return@Comparator result
    a.id.compareTo(b.id)
}

/** Folder first, then file name inside it — the grouped order. */
internal val ByFolder: Comparator<Book> = Comparator { a, b ->
    var result = NaturalOrder.compare(a.sortFolder, b.sortFolder)
    if (result != 0) return@Comparator result
    result = NaturalOrder.compare(a.sortName, b.sortName)
    if (result != 0) return@Comparator result
    a.id.compareTo(b.id)
}

/**
 * Cuts an already folder-ordered stream into sections, one per folder.
 *
 * The unsorted group goes last: a book with no known folder is the one thing
 * here the user cannot find by looking at their own disk, so it belongs at the
 * end rather than ahead of folders they recognise.
 */
internal fun folderSections(entries: List<LibraryEntry>): List<FolderSection> {
    val sections = LinkedHashMap<String, MutableList<Book>>()
    for (entry in entries) {
        if (entry !is LibraryEntry.BookEntry) continue
        // The path's own separators are part of the folder's identity: two
        // folders called `Novels` in different places are not one folder.
        sections.getOrPut(entry.book.sortFolder) { mutableListOf() } += entry.book
    }

    return sections.entries
        .map { (folder, books) -> FolderSection(folder, books) }
        .sortedWith(
            compareBy<FolderSection> { if (it.folder.isEmpty()) 1 else 0 }
                .thenComparator { a, b -> NaturalOrder.compare(a.folder, b.folder) },
        )
}

/**
 * Compares two names the way a person reads a numbered shelf: `2 Book` before
 * `10 Book`, and case ignored.
 *
 * The user's library is named `01 Dune.epub`, `02 …` on disk. Plain string
 * comparison mostly happens to work on that — the numbers are zero-padded — but
 * it puts `10` before `9` the moment one file is not, and it sorts every CJK
 * title by code point, which is neither pinyin nor stroke order. Nothing here
 * can fix the second (that needs a collator with locale data); the first is
 * what this exists for.
 */
internal object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var endA = i
                while (endA < a.length && a[endA].isDigit()) endA++
                var endB = j
                while (endB < b.length && b[endB].isDigit()) endB++
                val digitsA = a.substring(i, endA).trimStart('0')
                val digitsB = b.substring(j, endB).trimStart('0')
                if (digitsA.length != digitsB.length) return digitsA.length - digitsB.length
                val numeric = digitsA.compareTo(digitsB)
                if (numeric != 0) return numeric
                // Same number written `01` and `1`: equal so far, and the rest
                // of the name decides. Padding only breaks a full tie, which
                // callers do with their own stable tie-breaker.
                i = endA
                j = endB
                continue
            }
            val folded = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (folded != 0) return folded
            i++
            j++
        }
        return (a.length - i).compareTo(b.length - j)
    }
}