package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportChapterListTest {

    @Test
    fun refreshesLocalBookWithoutChapterList() {
        assertTrue(shouldRefreshChapterList(chapterCount = 0, localModified = false))
    }

    @Test
    fun keepsChapterListOfUnchangedLocalBook() {
        assertFalse(shouldRefreshChapterList(chapterCount = 12, localModified = false))
    }

    @Test
    fun refreshesLocalBookWhenFileChanged() {
        assertTrue(shouldRefreshChapterList(chapterCount = 12, localModified = true))
    }

    @Test
    fun refreshesLocalBookWithoutChapterListAndChangedFile() {
        assertTrue(shouldRefreshChapterList(chapterCount = 0, localModified = true))
    }
}
