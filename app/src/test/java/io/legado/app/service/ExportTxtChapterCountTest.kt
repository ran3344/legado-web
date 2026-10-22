package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExportTxtChapterCountTest {

    @Test
    fun `header is written but never counted as a chapter`() {
        val source = source("app/src/main/java/io/legado/app/service/ExportBookService.kt")

        val exportTxt = source
            .substringAfter("private suspend fun exportTxt(fileDoc: FileDoc, book: Book)")
            .substringBefore("private suspend fun exportPdf")
        assertTrue(exportTxt.contains("if (hasBody) {"))
        assertTrue(exportTxt.contains("chapterNum++"))
        assertFalse(exportTxt.contains("if (text.isNotEmpty()) {"))

        val getAllContents = source
            .substringAfter("private suspend fun getAllContents")
            .substringBefore("private fun getExportData")
        assertTrue(getAllContents.contains("append(sanitizeExportContent(qy), null, false)"))
        assertTrue(getAllContents.contains("append.invoke(result.text, result.srcList, result.hasBody)"))
    }

    private fun source(relativePath: String): String {
        return File(repositoryRoot(), relativePath).readText()
    }

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
