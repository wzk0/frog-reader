package com.example.frogreader.data

import android.content.Context
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock

/**
 * The exact write sequence the reader uses: open a never-read book, then save a
 * page. Progress may only be dropped if the reading it belongs to cannot be
 * identified, so the first open must produce an id that later writes match.
 */
class ReaderProgressRoundTripTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context

    @Before fun setup() {
        context = mock(Context::class.java)
        given(context.filesDir).willReturn(temporary.root)
        given(context.cacheDir).willReturn(temporary.root)
        StoreFixture.seed(temporary.root, listOf(
            Book("fresh", "Never read", format = BookFormat.EPUB, addedAtMillis = 1),
        ))
    }

    private fun repository() = BookRepository(context)

    @Test fun `a book read for the first time keeps the page it was left on`() = runTest {
        val repo = repository()
        repo.markStarted("fresh")

        val readingId = repo.bookById("fresh")!!.activeReadingLogId
        assertNotNull("the first open must own a reading", readingId)

        repo.saveProgressForReading(
            "fresh",
            ReadingProgress(
                chapterIndex = 4,
                elementIndex = 180,
                scrollOffset = 42,
                // Most pages begin mid-paragraph; losing this lands on the page
                // before the one the reader was on.
                charOffset = 317,
                fraction = .31f,
            ),
            readingId,
        )

        // Reopening reads the three documents from disk afresh.
        val reopened = repository().bookById("fresh")!!
        assertEquals(180, reopened.progress.elementIndex)
        assertEquals(4, reopened.progress.chapterIndex)
        assertEquals(42, reopened.progress.scrollOffset)
        assertEquals(317, reopened.progress.charOffset)
        assertEquals(.31f, reopened.progress.fraction)
    }

    @Test fun `a second session continues from the previous page`() = runTest {
        val first = repository()
        first.markStarted("fresh")
        val id = first.bookById("fresh")!!.activeReadingLogId
        first.saveProgressForReading("fresh", ReadingProgress(elementIndex = 90), id)

        // The reader's second session: same book, same reading, new save.
        val second = repository()
        second.markStarted("fresh")
        val sameId = second.bookById("fresh")!!.activeReadingLogId
        assertEquals(id, sameId)
        second.saveProgressForReading("fresh", ReadingProgress(elementIndex = 240), sameId)

        assertEquals(240, repository().bookById("fresh")!!.progress.elementIndex)
        assertEquals(ReadingStatus.READING, repository().bookById("fresh")!!.status)
    }

    @Test fun `a save belonging to another reading is ignored`() = runTest {
        val repo = repository()
        repo.markStarted("fresh")
        val id = repo.bookById("fresh")!!.activeReadingLogId

        repo.saveProgressForReading("fresh", ReadingProgress(elementIndex = 500), readingLogId = "not-this-reading")

        assertEquals(0, repo.bookById("fresh")!!.progress.elementIndex)
        // The correct reading still writes.
        repo.saveProgressForReading("fresh", ReadingProgress(elementIndex = 500), id)
        assertEquals(500, repo.bookById("fresh")!!.progress.elementIndex)
    }
}