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
    val hasJrEast = names.any { it in jrEastNames }
    val hasTokyoMetro = names.any { it in tokyoMetroNames }
    val hasOther = names.any { it !in jrEastNames }
    return when {
        hasJrEast && hasOther -> "station-rail-both"
        hasJrEast -> "station-jr-east"
        hasTokyoMetro -> "station-tokyo-metro"
        else -> "station-rail"
    }
}
