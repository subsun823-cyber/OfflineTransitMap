package com.example.offlinetransitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppUpdateAndRoadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testVersionComparison() {
        // 通常のマイナーバージョンアップ
        assertTrue(AppUpdateManager.isVersionNewer("1.1", "1.2"))
        assertFalse(AppUpdateManager.isVersionNewer("1.2", "1.1"))
        assertFalse(AppUpdateManager.isVersionNewer("1.1", "1.1"))

        // パッチバージョン
        assertTrue(AppUpdateManager.isVersionNewer("1.1", "1.1.1"))
        assertFalse(AppUpdateManager.isVersionNewer("1.1.1", "1.1"))

        // メジャーバージョン
        assertTrue(AppUpdateManager.isVersionNewer("1.9.9", "2.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("2.0", "1.9"))

        // ゼロ埋め同等
        assertFalse(AppUpdateManager.isVersionNewer("1.1", "1.1.0"))
        assertFalse(AppUpdateManager.isVersionNewer("1.1.0", "1.1"))
    }

    @Test
    fun testGlobalNodeIdEncoding() {
        val dbIdx0 = 0
        val localId0 = 12345
        val global0 = RoadRouter.makeGlobalNodeId(dbIdx0, localId0)
        assertEquals(dbIdx0.toLong(), global0 shr 32)
        assertEquals(localId0.toLong(), global0 and 0xFFFFFFFFL)

        val dbIdx3 = 3
        val localIdBig = 987654321
        val global3 = RoadRouter.makeGlobalNodeId(dbIdx3, localIdBig)
        assertEquals(dbIdx3.toLong(), global3 shr 32)
        assertEquals(localIdBig.toLong(), global3 and 0xFFFFFFFFL)
    }

    @Test
    fun testRoadDbValidation() {
        val validFile = tempFolder.newFile("test_roads.db")
        val header = ByteArray(1024)
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, header, 0, magic.size)
        validFile.writeBytes(header)

        // 正常なSQLiteファイル
        RoadDownloadManager.validateRoadDbFile(validFile)

        // 不正ヘッダー
        val invalidFile = tempFolder.newFile("corrupt.db")
        invalidFile.writeBytes(ByteArray(1024) { 0 })
        var caught = false
        try {
            RoadDownloadManager.validateRoadDbFile(invalidFile)
        } catch (_: Exception) {
            caught = true
        }
        assertTrue("不正なSQLiteヘッダーで例外がスローされること", caught)

        // サイズ不足
        val shortFile = tempFolder.newFile("short.db")
        shortFile.writeBytes(ByteArray(100))
        var shortCaught = false
        try {
            RoadDownloadManager.validateRoadDbFile(shortFile)
        } catch (_: Exception) {
            shortCaught = true
        }
        assertTrue("1024バイト未満で例外がスローされること", shortCaught)
    }

    @Test
    fun testNationalPrefecturesList() {
        assertEquals("日本全国47都道府県が全て定義されていること", 47, KantoPrefectures.nationalList.size)
        val tokyo = KantoPrefectures.findByIdInNational("tokyo")
        val osaka = KantoPrefectures.findByIdInNational("osaka")
        val saitama = KantoPrefectures.findByIdInNational("saitama")
        val hokkaido = KantoPrefectures.findByIdInNational("hokkaido")
        val okinawa = KantoPrefectures.findByIdInNational("okinawa")

        assertEquals("東京都", tokyo?.name)
        assertEquals("大阪府", osaka?.name)
        assertEquals("埼玉県", saitama?.name)
        assertEquals("北海道", hokkaido?.name)
        assertEquals("沖縄県", okinawa?.name)
        assertEquals("roads_saitama.db", saitama?.roadFileName)
        assertEquals("roads_tokyo.db", tokyo?.roadFileName)
        assertEquals("roads_osaka.db", osaka?.roadFileName)
        assertEquals("osaka.pmtiles", osaka?.fileName)
    }

    @Test
    fun testMapAppearanceNationalColorResolution() {
        // 全国のPMTilesレイヤーIDに対して正しいダークテーマ色が解決されること
        val tokyoWater = mapPaintColor(true, "water-tokyo", "fill-color", "#a6d4f7")
        val kanagawaWater = mapPaintColor(true, "water-kanagawa", "fill-color", "#a6d4f7")
        val osakaWater = mapPaintColor(true, "water-osaka", "fill-color", "#a6d4f7")
        val shizuokaRoad = mapPaintColor(true, "roads-highway-shizuoka", "line-color", "#ffcf6b")
        val yamanashiGreen = mapPaintColor(true, "natural-green-yamanashi", "fill-color", "#c4e0b7")

        assertEquals("#183e57", tokyoWater)
        assertEquals("#183e57", kanagawaWater)
        assertEquals("#183e57", osakaWater)
        assertEquals("#b9914e", shizuokaRoad)
        assertEquals("#263c30", yamanashiGreen)
    }
}
