package com.example.frogreader.reader

import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.damagedChapterPlaceholder
import com.example.frogreader.data.model.damagedChapterTarget
import com.example.frogreader.ui.reader.damagedChapterTargets
import com.example.frogreader.ui.reader.withDamagedChapterTargets
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DamagedChapterTargetsTest {
    private fun paragraph(text: String) = ContentElement.Paragraph(AnnotatedString(text))
    private fun chapter(title: String, vararg elements: ContentElement) =
        Chapter(title, elements.toList())

    @Test fun `a damaged chapter points at the next readable one`() {
        val chapters = listOf(
            chapter("One", paragraph("a"), paragraph("b")),
            chapter("Two", damagedChapterPlaceholder()),
            chapter("Three", damagedChapterPlaceholder()),
            chapter("Four", paragraph("c")),
        )
        val starts = listOf(0, 2, 3, 4)
        val targets = damagedChapterTargets(chapters, starts)
        assertEquals(mapOf(1 to 4, 2 to 4), targets)
    }

    @Test fun `damage that runs to the end falls back to the previous chapter`() {
        val chapters = listOf(
            chapter("One", paragraph("a")),
            chapter("Two", damagedChapterPlaceholder()),
            chapter("Three", damagedChapterPlaceholder()),
        )
        val targets = damagedChapterTargets(chapters, listOf(0, 1, 2))
        assertEquals(mapOf(1 to 0, 2 to 0), targets)
    }

    @Test fun `a wholly damaged book has nothing to jump to`() {
        val chapters = listOf(
            chapter("One", damagedChapterPlaceholder()),
            chapter("Two", damagedChapterPlaceholder()),
        )
        assertEquals(mapOf(0 to -1, 1 to -1), damagedChapterTargets(chapters, listOf(0, 1)))
    }

    @Test fun `resolved placeholders carry the flat index and keep their text`() {
        val chapters = listOf(chapter("One", damagedChapterPlaceholder(), paragraph("a")))
        val resolved = chapters.withDamagedChapterTargets(mapOf(0 to 7))
        val marker = resolved.single().elements.first()
        assertEquals(7, marker.damagedChapterTarget())
        // Invisible stand-in text, tall enough for the notice that replaces it.
        assertTrue((marker as ContentElement.Paragraph).text.text.isNotBlank())
        assertTrue(marker.text.text.all { it == '\u200b' || it == '\n' })
        assertEquals(null, resolved.single().elements[1].damagedChapterTarget())
    }

    @Test fun `ordinary chapters are left untouched`() {
        val chapters = listOf(chapter("One", paragraph("a")))
        assertTrue(damagedChapterTargets(chapters, listOf(0)).isEmpty())
        assertEquals(chapters, chapters.withDamagedChapterTargets(emptyMap()))
    }
}
