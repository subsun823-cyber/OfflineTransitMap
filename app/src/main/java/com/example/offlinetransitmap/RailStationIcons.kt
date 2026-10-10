package com.example.offlinetransitmap

import java.text.Normalizer
import java.util.Locale

// 路線の運行会社名で判定する。駅名や駅IDから会社を推測しない。
internal fun railStationIcon(operators: Set<String>): String {
    val names = operators.map { operator ->
        Normalizer.normalize(operator, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{Z}・.,:_-]"), "")
            .replace("株式会社", "")
    }.filter { it.isNotEmpty() }.toSet()
    val jrEastNames = setOf("jr東日本", "東日本旅客鉄道", "jreast", "eastjapanrailwaycompany", "odptoperatorjreast")
    val tokyoMetroNames = setOf("東京メトロ", "東京地下鉄", "tokyometro", "tokyometrocoltd", "tokyosubway", "odptoperatortokyometro")
    val toeiNames = setOf("東京都交通局", "都営地下鉄", "都営", "東京都", "toei", "toeisubway", "odptoperatortoei")
    val seibuNames = setOf("西武鉄道", "西武", "seibu", "seiburailway", "seiburailwaycoltd", "odptoperatorseibu")
    val tobuNames = setOf("東武鉄道", "東武", "tobu", "toburailway", "toburailwaycoltd", "odptoperatortobu")

    val hasJrEast = names.any { it in jrEastNames }
    val hasTokyoMetro = names.any { it in tokyoMetroNames }
    val hasToei = names.any { it in toeiNames }
    val hasSeibu = names.any { it in seibuNames }
    val hasTobu = names.any { it in tobuNames }
    val hasOtherPrivate = names.any { it !in jrEastNames && it !in tokyoMetroNames && it !in toeiNames && it !in seibuNames && it !in tobuNames }

    val categories = mutableListOf<String>()
    if (hasJrEast) categories.add("jr")
    if (hasTobu) categories.add("tobu")
    if (hasSeibu) categories.add("seibu")
    if (hasOtherPrivate) categories.add("private")
    if (hasTokyoMetro) categories.add("metro")
    if (hasToei) categories.add("toei")

    return when (categories.size) {
        0 -> "station-rail"
        1 -> when (categories[0]) {
            "jr" -> "station-jr-east"
            "metro" -> "station-tokyo-metro"
            "toei" -> "station-toei"
            "seibu" -> "station-seibu"
            "tobu" -> "station-tobu"
            else -> "station-rail"
        }
        2 -> when (categories.joinToString("-")) {
            "jr-tobu" -> "station-rail-jr-tobu"
            "tobu-seibu" -> "station-rail-tobu-seibu"
            "tobu-private" -> "station-rail-tobu-private"
            "tobu-metro" -> "station-rail-tobu-metro"
            "tobu-toei" -> "station-rail-tobu-toei"
            "jr-seibu" -> "station-rail-jr-seibu"
            "seibu-private" -> "station-rail-seibu-private"
            "seibu-metro" -> "station-rail-seibu-metro"
            "seibu-toei" -> "station-rail-seibu-toei"
            "jr-private" -> "station-rail-both"
            "jr-metro" -> "station-rail-jr-metro"
            "jr-toei" -> "station-rail-jr-toei"
            "private-metro" -> "station-rail-private-metro"
            "private-toei" -> "station-rail-private-toei"
            "metro-toei" -> "station-rail-metro-toei"
            else -> if (categories.contains("tobu")) "station-rail-jr-tobu" else if (categories.contains("seibu")) "station-rail-jr-seibu" else "station-rail-both"
        }
        3 -> when (categories.joinToString("-")) {
            "jr-tobu-seibu" -> "station-rail-jr-tobu-seibu"
            "jr-tobu-metro" -> "station-rail-jr-tobu-metro"
            "jr-tobu-toei" -> "station-rail-jr-tobu-toei"
            "tobu-metro-toei" -> "station-rail-tobu-metro-toei"
            "jr-tobu-private" -> "station-rail-jr-tobu"
            "tobu-private-metro" -> "station-rail-tobu-metro"
            "tobu-private-toei" -> "station-rail-tobu-toei"
            "jr-seibu-metro" -> "station-rail-jr-seibu-metro"
            "jr-seibu-toei" -> "station-rail-jr-seibu-toei"
            "seibu-metro-toei" -> "station-rail-seibu-metro-toei"
            "jr-seibu-private" -> "station-rail-jr-seibu"
            "jr-private-metro" -> "station-rail-jr-private-metro"
            "jr-private-toei" -> "station-rail-jr-private-toei"
            "jr-metro-toei" -> "station-rail-jr-metro-toei"
            "private-metro-toei" -> "station-rail-private-metro-toei"
            else -> if (categories.contains("tobu")) "station-rail-jr-tobu-metro" else if (categories.contains("seibu")) "station-rail-jr-seibu-metro" else "station-rail-jr-private-metro"
        }
        else -> when {
            categories.contains("tobu") && categories.contains("seibu") -> "station-rail-jr-seibu-tobu-metro"
            categories.contains("tobu") -> "station-rail-jr-tobu-metro-toei"
            categories.contains("seibu") -> "station-rail-jr-seibu-metro-toei"
            else -> "station-rail-jr-private-metro-toei"
        }
    }
}
