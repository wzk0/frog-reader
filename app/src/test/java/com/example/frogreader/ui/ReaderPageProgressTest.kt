package com.example.frogreader.ui

import com.example.frogreader.ui.reader.savedPageProgress
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * The numbers the library card shows must be the numbers the pager shows.
 * Saving element positions made them disagree; these are the page-based ones.
 */
class ReaderPageProgressTest {

    private fun progress(page: Int, count: Int, lastPageOfChapter: Int) =
        savedPageProgress(page, count, lastPageOfChapter)

    @Test
    fun thePercentageIsThePageTheReaderIsOn() {
        // Page 40 of 100, counting from 0: the pager shows 41/100 and 40%.
        val (fraction, _) = progress(page = 40, count = 100, lastPageOfChapter = 61)
        assertEquals(0.404f, fraction, 0.001f)
    }

    @Test
    fun theFirstAndLastPagesAreExactlyZeroAndOne() {
        assertEquals(0f, progress(0, 321, 40).first, 0f)
        assertEquals(1f, progress(320, 321, 320).first, 0f)
    }

    @Test
    fun pagesLeftInTheChapterShrinkToZeroOnItsLastPage() {
        assertEquals(21, progress(page = 40, count = 100, lastPageOfChapter = 61).second)
        assertEquals(0, progress(page = 61, count = 100, lastPageOfChapter = 61).second)
    }

    @Test
    fun aSinglePageBookDoesNotDivideByZero() {
        val (fraction, left) = progress(page = 0, count = 1, lastPageOfChapter = 0)
        assertEquals(0f, fraction, 0f)
        assertEquals(0, left)
    }

    @Test
    fun nonsenseInputIsClampedRatherThanShown() {
        val (fraction, left) = progress(page = 7, count = 4, lastPageOfChapter = 2)
        assertEquals(1f, fraction, 0f)
        assertEquals(0, left)
        assert(abs(fraction) <= 1f)
    }
}