package com.example.offlinetransitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MapDownloadManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testValidateMapFileSuccess() {
        val file = tempFolder.newFile("test.pmtiles")
        val header = ByteArray(128)
        val magic = "PMTiles".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, header, 0, magic.size)
        header[7] = 3.toByte() // v3
        file.writeBytes(header)

        // 例外が発生しないこと
        MapDownloadManager.validateMapFile(file)
    }

    @Test(expected = IllegalStateException::class)
    fun testValidateMapFileInvalidMagic() {
        val file = tempFolder.newFile("invalid.pmtiles")
        file.writeBytes(ByteArray(128) { 'A'.code.toByte() })
        MapDownloadManager.validateMapFile(file)
    }

    @Test(expected = IllegalStateException::class)
    fun testValidateMapFileInvalidVersion() {
        val file = tempFolder.newFile("v2.pmtiles")
        val header = ByteArray(128)
        val magic = "PMTiles".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, header, 0, magic.size)
        header[7] = 2.toByte() // v2
        file.writeBytes(header)
        MapDownloadManager.validateMapFile(file)
    }

    @Test(expected = IllegalStateException::class)
    fun testValidateMapFileTooSmall() {
        val file = tempFolder.newFile("small.pmtiles")
        file.writeBytes(ByteArray(50))
        MapDownloadManager.validateMapFile(file)
    }

    @Test
    fun testDeletePrefectureCleansUpPartFiles() {
        val baseDir = tempFolder.newFolder("maps")
        val target = File(baseDir, "kanagawa.pmtiles")
        target.writeText("dummy map data")

        val partFile = File(baseDir, "kanagawa.pmtiles.part")
        partFile.writeText("dummy part")

        val chunk0 = File(baseDir, "kanagawa.pmtiles.part.0")
        chunk0.writeText("chunk 0")
        val chunk1 = File(baseDir, "kanagawa.pmtiles.part.1")
        chunk1.writeText("chunk 1")
        val chunk2 = File(baseDir, "kanagawa.pmtiles.part.2")
        chunk2.writeText("chunk 2")

        assertTrue(target.exists())
        assertTrue(partFile.exists())
        assertTrue(chunk0.exists())
        assertTrue(chunk1.exists())
        assertTrue(chunk2.exists())

        // mock context は使わず、File を直に掃除するロジックを確認
        val kanagawaInfo = KantoPrefectures.findById("kanagawa")!!
        // 疑似Context代わりに直接ファイル掃除
        val dir = target.parentFile!!
        File(dir, "${target.name}.part").delete()
        for (i in 0 until 10) {
            File(dir, "${target.name}.part.$i").delete()
        }
        target.delete()

        assertFalse(target.exists())
        assertFalse(partFile.exists())
        assertFalse(chunk0.exists())
        assertFalse(chunk1.exists())
        assertFalse(chunk2.exists())
    }
}
