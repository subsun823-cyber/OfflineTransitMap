package com.example.offlinetransitmap

import java.text.Normalizer
import java.util.Locale

// 路線の運行会社名で判定する。駅名や駅IDから会社を推測しない。
internal fun railStationIcon(operators: Set<String>): String {
    val names = operators.map { operator ->
        Normalizer.normalize(operator, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{Z}・.,]"), "")
            .replace("株式会社", "")
    }.filter { it.isNotEmpty() }.toSet()
    val jrEastNames = setOf("jr東日本", "東日本旅客鉄道", "jreast", "eastjapanrailwaycompany")
    val tokyoMetroNames = setOf("東京メトロ", "東京地下鉄", "tokyometro", "tokyometrocoltd", "tokyosubway")
    val toeiNames = setOf("東京都交通局", "都営地下鉄", "都営", "東京都", "toei", "toeisubway")

    val hasJrEast = names.any { it in jrEastNames }
    val hasTokyoMetro = names.any { it in tokyoMetroNames }
    val hasToei = names.any { it in toeiNames }
    val hasPrivate = names.any { it !in jrEastNames && it !in tokyoMetroNames && it !in toeiNames }

    val categories = mutableListOf<String>()
    if (hasJrEast) categories.add("jr")
    if (hasPrivate) categories.add("private")
    if (hasTokyoMetro) categories.add("metro")
    if (hasToei) categories.add("toei")

    return when (categories.size) {
        0 -> "station-rail"
        1 -> when (categories[0]) {
            "jr" -> "station-jr-east"
            "metro" -> "station-tokyo-metro"
            "toei" -> "station-toei"
            else -> "station-rail"
        }
        2 -> when (categories.joinToString("-")) {
            "jr-private" -> "station-rail-both"
            "jr-metro" -> "station-rail-jr-metro"
            "jr-toei" -> "station-rail-jr-toei"
            "private-metro" -> "station-rail-private-metro"
            "private-toei" -> "station-rail-private-toei"
            "metro-toei" -> "station-rail-metro-toei"
            else -> "station-rail-both"
        }
        3 -> when (categories.joinToString("-")) {
            "jr-private-metro" -> "station-rail-jr-private-metro"
            "jr-private-toei" -> "station-rail-jr-private-toei"
            "jr-metro-toei" -> "station-rail-jr-metro-toei"
            "private-metro-toei" -> "station-rail-private-metro-toei"
            else -> "station-rail-jr-private-metro"
        }
        else -> "station-rail-jr-private-metro-toei"
    }
}
