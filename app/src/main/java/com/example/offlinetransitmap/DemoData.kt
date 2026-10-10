package com.example.offlinetransitmap

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class Station(val name: String, val lat: Double, val lon: Double)

data class Departure(
    val operatorLabel: String,
    val lineName: String,
    val lineColor: Long,
    val headsign: String,
    val detail: String,      // 「3番線 · 快速」など
    val time: LocalDateTime, // 出発する日時
    val isBus: Boolean = false, // バスなら「行き」、それ以外は「方面」と表示
    val tripNo: Long = 0L,   // DB上の便の番号(0 = サンプル)
    val seq: Int = 0,        // その便の中で、この駅が何番目の停留所か
    val trainType: String = "" // 電車の種別(快速・中央特快など。バスは空)
)

// 便の経由地1つ分(詳細表示用)
data class TripStop(
    val seq: Int,
    val name: String,
    val platform: String,
    val time: LocalDateTime
)

val demoStations = listOf(
    Station("新宿駅", 35.6896, 139.7006),
    Station("吉祥寺駅", 35.7030, 139.5797),
    Station("立川駅", 35.6980, 139.4137),
    Station("八王子駅", 35.6556, 139.3388),
    Station("高尾駅", 35.6419, 139.2825)
)

fun demoStationsGeoJson(): String {
    val features = demoStations.joinToString(",") { s ->
        """{"type":"Feature","properties":{"name":"${s.name}"},"geometry":{"type":"Point","coordinates":[${s.lon},${s.lat}]}}"""
    }
    return """{"type":"FeatureCollection","features":[$features]}"""
}

private data class SampleLine(
    val op: String,
    val line: String,
    val color: Long,
    val head: String,
    val detail: String
)

private val sampleLines = listOf(
    SampleLine("JR", "中央線", 0xFFF15A22, "立川", "3番線 · 快速"),
    SampleLine("京王", "高尾線", 0xFFDD0077, "高尾山口", "6番線 · 各停"),
    SampleLine("JR", "中央線", 0xFFF15A22, "東京", "1番線 · 特快"),
    SampleLine("京王", "京王線", 0xFFDD0077, "新宿", "2番線 · 特急"),
    SampleLine("バス", "八05系統", 0xFF2E8B57, "八王子駅", "2番のりば")
)

data class StationLine(
    val name: String,
    val color: Long,
    val operator: String
)

// 表示確認用のサンプル(実際の時刻表ではありません)
// 現在時刻から25時間先までの25件。1時間以上先と、日付をまたぐ便を含みます。
fun sampleDepartures(): List<Departure> {
    val base = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
    val offsets = listOf(
        3, 4, 9, 12, 15, 22, 31, 38, 47, 55,
        63, 78, 95, 112, 130, 155, 180, 210, 240, 300,
        420, 600, 900, 1200, 1500
    )
    return offsets.mapIndexed { i, minutes ->
        val s = sampleLines[i % sampleLines.size]
        Departure(
            operatorLabel = s.op,
            lineName = s.line,
            lineColor = s.color,
            headsign = s.head,
            detail = s.detail,
            time = base.plusMinutes(minutes.toLong()),
            isBus = s.op == "バス"
        )
    }
}

// 直前に出発した便のサンプル(古い順で並べる: 10分前 -> 6分前 -> 1分前)
fun samplePastDepartures(): List<Departure> {
    val base = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
    val offsets = listOf(10, 10, 6, 4, 2, 1) // 分前
    return offsets.mapIndexed { i, minutesAgo ->
        val s = sampleLines[i % sampleLines.size]
        Departure(
            operatorLabel = s.op,
            lineName = s.line,
            lineColor = s.color,
            headsign = s.head,
            detail = s.detail,
            time = base.minusMinutes(minutesAgo.toLong()),
            isBus = s.op == "バス"
        )
    }
}

fun sampleStationLines(): List<StationLine> {
    return sampleLines
        .filter { it.op != "バス" }
        .map { StationLine(name = it.line, color = it.color, operator = it.op) }
        .distinctBy { it.name }
}