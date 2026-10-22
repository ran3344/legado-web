package io.legado.app.model.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WebDavLogUploadTest {

    @Test
    fun `only uploads logs whose size or modify time changed`() {
        val unchanged = WebDavLogFile("appLog-1.txt", 100, 1_000)
        val grown = WebDavLogFile("appLog-2.txt", 200, 2_000)
        val uploaded = setOf(unchanged.stateKey)

        val pending = pendingLogUploads(listOf(unchanged, grown), uploaded)

        assertEquals(listOf(grown), pending)
    }

    @Test
    fun `drops sync states for removed or changed log files`() {
        val existing = WebDavLogFile("appLog-1.txt", 100, 1_000)
        val rotated = WebDavLogFile("appLog-0.txt", 50, 500)
        val changed = WebDavLogFile("appLog-1.txt", 120, 1_200)
        val uploaded = setOf(existing.stateKey, rotated.stateKey, changed.stateKey)

        val retained = retainExistingLogStates(listOf(existing), uploaded)

        assertEquals(setOf(existing.stateKey), retained)
        assertTrue(rotated.stateKey !in retained)
        assertTrue(changed.stateKey !in retained)
    }

    @Test
    fun `skips zero byte log files`() {
        val empty = Files.createTempFile("legado-empty", ".txt").toFile()
        val nonEmpty = Files.createTempFile("legado-log", ".txt").toFile()
        try {
            nonEmpty.writeText("log content")

            val selected = filterNonEmptyLogFiles(listOf(empty, nonEmpty))

            assertEquals(listOf(nonEmpty), selected)
        } finally {
            empty.delete()
            nonEmpty.delete()
        }
    }
}
