package com.example.frogreader.ui

import com.example.frogreader.data.LibrarySortMode
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.ui.library.LibraryEntry
import com.example.frogreader.ui.library.LibrarySlot
import com.example.frogreader.ui.library.NaturalOrder
import com.example.frogreader.ui.library.arrangeEntries
import com.example.frogreader.ui.library.folderSections
import com.example.frogreader.ui.library.librarySlots
import org.junit.Assert.assertEquals
import org.junit.Test

/** The library's optional orders: by file name, and grouped by folder. */
class LibrarySortTest {

    private fun book(
        id: String,
        name: String?,
        folder: String? = null,
        title: String = "Title $id",
    ) = Book(
        id = id,
        title = title,
        format = BookFormat.EPUB,
        fileName = "$id.epub",
        sourceName = name,
        sourceFolder = folder,
        addedAtMillis = 1_000,
    )

    @Test
    fun numberedNamesSortAsNumbers() {
        val names = listOf("10 Book", "2 Book", "01 A", "02 B", "1 C")
        assertEquals(
            listOf("01 A", "1 C", "02 B", "2 Book", "10 Book"),
            names.sortedWith(NaturalOrder),
        )
    }

    @Test
    fun fileNamesAreUsedWhenTheUserChoseThem() {
        val books = listOf(
            book("b", "02 Second.epub"),
            book("c", "10 Tenth.epub"),
            book("a", "01 First.epub"),
        )

        val entries = arrangeEntries(books.map { LibraryEntry.BookEntry(it) }, LibrarySortMode.FILENAME)

        assertEquals(listOf("b:a", "b:b", "b:c"), entries.map { it.id })
    }

    @Test
    fun recordsWithoutAFileNameFallBackToTheirTitle() {
        val books = listOf(
            book("b", name = null, title = "Zebra"),
            book("a", name = null, title = "Apple"),
        )

        val entries = arrangeEntries(books.map { LibraryEntry.BookEntry(it) }, LibrarySortMode.FILENAME)

        assertEquals(listOf("b:a", "b:b"), entries.map { it.id })
    }

    @Test
    fun shelvesStayInFrontWhenTheOrderIsNoLongerByTime() {
        val shelf = Shelf(id = "s1", bookIds = listOf("b"), createdAtMillis = 5, sortKey = 5)
        val entries = listOf(
            LibraryEntry.BookEntry(book("b", "09 In shelf.epub")),
            LibraryEntry.ShelfEntry(shelf, listOf(book("b", "09 In shelf.epub"))),
            LibraryEntry.BookEntry(book("a", "01 Loose.epub")),
        )

        val arranged = arrangeEntries(entries, LibrarySortMode.FILENAME)

        assertEquals(listOf("s:s1", "b:a", "b:b"), arranged.map { it.id })
    }

    @Test
    fun folderSectionsGroupAndSortWithUnsortedLast() {
        val books = listOf(
            book("x", "02 Two.epub", folder = "Novels"),
            book("y", "01 One.epub", folder = "Novels"),
            book("z", "01 Poetry.epub", folder = "Poems"),
            book("u", "03 Loose.epub", folder = null),
        )
        val entries = arrangeEntries(books.map { LibraryEntry.BookEntry(it) }, LibrarySortMode.FOLDER)

        val sections = folderSections(entries)

        assertEquals(listOf("Novels", "Poems", ""), sections.map { it.folder })
        assertEquals(listOf("y", "x"), sections.first().books.map { it.id })
    }

    @Test
    fun groupedSlotsPutAHeadingBeforeEachFolder() {
        val books = listOf(
            book("x", "02 Two.epub", folder = "Novels"),
            book("y", "01 One.epub", folder = "Novels"),
        )
        val entries = arrangeEntries(books.map { LibraryEntry.BookEntry(it) }, LibrarySortMode.FOLDER)

        val slots = librarySlots(entries, LibrarySortMode.FOLDER, searching = false)

        assertEquals(3, slots.size)
        assertEquals("f:Novels", slots[0].id)
        assertEquals(listOf("b:y", "b:x"), slots.drop(1).map { it.id })
        assertEquals(2, (slots[0] as LibrarySlot.Folder).bookCount)
    }

    @Test
    fun aSearchResultKeepsItsOwnOrder() {
        val books = listOf(book("b", "01 B.epub"), book("a", "02 A.epub"))
        val entries = books.map { LibraryEntry.BookEntry(it) }

        val slots = librarySlots(entries, LibrarySortMode.FOLDER, searching = true)

        assertEquals(listOf("b:b", "b:a"), slots.map { it.id })
    }
}