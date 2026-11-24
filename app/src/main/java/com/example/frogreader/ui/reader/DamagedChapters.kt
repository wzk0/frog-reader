package com.example.frogreader.ui.reader

import com.example.frogreader.data.model.Chapter
import com.example.frogreader.data.model.damagedChapterTarget
import com.example.frogreader.data.model.withDamagedChapterTarget

/**
 * Where the "continue from a readable chapter" button of a damaged chapter
 * should land, as chapter index → flat reading-order item index.
 *
 * A chapter whose bytes the archive could not deliver keeps its slot in the
 * reading order with a marked placeholder (see `damagedChapterPlaceholder`).
 * The reader offers the next chapter that is not damaged, falling back to the
 * previous one when the damage runs to the end of the book; -1 means there is
 * nothing readable to jump to and the notice shows no button.
 */
internal fun damagedChapterTargets(
    chapters: List<Chapter>,
    chapterStarts: List<Int>,
): Map<Int, Int> {
    val damaged = chapters.map { chapter ->
        chapter.elements.any { element -> element.damagedChapterTarget() != null }
    }
    if (damaged.none { it }) return emptyMap()

    val previousReadable = IntArray(chapters.size) { -1 }
    var seen = -1
    for (index in chapters.indices) {
        previousReadable[index] = seen
        if (!damaged[index]) seen = index
    }

    val targets = mutableMapOf<Int, Int>()
    var nextReadable = -1
    for (index in chapters.indices.reversed()) {
        if (!damaged[index]) {
            nextReadable = index
            continue
        }
        val target = if (nextReadable >= 0) nextReadable else previousReadable[index]
        targets[index] = chapterStarts.getOrElse(target) { -1 }
    }
    return targets
}

/** Resolve each damaged chapter's placeholder to the flat item index it links to. */
internal fun List<Chapter>.withDamagedChapterTargets(
    targets: Map<Int, Int>,
): List<Chapter> {
    if (targets.isEmpty()) return this
    return mapIndexed { index, chapter ->
        val target = targets[index] ?: return@mapIndexed chapter
        Chapter(
            title = chapter.title,
            elements = chapter.elements.map { it.withDamagedChapterTarget(target) },
            depth = chapter.depth,
            publisherBoxes = chapter.publisherBoxes,
        )
    }
}