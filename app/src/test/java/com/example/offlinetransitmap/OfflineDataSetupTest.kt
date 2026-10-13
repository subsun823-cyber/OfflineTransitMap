package com.example.offlinetransitmap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineDataSetupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testCheckFastReadyFilesSuccess() {
        val mapFile = tempFolder.newFile("tokyo.pmtiles").apply {
            writeBytes(ByteArray(2048))
        }
        val dbFile = tempFolder.newFile("timetable.db").apply {
            writeBytes(ByteArray(4096))
        }

        val result = OfflineDataSetup.checkFastReadyFiles(mapFile, dbFile)
        assertTrue("正常なファイルが存在する場合は高速起動条件を満たすこと", result)
    }

    @Test
    fun testCheckFastReadyFilesMissingOrZeroSize() {
        val nonExistentMap = File(tempFolder.root, "missing.pmtiles")
        val dbFile = tempFolder.newFile("timetable.db").apply {
            writeBytes(ByteArray(4096))
        }
        assertFalse("地図ファイルが存在しない場合はfalseになること", OfflineDataSetup.checkFastReadyFiles(nonExistentMap, dbFile))

        val emptyMap = tempFolder.newFile("empty.pmtiles")
        assertFalse("地図ファイルが空(0バイト)の場合はfalseになること", OfflineDataSetup.checkFastReadyFiles(emptyMap, dbFile))
    }

    @Test
    fun testCheckFastReadyFilesWithActiveWalFile() {
        val mapFile = tempFolder.newFile("tokyo.pmtiles").apply {
            writeBytes(ByteArray(2048))
        }
        val dbFile = tempFolder.newFile("timetable.db").apply {
            writeBytes(ByteArray(4096))
        }
        // WALファイルが存在しサイズがある場合
        val walFile = File(dbFile.path + "-wal").apply {
            writeBytes(ByteArray(512))
        }

        val result = OfflineDataSetup.checkFastReadyFiles(mapFile, dbFile)
        assertFalse("未コミットのWALファイルが存在する場合はfalseになること", result)
    }
}
