package com.example.frogreader.ui

import com.example.frogreader.ui.reader.ReaderOpeningPosition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps a half-laid-out opening from overwriting the stored
 * reading position: only the anchor may be written until it has been shown.
 */
class ReaderOpeningPositionTest {

    @Test
    fun aClampedFirstFrameIsNotWrittenBack() {
        val anchor = ReaderOpeningPosition(index = 180, scrollOffset = 40)

        // A prefix that stopped short of the saved item clamps to its own end.
        assertFalse(anchor.accepts(0))
        assertFalse(anchor.accepts(120))
        assertFalse(anchor.isSettled)
    }

    @Test
    fun showingTheAnchorUnlocksEveryLaterPosition() {
        val anchor = ReaderOpeningPosition(index = 180)

        anchor.presented(180)
        assertTrue(anchor.isSettled)
        assertTrue(anchor.accepts(181))
        assertTrue(anchor.accepts(0))
    }

    @Test
    fun reportingSomeOtherPageDoesNotUnlock() {
        val anchor = ReaderOpeningPosition(index = 180)

        anchor.presented(0)
        assertFalse(anchor.isSettled)
        // A clamp can only land before the anchor, never after it.
        assertFalse(anchor.accepts(3))
        assertTrue(anchor.accepts(300))
    }

    @Test
    fun aRealGestureWinsEvenBeforeTheAnchorIsShown() {
        val anchor = ReaderOpeningPosition(index = 180)

        anchor.userMoved()
        assertTrue(anchor.isSettled)
        assertTrue(anchor.accepts(200))
    }

    @Test
    fun theAnchorItselfMayAlwaysBeWritten() {
        val anchor = ReaderOpeningPosition(180)

        // Re-saving what was opened is harmless and keeps firstOpened stamps fresh.
        assertTrue(anchor.accepts(180))
    }

    @Test
    fun negativeStoredValuesAreTreatedAsTheTop() {
        val anchor = ReaderOpeningPosition(index = -5, scrollOffset = -20)

        assertTrue(anchor.accepts(0))
        assertTrue(anchor.accepts(1))
    }
}