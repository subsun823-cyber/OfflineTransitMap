package com.example.offlinetransitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KantoPrefecturesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testAllPrefecturesDefined() {
        val all = KantoPrefectures.all
        assertEquals("関東1都6県の7自治体が定義されていること", 7, all.size)
        val ids = all.map { it.id }.toSet()
        val expectedIds = setOf("tokyo", "kanagawa", "saitama", "chiba", "ibaraki", "tochigi", "gunma")
        assertEquals(expectedIds, ids)

        for (pref in all) {
            assertTrue("ファイル名は .pmtiles で終わること: ${pref.fileName}", pref.fileName.endsWith(".pmtiles"))
            assertTrue("概算サイズが正数であること", pref.approximateSizeMb > 0)
            assertTrue("境界: 経度西 < 経度東", pref.west < pref.east)
            assertTrue("境界: 緯度南 < 緯度北", pref.south < pref.north)
            assertTrue("中心座標が境界内にあること", pref.centerLat in pref.south..pref.north)
            assertTrue("中心座標が境界内にあること", pref.centerLon in pref.west..pref.east)
            assertTrue("ダウンロードURLが有効であること", pref.downloadUrl.startsWith("http"))
        }
    }

    @Test
    fun testFindById() {
        assertNotNull(KantoPrefectures.findById("tokyo"))
        assertNotNull(KantoPrefectures.findById("kanagawa"))
        assertEquals("東京都", KantoPrefectures.findById("tokyo")?.name)
        assertEquals("神奈川県", KantoPrefectures.findById("kanagawa")?.name)
        assertEquals(null, KantoPrefectures.findById("osaka"))
    }

    @Test
    fun testPrefectureCoordinatesCoverKeyStations() {
        // 東京駅
        val tokyo = KantoPrefectures.findById("tokyo")!!
        assertTrue(35.6812 in tokyo.south..tokyo.north && 139.7671 in tokyo.west..tokyo.east)

        // 横浜駅
        val kanagawa = KantoPrefectures.findById("kanagawa")!!
        assertTrue(35.4658 in kanagawa.south..kanagawa.north && 139.6227 in kanagawa.west..kanagawa.east)

        // 大宮駅
        val saitama = KantoPrefectures.findById("saitama")!!
        assertTrue(35.9063 in saitama.south..saitama.north && 139.6240 in saitama.west..saitama.east)

        // 千葉駅
        val chiba = KantoPrefectures.findById("chiba")!!
        assertTrue(35.6131 in chiba.south..chiba.north && 140.1132 in chiba.west..chiba.east)

        // 水戸駅
        val ibaraki = KantoPrefectures.findById("ibaraki")!!
        assertTrue(36.3708 in ibaraki.south..ibaraki.north && 140.4767 in ibaraki.west..ibaraki.east)

        // 宇都宮駅
        val tochigi = KantoPrefectures.findById("tochigi")!!
        assertTrue(36.5590 in tochigi.south..tochigi.north && 139.8983 in tochigi.west..tochigi.east)

        // 前橋駅
        val gunma = KantoPrefectures.findById("gunma")!!
        assertTrue(36.3833 in gunma.south..gunma.north && 139.0728 in gunma.west..gunma.east)
    }

    @Test
    fun testPmtilesValidation() {
        val validFile = tempFolder.newFile("valid.pmtiles")
        val header = ByteArray(128)
        val magic = "PMTiles".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, header, 0, magic.size)
        header[7] = 3.toByte() // v3
        validFile.writeBytes(header)

        // 例外が発生しないこと
        MapDownloadManager.validateMapFile(validFile)

        // 不正ヘッダー
        val invalidFile = tempFolder.newFile("invalid.pmtiles")
        invalidFile.writeBytes(ByteArray(128) { 0 })
        var caught = false
        try {
            MapDownloadManager.validateMapFile(invalidFile)
        } catch (e: Exception) {
            caught = true
        }
        assertTrue("不正なヘッダーで例外がスローされること", caught)

        // サイズ不足
        val shortFile = tempFolder.newFile("short.pmtiles")
        shortFile.writeBytes(ByteArray(50))
        var shortCaught = false
        try {
            MapDownloadManager.validateMapFile(shortFile)
        } catch (e: Exception) {
            shortCaught = true
        }
        assertTrue("127バイト未満で例外がスローされること", shortCaught)
    }

    @Test
    fun testOfflineMultiStyleJsonEmpty() {
        val emptyJson = offlineMultiStyleJson(emptyList(), """{"type":"FeatureCollection","features":[]}""", 0.0)
        assertFalse("protomaps-tokyo ソースが含まれないこと", emptyJson.contains(""""protomaps-tokyo":"""))
        assertFalse("protomaps ソースが含まれないこと", emptyJson.contains(""""protomaps":"""))
        assertTrue("stations ソースが含まれること", emptyJson.contains(""""stations":"""))
        assertTrue("route ソースが含まれること", emptyJson.contains(""""route":"""))
        assertTrue("nav-line ソースが含まれること", emptyJson.contains(""""nav-line":"""))
        assertTrue("destination ソースが含まれること", emptyJson.contains(""""destination":"""))
        assertTrue("background レイヤーが含まれること", emptyJson.contains(""""id": "background""""))
        assertTrue("stations レイヤーが含まれること", emptyJson.contains(""""id": "stations""""))
        assertFalse("都県別Protomapsレイヤーが含まれないこと", emptyJson.contains("landuse-green-tokyo"))
    }

    @Test
    fun testOfflineMultiStyleJsonMultiple() {
        val dummyTokyoFile = tempFolder.newFile("tokyo.pmtiles")
        val dummyKanagawaFile = tempFolder.newFile("kanagawa.pmtiles")
        val tokyoInfo = KantoPrefectures.findById("tokyo")!!
        val kanagawaInfo = KantoPrefectures.findById("kanagawa")!!

        val list = listOf(tokyoInfo to dummyTokyoFile, kanagawaInfo to dummyKanagawaFile)
        val styleString = offlineMultiStyleJson(list, """{"type":"FeatureCollection","features":[]}""", 12.0)

        assertTrue("protomaps-tokyo ソースが含まれること", styleString.contains(""""protomaps-tokyo":"""))
        assertTrue("protomaps-kanagawa ソースが含まれること", styleString.contains(""""protomaps-kanagawa":"""))
        assertTrue("tokyo.pmtiles への URL が含まれること", styleString.contains("tokyo.pmtiles"))
        assertTrue("kanagawa.pmtiles への URL が含まれること", styleString.contains("kanagawa.pmtiles"))

        assertTrue("background レイヤーが含まれること", styleString.contains(""""id": "background""""))
        assertTrue("landuse-green-tokyo レイヤーが含まれること", styleString.contains(""""id": "landuse-green-tokyo""""))
        assertTrue("landuse-green-kanagawa レイヤーが含まれること", styleString.contains(""""id": "landuse-green-kanagawa""""))
        assertTrue("roads-highway-tokyo レイヤーが含まれること", styleString.contains(""""id": "roads-highway-tokyo""""))
        assertTrue("roads-highway-kanagawa レイヤーが含まれること", styleString.contains(""""id": "roads-highway-kanagawa""""))
        assertTrue("route-bus レイヤーが含まれること", styleString.contains(""""id": "route-bus""""))
        assertTrue("stations レイヤーが含まれること", styleString.contains(""""id": "stations""""))
        assertTrue("stations-rail レイヤーが含まれること", styleString.contains(""""id": "stations-rail""""))
    }
}
