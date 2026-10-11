package com.example.offlinetransitmap

import kotlin.math.ceil

/**
 * JR東日本（首都圏・電車特定区間・山手線内）の営業キロおよび運賃計算エンジン
 *
 * オープンデータ（GTFS）で運賃表（faresテーブル）が提供されていないJR区間に対し、
 * 経由駅の駅間直線距離から営業キロを推定し、JR東日本の公式普通運賃（鉄道駅バリアフリー料金10円含む）を算出します。
 */
object JrFareCalculator {

    // 山手線内（環状線各駅および中央線神田〜代々木）の主要駅名
    private val YAMANOTE_ZONE_STATIONS = setOf(
        "東京", "有楽町", "新橋", "浜松町", "田町", "高輪ゲートウェイ", "品川",
        "大崎", "五反田", "目黒", "恵比寿", "渋谷", "原宿", "代々木", "新宿",
        "新大久保", "高田馬場", "目白", "池袋", "大塚", "巣鴨", "駒込", "田端",
        "西日暮里", "日暮里", "鶯谷", "上野", "御徒町", "秋葉原", "神田",
        "御茶ノ水", "水道橋", "飯田橋", "市ヶ谷", "四ツ谷", "信濃町", "千駄ケ谷"
    )

    /**
     * 指定された路線または事業者が JR東日本（JR線）であるかを判定
     */
    fun isJrRoute(routeId: String, operatorLabel: String): Boolean {
        if (routeId.startsWith("JR", ignoreCase = true)) return true
        if (operatorLabel.contains("JR", ignoreCase = true)) return true
        if (operatorLabel.contains("東日本")) return true
        return false
    }

    /**
     * 経由駅リストから営業キロ（km）を推定
     * 駅間直線距離の積算に線路曲率補正（約1.045倍）を掛け、1km未満の端数を切り上げ
     */
    fun estimateOperatingKm(stations: List<StationEntry>): Int {
        if (stations.size < 2) return 1
        var straightMeters = 0.0
        for (i in 0 until stations.size - 1) {
            val s1 = stations[i]
            val s2 = stations[i + 1]
            straightMeters += calcDistanceMeters(s1.lat, s1.lon, s2.lat, s2.lon)
        }
        val km = (straightMeters * 1.045) / 1000.0
        return ceil(km).toInt().coerceAtLeast(1)
    }

    /**
     * 経由駅がすべて山手線内駅であるかを判定
     */
    fun isAllYamanote(stations: List<StationEntry>): Boolean {
        if (stations.isEmpty()) return false
        return stations.all { st ->
            YAMANOTE_ZONE_STATIONS.any { name -> st.name.contains(name) }
        }
    }

    /**
     * 営業キロに基づく大人片道普通運賃（きっぷ運賃・バリアフリー料金10円込み）を計算
     */
    fun calculateFare(operatingKm: Int, isYamanoteOnly: Boolean = false): Int {
        val km = operatingKm.coerceAtLeast(1)

        // 山手線内のみの移動（特定運賃）
        if (isYamanoteOnly) {
            return when {
                km <= 3 -> 150
                km <= 6 -> 170
                km <= 10 -> 180
                km <= 15 -> 210
                km <= 20 -> 280
                else -> calculateDenchaTokuteiFare(km)
            }
        }

        return calculateDenchaTokuteiFare(km)
    }

    /**
     * 電車特定区間運賃表（東京近郊・バリアフリー料金10円加算後）
     */
    private fun calculateDenchaTokuteiFare(km: Int): Int {
        return when {
            km <= 3 -> 150
            km <= 6 -> 170
            km <= 10 -> 180
            km <= 15 -> 230
            km <= 20 -> 320
            km <= 25 -> 410
            km <= 30 -> 490
            km <= 35 -> 580
            km <= 40 -> 660
            km <= 45 -> 740
            km <= 50 -> 830
            km <= 60 -> 950
            km <= 70 -> 1110
            km <= 80 -> 1280
            km <= 90 -> 1450
            km <= 100 -> 1530
            km <= 120 -> 1720
            km <= 140 -> 2010
            km <= 160 -> 2350
            km <= 180 -> 2680
            km <= 200 -> 3080
            else -> 3080 + ((km - 200 + 19) / 20) * 350
        }
    }

    /**
     * 1区間の乗車レグにおける運賃を算出
     */
    fun calculateLegFare(
        stations: List<StationEntry>,
        routeId: String,
        operatorLabel: String
    ): Int? {
        if (!isJrRoute(routeId, operatorLabel)) return null
        val km = estimateOperatingKm(stations)
        val yamanote = isAllYamanote(stations)
        return calculateFare(km, yamanote)
    }

    /**
     * 連続するJR乗車区間（改札内乗換）の通算運賃を計算
     */
    fun calculateThroughFare(kmList: List<Int>, allYamanote: Boolean = false): Int {
        if (kmList.isEmpty()) return 0
        val totalKm = kmList.sum()
        return calculateFare(totalKm, allYamanote)
    }
}
