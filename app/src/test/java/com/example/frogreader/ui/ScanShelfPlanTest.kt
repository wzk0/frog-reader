package com.example.frogreader.ui

import com.example.frogreader.ui.library.scanShelfPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scan screen's two shelf switches resolve to exactly one filing rule. */
class ScanShelfPlanTest {

    @Test
    fun subfoldersGiveOneShelfEach() {
        val plan = scanShelfPlan(
            subfolders = listOf("科幻", "历史"),
            subfolderShelves = true,
            folderShelf = true,
            folderName = "书库",
        )

        assertTrue(plan.perSubfolder)
        assertNull(plan.wholeFolder)
        assertEquals("科幻", plan.shelfFor("科幻"))
        // A file sitting at the picked folder's root has no folder of its own.
        assertNull(plan.shelfFor(""))
    }

    @Test
    fun aFlatFolderBecomesOneShelfNamedAfterIt() {
        val plan = scanShelfPlan(
            subfolders = emptyList(),
            subfolderShelves = true,
            folderShelf = true,
            folderName = "科幻",
        )

        assertFalse(plan.perSubfolder)
        assertEquals("科幻", plan.wholeFolder)
        assertEquals("科幻", plan.shelfFor(""))
    }

    @Test
    fun turningTheSwitchOffLeavesTheBooksLoose() {
        val flat = scanShelfPlan(emptyList(), subfolderShelves = true, folderShelf = false, folderName = "科幻")
        assertNull(flat.shelfFor(""))

        val nested = scanShelfPlan(listOf("科幻"), subfolderShelves = false, folderShelf = true, folderName = "书库")
        assertNull(nested.shelfFor("科幻"))
    }

    @Test
    fun aFolderTheProviderCannotNameMakesNoShelf() {
        val plan = scanShelfPlan(emptyList(), subfolderShelves = true, folderShelf = true, folderName = "   ")

        assertNull(plan.shelfFor(""))
    }
}