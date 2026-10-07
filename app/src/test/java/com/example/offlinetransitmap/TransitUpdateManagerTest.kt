package com.example.offlinetransitmap

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class TransitUpdateManagerTest {

    @Test
    fun transitManifestDataModelRepresentsAttributesAccurately() {
        val file = TransitRemoteFile(
            id = "timetable",
            name = "統合時刻表データ",
            url = "https://example.invalid/timetable.db.gz",
            compressed = "gzip",
            size = 1234567,
            sha256 = "1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff",
            uncompressedSize = 9876543,
            uncompressedSha256 = "aaaabbbbccccddddeeeeffff1111222233334444555566667777888899990000",
            validityText = "2026/10/01〜2027/03/12",
            tripCount = 60866
        )
        val manifest = TransitManifest("20261007-01", "2026-10-07T12:00:00Z", file)

        assertEquals("20261007-01", manifest.version)
        assertEquals("timetable", manifest.file.id)
        assertEquals("gzip", manifest.file.compressed)
        assertEquals(60866, manifest.file.tripCount)
        assertEquals("2026/10/01〜2027/03/12", manifest.file.validityText)
    }

    @Test
    fun verifiesGzipDecompressionAndHashCalculation() {
        val testData = "Sample SQLite Timetable Payload Data for OfflineTransitMap".toByteArray(Charsets.UTF_8)
        val expectedSha256 = MessageDigest.getInstance("SHA-256")
            .digest(testData)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(testData) }
        val compressedBytes = bos.toByteArray()

        assertTrue(compressedBytes.isNotEmpty())

        val decompressedStream = java.util.zip.GZIPInputStream(ByteArrayInputStream(compressedBytes))
        val readBack = decompressedStream.readBytes()
        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(readBack)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

        assertEquals(expectedSha256, actualSha256)
        assertEquals(String(testData, Charsets.UTF_8), String(readBack, Charsets.UTF_8))
    }

    @Test
    fun appPreferencesPreservesAutoUpdateDefaults() {
        val prefs = AppPreferences()
        assertTrue(prefs.autoCheckTransitUpdates)
        assertEquals("", prefs.customManifestUrl)
    }
}
