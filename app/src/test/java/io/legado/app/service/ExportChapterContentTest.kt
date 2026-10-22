package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportChapterContentTest {

    @Test
    fun keepsAvailableContentUnchanged() {
        assertEquals("chapter body", resolveExportChapterContent("chapter body", false))
    }

    @Test
    fun skipsOrdinaryChapterWithoutContent() {
        assertNull(resolveExportChapterContent(null, false))
    }

    @Test
    fun keepsVolumeWithoutContentAsEmptySection() {
        assertEquals("", resolveExportChapterContent(null, true))
    }

    @Test
    fun keepsAvailableEmptyContent() {
        assertEquals("", resolveExportChapterContent("", false))
    }

    @Test
    fun keepsLiteralNullContent() {
        assertEquals("null", resolveExportChapterContent("null", false))
    }

    @Test
    fun countsOnlyChaptersWithRealBody() {
        assertFalse(hasExportChapterBody(null))
        assertFalse(hasExportChapterBody(""))
        assertFalse(hasExportChapterBody("  \n\t "))
        assertTrue(hasExportChapterBody("chapter body"))
        assertTrue(hasExportChapterBody("<img src=\"cover.jpg\" />"))
    }
}
