package com.example.offlinetransitmap

import android.content.Context
import java.io.File

/**
 * 関東各都県のオフライン地図（PMTiles）情報。
 */
data class PrefectureMapInfo(
    val id: String,
    val name: String,
    val fileName: String,
    val approximateSizeMb: Int,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val centerLat: Double,
    val centerLon: Double,
    val downloadUrl: String
)

object KantoPrefectures {
    // 関東全域の境界（山梨・長野県境〜房総半島・犬吠埼、三浦半島〜栃木・群馬・福島県境）
    const val KANTO_WEST = 138.30
    const val KANTO_SOUTH = 34.90
    const val KANTO_EAST = 140.90
    const val KANTO_NORTH = 37.15

    const val KANTO_CENTER_LAT = 35.86
    const val KANTO_CENTER_LON = 139.65

    private const val DEFAULT_BASE_URL = "https://github.com/subsun823-cyber/OfflineTransitMap/releases/download/transit-data-latest"

    val all: List<PrefectureMapInfo> = listOf(
        PrefectureMapInfo(
            id = "tokyo",
            name = "東京都",
            fileName = "tokyo.pmtiles",
            approximateSizeMb = 164,
            west = 138.94, south = 35.50, east = 139.92, north = 35.90,
            centerLat = 35.6895, centerLon = 139.6917,
            downloadUrl = "$DEFAULT_BASE_URL/tokyo.pmtiles"
        ),
        PrefectureMapInfo(
            id = "kanagawa",
            name = "神奈川県",
            fileName = "kanagawa.pmtiles",
            approximateSizeMb = 140,
            west = 138.91, south = 35.12, east = 139.80, north = 35.68,
            centerLat = 35.4478, centerLon = 139.6425,
            downloadUrl = "$DEFAULT_BASE_URL/kanagawa.pmtiles"
        ),
        PrefectureMapInfo(
            id = "saitama",
            name = "埼玉県",
            fileName = "saitama.pmtiles",
            approximateSizeMb = 130,
            west = 138.71, south = 35.75, east = 139.90, north = 36.29,
            centerLat = 35.8617, centerLon = 139.6455,
            downloadUrl = "$DEFAULT_BASE_URL/saitama.pmtiles"
        ),
        PrefectureMapInfo(
            id = "chiba",
            name = "千葉県",
            fileName = "chiba.pmtiles",
            approximateSizeMb = 120,
            west = 139.74, south = 34.90, east = 140.88, north = 36.11,
            centerLat = 35.6073, centerLon = 140.1063,
            downloadUrl = "$DEFAULT_BASE_URL/chiba.pmtiles"
        ),
        PrefectureMapInfo(
            id = "ibaraki",
            name = "茨城県",
            fileName = "ibaraki.pmtiles",
            approximateSizeMb = 90,
            west = 139.68, south = 35.74, east = 140.85, north = 36.95,
            centerLat = 36.3418, centerLon = 140.4468,
            downloadUrl = "$DEFAULT_BASE_URL/ibaraki.pmtiles"
        ),
        PrefectureMapInfo(
            id = "tochigi",
            name = "栃木県",
            fileName = "tochigi.pmtiles",
            approximateSizeMb = 80,
            west = 139.32, south = 36.20, east = 140.29, north = 37.15,
            centerLat = 36.5658, centerLon = 139.8836,
            downloadUrl = "$DEFAULT_BASE_URL/tochigi.pmtiles"
        ),
        PrefectureMapInfo(
            id = "gunma",
            name = "群馬県",
            fileName = "gunma.pmtiles",
            approximateSizeMb = 80,
            west = 138.39, south = 36.08, east = 139.66, north = 37.06,
            centerLat = 36.3907, centerLon = 139.0604,
            downloadUrl = "$DEFAULT_BASE_URL/gunma.pmtiles"
        )
    )

    fun findById(id: String): PrefectureMapInfo? = all.firstOrNull { it.id == id }

    fun getFile(context: Context, info: PrefectureMapInfo): File {
        val dir = context.getExternalFilesDir("maps") ?: File(context.filesDir, "maps")
        return File(dir, info.fileName)
    }

    fun isDownloaded(context: Context, info: PrefectureMapInfo): Boolean {
        val file = getFile(context, info)
        return file.exists() && file.isFile && file.length() >= 127
    }

    fun getDownloadedList(context: Context): List<PrefectureMapInfo> =
        all.filter { isDownloaded(context, it) }

    fun isInAnyDownloadedArea(context: Context, lat: Double, lon: Double): Boolean {
        val downloaded = getDownloadedList(context)
        if (downloaded.isEmpty()) {
            // 地図が未保存の場合は関東全域を許容
            return lat in KANTO_SOUTH..KANTO_NORTH && lon in KANTO_WEST..KANTO_EAST
        }
        return downloaded.any { lat in it.south..it.north && lon in it.west..it.east }
    }
}
