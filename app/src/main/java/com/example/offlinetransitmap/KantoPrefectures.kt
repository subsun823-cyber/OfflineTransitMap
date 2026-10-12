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

    // 全国各地域（北海道、東北、中部、近畿、中国、四国、九州・沖縄）の都道県
    val otherRegionsList: List<PrefectureMapInfo> = listOf(
        // --- 北海道・東北 ---
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
        ),
        PrefectureMapInfo(
            id = "aomori",
            name = "青森県",
            region = "東北",
            fileName = "aomori.pmtiles",
            approximateSizeMb = 75,
            west = 139.50, south = 40.22, east = 141.68, north = 41.56,
            centerLat = 40.8244, centerLon = 140.7400,
            downloadUrl = "$DEFAULT_BASE_URL/aomori.pmtiles",
            roadFileName = "roads_aomori.db",
            roadApproximateSizeMb = 32
        ),
        PrefectureMapInfo(
            id = "iwate",
            name = "岩手県",
            region = "東北",
            fileName = "iwate.pmtiles",
            approximateSizeMb = 85,
            west = 140.66, south = 38.74, east = 142.07, north = 40.45,
            centerLat = 39.7036, centerLon = 141.1527,
            downloadUrl = "$DEFAULT_BASE_URL/iwate.pmtiles",
            roadFileName = "roads_iwate.db",
            roadApproximateSizeMb = 35
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
            id = "akita",
            name = "秋田県",
            region = "東北",
            fileName = "akita.pmtiles",
            approximateSizeMb = 70,
            west = 139.69, south = 38.90, east = 140.99, north = 40.52,
            centerLat = 39.7186, centerLon = 140.1024,
            downloadUrl = "$DEFAULT_BASE_URL/akita.pmtiles",
            roadFileName = "roads_akita.db",
            roadApproximateSizeMb = 30
        ),
        PrefectureMapInfo(
            id = "yamagata",
            name = "山形県",
            region = "東北",
            fileName = "yamagata.pmtiles",
            approximateSizeMb = 70,
            west = 139.54, south = 37.75, east = 140.64, north = 39.15,
            centerLat = 38.2404, centerLon = 140.3636,
            downloadUrl = "$DEFAULT_BASE_URL/yamagata.pmtiles",
            roadFileName = "roads_yamagata.db",
            roadApproximateSizeMb = 30
        ),
        PrefectureMapInfo(
            id = "fukushima",
            name = "福島県",
            region = "東北",
            fileName = "fukushima.pmtiles",
            approximateSizeMb = 90,
            west = 139.17, south = 36.78, east = 141.04, north = 37.98,
            centerLat = 37.7503, centerLon = 140.4676,
            downloadUrl = "$DEFAULT_BASE_URL/fukushima.pmtiles",
            roadFileName = "roads_fukushima.db",
            roadApproximateSizeMb = 38
        ),

        // --- 中部・甲信越・北陸・東海 ---
        PrefectureMapInfo(
            id = "niigata",
            name = "新潟県",
            region = "甲信越",
            fileName = "niigata.pmtiles",
            approximateSizeMb = 100,
            west = 137.64, south = 36.74, east = 139.90, north = 38.56,
            centerLat = 37.9022, centerLon = 139.0232,
            downloadUrl = "$DEFAULT_BASE_URL/niigata.pmtiles",
            roadFileName = "roads_niigata.db",
            roadApproximateSizeMb = 42
        ),
        PrefectureMapInfo(
            id = "toyama",
            name = "富山県",
            region = "北陸",
            fileName = "toyama.pmtiles",
            approximateSizeMb = 60,
            west = 136.78, south = 36.27, east = 137.76, north = 36.98,
            centerLat = 36.6953, centerLon = 137.2113,
            downloadUrl = "$DEFAULT_BASE_URL/toyama.pmtiles",
            roadFileName = "roads_toyama.db",
            roadApproximateSizeMb = 26
        ),
        PrefectureMapInfo(
            id = "ishikawa",
            name = "石川県",
            region = "北陸",
            fileName = "ishikawa.pmtiles",
            approximateSizeMb = 65,
            west = 136.24, south = 36.07, east = 137.37, north = 37.52,
            centerLat = 36.5947, centerLon = 136.6256,
            downloadUrl = "$DEFAULT_BASE_URL/ishikawa.pmtiles",
            roadFileName = "roads_ishikawa.db",
            roadApproximateSizeMb = 28
        ),
        PrefectureMapInfo(
            id = "fukui",
            name = "福井県",
            region = "北陸",
            fileName = "fukui.pmtiles",
            approximateSizeMb = 55,
            west = 135.45, south = 35.35, east = 136.83, north = 36.30,
            centerLat = 36.0652, centerLon = 136.2216,
            downloadUrl = "$DEFAULT_BASE_URL/fukui.pmtiles",
            roadFileName = "roads_fukui.db",
            roadApproximateSizeMb = 24
        ),
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
            id = "gifu",
            name = "岐阜県",
            region = "東海",
            fileName = "gifu.pmtiles",
            approximateSizeMb = 85,
            west = 136.27, south = 35.14, east = 137.65, north = 36.47,
            centerLat = 35.3912, centerLon = 136.7223,
            downloadUrl = "$DEFAULT_BASE_URL/gifu.pmtiles",
            roadFileName = "roads_gifu.db",
            roadApproximateSizeMb = 38
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
            id = "mie",
            name = "三重県",
            region = "東海",
            fileName = "mie.pmtiles",
            approximateSizeMb = 80,
            west = 135.85, south = 33.72, east = 136.99, north = 35.26,
            centerLat = 34.7303, centerLon = 136.5086,
            downloadUrl = "$DEFAULT_BASE_URL/mie.pmtiles",
            roadFileName = "roads_mie.db",
            roadApproximateSizeMb = 35
        ),

        // --- 近畿（関西） ---
        PrefectureMapInfo(
            id = "shiga",
            name = "滋賀県",
            region = "関西",
            fileName = "shiga.pmtiles",
            approximateSizeMb = 65,
            west = 135.85, south = 34.78, east = 136.46, north = 35.70,
            centerLat = 35.0045, centerLon = 135.8686,
            downloadUrl = "$DEFAULT_BASE_URL/shiga.pmtiles",
            roadFileName = "roads_shiga.db",
            roadApproximateSizeMb = 28
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
            id = "nara",
            name = "奈良県",
            region = "関西",
            fileName = "nara.pmtiles",
            approximateSizeMb = 60,
            west = 135.52, south = 33.85, east = 136.23, north = 34.79,
            centerLat = 34.6853, centerLon = 135.8328,
            downloadUrl = "$DEFAULT_BASE_URL/nara.pmtiles",
            roadFileName = "roads_nara.db",
            roadApproximateSizeMb = 26
        ),
        PrefectureMapInfo(
            id = "wakayama",
            name = "和歌山県",
            region = "関西",
            fileName = "wakayama.pmtiles",
            approximateSizeMb = 65,
            west = 134.99, south = 33.43, east = 135.99, north = 34.37,
            centerLat = 34.2260, centerLon = 135.1675,
            downloadUrl = "$DEFAULT_BASE_URL/wakayama.pmtiles",
            roadFileName = "roads_wakayama.db",
            roadApproximateSizeMb = 28
        ),

        // --- 中国・四国 ---
        PrefectureMapInfo(
            id = "tottori",
            name = "鳥取県",
            region = "中国",
            fileName = "tottori.pmtiles",
            approximateSizeMb = 45,
            west = 133.15, south = 35.13, east = 134.45, north = 35.62,
            centerLat = 35.5039, centerLon = 134.2381,
            downloadUrl = "$DEFAULT_BASE_URL/tottori.pmtiles",
            roadFileName = "roads_tottori.db",
            roadApproximateSizeMb = 20
        ),
        PrefectureMapInfo(
            id = "shimane",
            name = "島根県",
            region = "中国",
            fileName = "shimane.pmtiles",
            approximateSizeMb = 55,
            west = 131.66, south = 34.30, east = 133.39, north = 36.35,
            centerLat = 35.4723, centerLon = 133.0505,
            downloadUrl = "$DEFAULT_BASE_URL/shimane.pmtiles",
            roadFileName = "roads_shimane.db",
            roadApproximateSizeMb = 24
        ),
        PrefectureMapInfo(
            id = "okayama",
            name = "岡山県",
            region = "中国",
            fileName = "okayama.pmtiles",
            approximateSizeMb = 80,
            west = 133.34, south = 34.30, east = 134.42, north = 35.36,
            centerLat = 34.6618, centerLon = 133.9350,
            downloadUrl = "$DEFAULT_BASE_URL/okayama.pmtiles",
            roadFileName = "roads_okayama.db",
            roadApproximateSizeMb = 34
        ),
        PrefectureMapInfo(
            id = "hiroshima",
            name = "広島県",
            region = "中国",
            fileName = "hiroshima.pmtiles",
            approximateSizeMb = 95,
            west = 132.03, south = 34.03, east = 133.51, north = 35.10,
            centerLat = 34.3966, centerLon = 132.4596,
            downloadUrl = "$DEFAULT_BASE_URL/hiroshima.pmtiles",
            roadFileName = "roads_hiroshima.db",
            roadApproximateSizeMb = 40
        ),
        PrefectureMapInfo(
            id = "yamaguchi",
            name = "山口県",
            region = "中国",
            fileName = "yamaguchi.pmtiles",
            approximateSizeMb = 70,
            west = 130.75, south = 33.71, east = 132.25, north = 34.62,
            centerLat = 34.1861, centerLon = 131.4705,
            downloadUrl = "$DEFAULT_BASE_URL/yamaguchi.pmtiles",
            roadFileName = "roads_yamaguchi.db",
            roadApproximateSizeMb = 30
        ),
        PrefectureMapInfo(
            id = "tokushima",
            name = "徳島県",
            region = "四国",
            fileName = "tokushima.pmtiles",
            approximateSizeMb = 50,
            west = 133.62, south = 33.54, east = 134.82, north = 34.24,
            centerLat = 34.0658, centerLon = 134.5594,
            downloadUrl = "$DEFAULT_BASE_URL/tokushima.pmtiles",
            roadFileName = "roads_tokushima.db",
            roadApproximateSizeMb = 22
        ),
        PrefectureMapInfo(
            id = "kagawa",
            name = "香川県",
            region = "四国",
            fileName = "kagawa.pmtiles",
            approximateSizeMb = 55,
            west = 133.45, south = 34.01, east = 134.44, north = 34.57,
            centerLat = 34.3402, centerLon = 134.0434,
            downloadUrl = "$DEFAULT_BASE_URL/kagawa.pmtiles",
            roadFileName = "roads_kagawa.db",
            roadApproximateSizeMb = 24
        ),
        PrefectureMapInfo(
            id = "ehime",
            name = "愛媛県",
            region = "四国",
            fileName = "ehime.pmtiles",
            approximateSizeMb = 65,
            west = 132.01, south = 32.89, east = 133.69, north = 34.31,
            centerLat = 33.8417, centerLon = 132.7661,
            downloadUrl = "$DEFAULT_BASE_URL/ehime.pmtiles",
            roadFileName = "roads_ehime.db",
            roadApproximateSizeMb = 28
        ),
        PrefectureMapInfo(
            id = "kochi",
            name = "高知県",
            region = "四国",
            fileName = "kochi.pmtiles",
            approximateSizeMb = 55,
            west = 132.48, south = 32.70, east = 134.31, north = 33.88,
            centerLat = 33.5597, centerLon = 133.5311,
            downloadUrl = "$DEFAULT_BASE_URL/kochi.pmtiles",
            roadFileName = "roads_kochi.db",
            roadApproximateSizeMb = 24
        ),

        // --- 九州・沖縄 ---
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
            id = "saga",
            name = "佐賀県",
            region = "九州",
            fileName = "saga.pmtiles",
            approximateSizeMb = 45,
            west = 129.74, south = 32.96, east = 130.55, north = 33.62,
            centerLat = 33.2494, centerLon = 130.2988,
            downloadUrl = "$DEFAULT_BASE_URL/saga.pmtiles",
            roadFileName = "roads_saga.db",
            roadApproximateSizeMb = 20
        ),
        PrefectureMapInfo(
            id = "nagasaki",
            name = "長崎県",
            region = "九州",
            fileName = "nagasaki.pmtiles",
            approximateSizeMb = 75,
            west = 128.59, south = 32.57, east = 130.40, north = 34.73,
            centerLat = 32.7448, centerLon = 129.8737,
            downloadUrl = "$DEFAULT_BASE_URL/nagasaki.pmtiles",
            roadFileName = "roads_nagasaki.db",
            roadApproximateSizeMb = 30
        ),
        PrefectureMapInfo(
            id = "kumamoto",
            name = "熊本県",
            region = "九州",
            fileName = "kumamoto.pmtiles",
            approximateSizeMb = 85,
            west = 129.98, south = 32.08, east = 131.33, north = 33.19,
            centerLat = 32.7898, centerLon = 130.7417,
            downloadUrl = "$DEFAULT_BASE_URL/kumamoto.pmtiles",
            roadFileName = "roads_kumamoto.db",
            roadApproximateSizeMb = 36
        ),
        PrefectureMapInfo(
            id = "oita",
            name = "大分県",
            region = "九州",
            fileName = "oita.pmtiles",
            approximateSizeMb = 70,
            west = 130.84, south = 32.72, east = 132.09, north = 33.74,
            centerLat = 33.2382, centerLon = 131.6126,
            downloadUrl = "$DEFAULT_BASE_URL/oita.pmtiles",
            roadFileName = "roads_oita.db",
            roadApproximateSizeMb = 30
        ),
        PrefectureMapInfo(
            id = "miyazaki",
            name = "宮崎県",
            region = "九州",
            fileName = "miyazaki.pmtiles",
            approximateSizeMb = 65,
            west = 130.70, south = 31.35, east = 131.88, north = 32.84,
            centerLat = 31.9111, centerLon = 131.4239,
            downloadUrl = "$DEFAULT_BASE_URL/miyazaki.pmtiles",
            roadFileName = "roads_miyazaki.db",
            roadApproximateSizeMb = 28
        ),
        PrefectureMapInfo(
            id = "kagoshima",
            name = "鹿児島県",
            region = "九州",
            fileName = "kagoshima.pmtiles",
            approximateSizeMb = 85,
            west = 128.40, south = 30.20, east = 131.20, north = 32.32,
            centerLat = 31.5602, centerLon = 130.5581,
            downloadUrl = "$DEFAULT_BASE_URL/kagoshima.pmtiles",
            roadFileName = "roads_kagoshima.db",
            roadApproximateSizeMb = 35
        ),
        PrefectureMapInfo(
            id = "okinawa",
            name = "沖縄県",
            region = "沖縄",
            fileName = "okinawa.pmtiles",
            approximateSizeMb = 45,
            west = 127.60, south = 26.05, east = 128.35, north = 26.88,
            centerLat = 26.2124, centerLon = 127.6809,
            downloadUrl = "$DEFAULT_BASE_URL/okinawa.pmtiles",
            roadFileName = "roads_okinawa.db",
            roadApproximateSizeMb = 20
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
