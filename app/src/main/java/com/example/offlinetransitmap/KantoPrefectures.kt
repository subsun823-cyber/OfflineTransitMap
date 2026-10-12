package com.example.offlinetransitmap

import android.content.Context
import java.io.File

/**
 * 各都府県のオフライン地図（PMTiles）および道路データ（SQLite）情報。
 */
data class PrefectureMapInfo(
    val id: String,
    val name: String,
    val region: String = "関東",
    val fileName: String,
    val approximateSizeMb: Int,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val centerLat: Double,
    val centerLon: Double,
    val downloadUrl: String,
    val roadFileName: String = "roads_${id}.db",
    val roadApproximateSizeMb: Int = 45,
    val roadDownloadUrl: String = "https://github.com/subsun823-cyber/OfflineTransitMap/releases/download/transit-data-latest/roads_${id}.db"
)

object KantoPrefectures {
    // 関東全域の境界（山梨・長野県境〜房総半島・犬吠埼、三浦半島〜栃木・群馬・福島県境）
    const val KANTO_WEST = 138.30
    const val KANTO_SOUTH = 34.90
    const val KANTO_EAST = 140.90
    const val KANTO_NORTH = 37.15

    const val KANTO_CENTER_LAT = 35.86
    const val KANTO_CENTER_LON = 139.65

    const val DEFAULT_BASE_URL = "https://github.com/subsun823-cyber/OfflineTransitMap/releases/download/transit-data-latest"

    fun getDownloadUrl(info: PrefectureMapInfo, customBaseUrl: String? = null): String {
        val base = customBaseUrl?.trim()?.removeSuffix("/")?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
        return "$base/${info.fileName}"
    }

    fun getRoadDownloadUrl(info: PrefectureMapInfo, customBaseUrl: String? = null): String {
        val base = customBaseUrl?.trim()?.removeSuffix("/")?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
        return "$base/${info.roadFileName}"
    }

    // 関東地方
    val kantoList: List<PrefectureMapInfo> = listOf(
        PrefectureMapInfo(
            id = "tokyo",
            name = "東京都",
            region = "関東",
            fileName = "tokyo.pmtiles",
            approximateSizeMb = 164,
            west = 138.94, south = 35.50, east = 139.92, north = 35.90,
            centerLat = 35.6895, centerLon = 139.6917,
            downloadUrl = "$DEFAULT_BASE_URL/tokyo.pmtiles",
            roadFileName = "roads_tokyo.db",
            roadApproximateSizeMb = 58
        ),
        PrefectureMapInfo(
            id = "kanagawa",
            name = "神奈川県",
            region = "関東",
            fileName = "kanagawa.pmtiles",
            approximateSizeMb = 140,
            west = 138.91, south = 35.12, east = 139.80, north = 35.68,
            centerLat = 35.4478, centerLon = 139.6425,
            downloadUrl = "$DEFAULT_BASE_URL/kanagawa.pmtiles",
            roadFileName = "roads_kanagawa.db",
            roadApproximateSizeMb = 50
        ),
        PrefectureMapInfo(
            id = "saitama",
            name = "埼玉県",
            region = "関東",
            fileName = "saitama.pmtiles",
            approximateSizeMb = 130,
            west = 138.71, south = 35.75, east = 139.90, north = 36.29,
            centerLat = 35.8617, centerLon = 139.6455,
            downloadUrl = "$DEFAULT_BASE_URL/saitama.pmtiles",
            roadFileName = "roads_saitama.db",
            roadApproximateSizeMb = 48
        ),
        PrefectureMapInfo(
            id = "chiba",
            name = "千葉県",
            region = "関東",
            fileName = "chiba.pmtiles",
            approximateSizeMb = 120,
            west = 139.74, south = 34.90, east = 140.88, north = 36.11,
            centerLat = 35.6073, centerLon = 140.1063,
            downloadUrl = "$DEFAULT_BASE_URL/chiba.pmtiles",
            roadFileName = "roads_chiba.db",
            roadApproximateSizeMb = 45
        ),
        PrefectureMapInfo(
            id = "ibaraki",
            name = "茨城県",
            region = "関東",
            fileName = "ibaraki.pmtiles",
            approximateSizeMb = 90,
            west = 139.68, south = 35.74, east = 140.85, north = 36.95,
            centerLat = 36.3418, centerLon = 140.4468,
            downloadUrl = "$DEFAULT_BASE_URL/ibaraki.pmtiles",
            roadFileName = "roads_ibaraki.db",
            roadApproximateSizeMb = 38
        ),
        PrefectureMapInfo(
            id = "tochigi",
            name = "栃木県",
            region = "関東",
            fileName = "tochigi.pmtiles",
            approximateSizeMb = 80,
            west = 139.32, south = 36.20, east = 140.29, north = 37.15,
            centerLat = 36.5658, centerLon = 139.8836,
            downloadUrl = "$DEFAULT_BASE_URL/tochigi.pmtiles",
            roadFileName = "roads_tochigi.db",
            roadApproximateSizeMb = 35
        ),
        PrefectureMapInfo(
            id = "gunma",
            name = "群馬県",
            region = "関東",
            fileName = "gunma.pmtiles",
            approximateSizeMb = 80,
            west = 138.39, south = 36.08, east = 139.66, north = 37.06,
            centerLat = 36.3907, centerLon = 139.0604,
            downloadUrl = "$DEFAULT_BASE_URL/gunma.pmtiles",
            roadFileName = "roads_gunma.db",
            roadApproximateSizeMb = 35
        )
    )

    // 甲信越・東海・関西・全国主要都府県
    val otherRegionsList: List<PrefectureMapInfo> = listOf(
        PrefectureMapInfo(
            id = "yamanashi",
            name = "山梨県",
            region = "甲信越",
            fileName = "yamanashi.pmtiles",
            approximateSizeMb = 50,
            west = 138.18, south = 35.17, east = 139.14, north = 35.98,
            centerLat = 35.6639, centerLon = 138.5683,
            downloadUrl = "$DEFAULT_BASE_URL/yamanashi.pmtiles",
            roadFileName = "roads_yamanashi.db",
            roadApproximateSizeMb = 25
        ),
        PrefectureMapInfo(
            id = "nagano",
            name = "長野県",
            region = "甲信越",
            fileName = "nagano.pmtiles",
            approximateSizeMb = 90,
            west = 137.31, south = 35.19, east = 138.75, north = 37.04,
            centerLat = 36.6513, centerLon = 138.1811,
            downloadUrl = "$DEFAULT_BASE_URL/nagano.pmtiles",
            roadFileName = "roads_nagano.db",
            roadApproximateSizeMb = 42
        ),
        PrefectureMapInfo(
            id = "shizuoka",
            name = "静岡県",
            region = "東海",
            fileName = "shizuoka.pmtiles",
            approximateSizeMb = 95,
            west = 137.48, south = 34.59, east = 139.15, north = 35.64,
            centerLat = 34.9756, centerLon = 138.3828,
            downloadUrl = "$DEFAULT_BASE_URL/shizuoka.pmtiles",
            roadFileName = "roads_shizuoka.db",
            roadApproximateSizeMb = 45
        ),
        PrefectureMapInfo(
            id = "aichi",
            name = "愛知県",
            region = "東海",
            fileName = "aichi.pmtiles",
            approximateSizeMb = 130,
            west = 136.67, south = 34.58, east = 137.84, north = 35.42,
            centerLat = 35.1802, centerLon = 136.9066,
            downloadUrl = "$DEFAULT_BASE_URL/aichi.pmtiles",
            roadFileName = "roads_aichi.db",
            roadApproximateSizeMb = 55
        ),
        PrefectureMapInfo(
            id = "osaka",
            name = "大阪府",
            region = "関西",
            fileName = "osaka.pmtiles",
            approximateSizeMb = 145,
            west = 135.09, south = 34.28, east = 135.75, north = 35.05,
            centerLat = 34.6937, centerLon = 135.5022,
            downloadUrl = "$DEFAULT_BASE_URL/osaka.pmtiles",
            roadFileName = "roads_osaka.db",
            roadApproximateSizeMb = 58
        ),
        PrefectureMapInfo(
            id = "kyoto",
            name = "京都府",
            region = "関西",
            fileName = "kyoto.pmtiles",
            approximateSizeMb = 85,
            west = 134.86, south = 34.78, east = 136.05, north = 35.77,
            centerLat = 35.0116, centerLon = 135.7681,
            downloadUrl = "$DEFAULT_BASE_URL/kyoto.pmtiles",
            roadFileName = "roads_kyoto.db",
            roadApproximateSizeMb = 38
        ),
        PrefectureMapInfo(
            id = "hyogo",
            name = "兵庫県",
            region = "関西",
            fileName = "hyogo.pmtiles",
            approximateSizeMb = 120,
            west = 134.25, south = 34.15, east = 135.47, north = 35.68,
            centerLat = 34.6913, centerLon = 135.1830,
            downloadUrl = "$DEFAULT_BASE_URL/hyogo.pmtiles",
            roadFileName = "roads_hyogo.db",
            roadApproximateSizeMb = 48
        ),
        PrefectureMapInfo(
            id = "miyagi",
            name = "宮城県",
            region = "東北",
            fileName = "miyagi.pmtiles",
            approximateSizeMb = 85,
            west = 140.27, south = 37.77, east = 141.68, north = 39.00,
            centerLat = 38.2682, centerLon = 140.8694,
            downloadUrl = "$DEFAULT_BASE_URL/miyagi.pmtiles",
            roadFileName = "roads_miyagi.db",
            roadApproximateSizeMb = 36
        ),
        PrefectureMapInfo(
            id = "fukuoka",
            name = "福岡県",
            region = "九州",
            fileName = "fukuoka.pmtiles",
            approximateSizeMb = 110,
            west = 129.98, south = 33.00, east = 131.19, north = 34.24,
            centerLat = 33.6064, centerLon = 130.4183,
            downloadUrl = "$DEFAULT_BASE_URL/fukuoka.pmtiles",
            roadFileName = "roads_fukuoka.db",
            roadApproximateSizeMb = 46
        ),
        PrefectureMapInfo(
            id = "hokkaido",
            name = "北海道",
            region = "北海道",
            fileName = "hokkaido.pmtiles",
            approximateSizeMb = 160,
            west = 139.33, south = 41.35, east = 145.82, north = 45.56,
            centerLat = 43.0642, centerLon = 141.3469,
            downloadUrl = "$DEFAULT_BASE_URL/hokkaido.pmtiles",
            roadFileName = "roads_hokkaido.db",
            roadApproximateSizeMb = 65
        )
    )

    val all: List<PrefectureMapInfo> = kantoList
    val nationalList: List<PrefectureMapInfo> = kantoList + otherRegionsList

    fun findById(id: String): PrefectureMapInfo? = all.firstOrNull { it.id == id }
    fun findByIdInNational(id: String): PrefectureMapInfo? = nationalList.firstOrNull { it.id == id }

    // 地図 (PMTiles) ファイル
    fun getFile(context: Context, info: PrefectureMapInfo): File {
        val dir = context.getExternalFilesDir("maps") ?: File(context.filesDir, "maps")
        return File(dir, info.fileName)
    }

    fun isDownloaded(context: Context, info: PrefectureMapInfo): Boolean {
        val file = getFile(context, info)
        return file.exists() && file.isFile && file.length() >= 127
    }

    fun getDownloadedList(context: Context): List<PrefectureMapInfo> =
        nationalList.filter { isDownloaded(context, it) }

    // 道路データ (SQLite roads_*.db) ファイル
    fun getRoadFile(context: Context, info: PrefectureMapInfo): File {
        val dir = context.getExternalFilesDir("roads") ?: File(context.filesDir, "roads")
        return File(dir, info.roadFileName)
    }

    fun isRoadDownloaded(context: Context, info: PrefectureMapInfo): Boolean {
        // 東京都は初期から roads.db が同梱されている場合も包含
        if (info.id == "tokyo") {
            val file = getRoadFile(context, info)
            if (file.exists() && file.isFile && file.length() > 1024) return true
            val defaultRoads = File(context.getExternalFilesDir("roads") ?: context.filesDir, "roads.db")
            if (defaultRoads.exists() && defaultRoads.length() > 1024) return true
        }
        val file = getRoadFile(context, info)
        return file.exists() && file.isFile && file.length() >= 1024
    }

    fun getRoadDownloadedList(context: Context): List<PrefectureMapInfo> =
        nationalList.filter { isRoadDownloaded(context, it) }

    fun isInAnyDownloadedArea(context: Context, lat: Double, lon: Double): Boolean {
        val downloaded = getDownloadedList(context)
        if (downloaded.isEmpty()) {
            return lat in KANTO_SOUTH..KANTO_NORTH && lon in KANTO_WEST..KANTO_EAST
        }
        return downloaded.any { lat in it.south..it.north && lon in it.west..it.east }
    }
}
