package com.example.offlinetransitmap

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class DataFileIOTest {
    @get:Rule val directory = TemporaryFolder()
    private val content = "new offline data".toByteArray()
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun target() = directory.newFile("data.db").apply { writeText("existing data") }

    @Test fun verifiedContentIsPublishedOnlyAfterCommit() {
        val target = target()
        val staged = DataFileIO.stage(content.inputStream(), target, content.size.toLong(), hash)
        assertEquals("existing data", target.readText())
        assertArrayEquals(content, staged.readBytes())
        DataFileIO.commit(staged, target)
        assertArrayEquals(content, target.readBytes())
        assertFalse(staged.exists())
    }

    @Test fun corruptOrTruncatedDownloadsKeepExistingData() {
        val target = target()
        for (bytes in listOf("wrong checksum!".toByteArray(), content.copyOf(3), content + byteArrayOf(1))) {
            assertThrows(IllegalArgumentException::class.java) {
                DataFileIO.stage(bytes.inputStream(), target, content.size.toLong(), hash)
            }
            assertEquals("existing data", target.readText())
            assertFalse(File(target.parentFile, target.name + ".part").exists())
        }
    }

    @Test fun checksumMismatchEvenWithCorrectLengthIsRejected() {
        val target = target()
        assertThrows(IllegalArgumentException::class.java) {
            DataFileIO.stage(ByteArray(content.size).inputStream(), target, content.size.toLong(), hash)
        }
        assertEquals("existing data", target.readText())
    }

    @Test fun readFailureCleansUpAndRetrySucceeds() {
        val target = target()
        val broken = object : InputStream() {
            var count = 0
            override fun read(): Int { if (count++ < 3) return 65; throw IOException("interrupted") }
        }
        assertThrows(IOException::class.java) { DataFileIO.stage(broken, target, content.size.toLong(), hash) }
        assertEquals("existing data", target.readText())
        assertFalse(File(target.parentFile, target.name + ".part").exists())
        val staged = DataFileIO.stage(ByteArrayInputStream(content), target, content.size.toLong(), hash.uppercase())
        DataFileIO.commit(staged, target)
        assertArrayEquals(content, target.readBytes())
    }

    @Test fun cancellationDuringCopyDoesNotPublishPartialData() {
        val target = target()
        assertThrows(IllegalStateException::class.java) {
            DataFileIO.stage(content.inputStream(), target, content.size.toLong(), hash) { error("cancelled") }
        }
        assertEquals("existing data", target.readText())
        assertFalse(File(target.parentFile, target.name + ".part").exists())
    }

    @Test fun insecureDistributionUrlIsRejectedBeforeConnecting() {
        assertThrows(IllegalArgumentException::class.java) {
            DataFileIO.download("http://127.0.0.1:1/file", target(), content.size.toLong(), hash) {}
        }
    }
}
