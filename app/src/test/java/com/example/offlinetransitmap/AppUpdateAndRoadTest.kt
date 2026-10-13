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

    @Test
    fun testRoadRouteResultProperties() {
        val path = listOf(
            Pair(35.6896, 139.7006),
            Pair(35.6900, 139.7010),
            Pair(35.6910, 139.7020)
        )
        val res = RoadRouteResult(
            path = path,
            distanceMeters = 350,
            durationMinutes = 5,
            isRoadBased = true
        )
        assertTrue("道路案内であること", res.isRoadBased)
        assertEquals(3, res.path.size)
        assertEquals(350, res.distanceMeters)
        assertEquals(5, res.durationMinutes)

        // フォールバック直線の場合
        val directRes = RoadRouteResult(
            path = listOf(Pair(35.6896, 139.7006), Pair(35.6910, 139.7020)),
            distanceMeters = 200,
            durationMinutes = 3,
            isRoadBased = false
        )
        assertFalse("直接フォールバック時はisRoadBasedがfalseであること", directRes.isRoadBased)
    }

    @Test
    fun testTravelDurationCalculations() {
        // 徒歩 (約16分)
        val walk1km = travelDurationMinutes(1000, TravelMode.WALK)
        // 自転車 (250m/分: 約4分)
        val bike1km = travelDurationMinutes(1000, TravelMode.BICYCLE)

        assertEquals("1kmの徒歩は約16分", 16, walk1km)
        assertEquals("1kmの自転車は約4分", 4, bike1km)
        assertTrue("自転車の方が所要時間が短いこと", bike1km < walk1km)

        // 公共交通機関モード（徒歩ベース）
        val transit1km = travelDurationMinutes(1000, TravelMode.TRANSIT)
        assertEquals("TRANSITの徒歩区間所要時間はWALKと同等", walk1km, transit1km)
    }
}

