package com.example.frogreader.ui.reader

/**
 * The position a reading session opened at, and the rule for when it may be
 * replaced by what is on screen.
 *
 * A book opens from `progress.json`, but the first frames are laid out from
 * whatever the parser has produced so far — an EPUB publishes a prefix as soon
 * as it reaches the saved element, a cached prefix may stop earlier, and the
 * paged quick pass covers one chapter. When any of those stops short of the
 * saved position, the list or pager can only clamp, and the clamped frame used
 * to be saved straight back: the real position was overwritten by the top of the
 * book, and every later open started from there.
 *
 * The stored anchor therefore stays authoritative until the reader either shows
 * it or the user deliberately reads somewhere else. This class is the whole
 * rule, so it can be tested without a device.
 */
internal class ReaderOpeningPosition(
    index: Int,
    scrollOffset: Int = 0,
    charOffset: Int = 0,
) {
    val index: Int = index.coerceAtLeast(0)
    val scrollOffset: Int = scrollOffset.coerceAtLeast(0)
    val charOffset: Int = charOffset.coerceAtLeast(0)

    private var shown = false
    private var movedByUser = false

    /** True once the anchor has been on screen, or the user has moved away. */
    val isSettled: Boolean get() = shown || movedByUser

    /** The reader reports that [flatIndex] is what it is showing now. */
    fun presented(flatIndex: Int) {
        if (flatIndex == index) shown = true
    }

    /** A real gesture: from here on any position may be written. */
    fun userMoved() {
        movedByUser = true
    }

    /**
     * Whether a frame at [flatIndex] may replace the stored position.
     *
     * A partial layout can only stop SHORT of the anchor, so a clamp always
     * lands before it — anything at or past the anchor is a place the reader
     * really reached, and stays writable while the rest of the book parses.
     */
    fun accepts(flatIndex: Int): Boolean = isSettled || flatIndex >= index
}