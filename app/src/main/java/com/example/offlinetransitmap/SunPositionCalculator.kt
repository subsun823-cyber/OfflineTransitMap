package com.example.offlinetransitmap

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.*

/**
 * 太陽の位置（方位角・仰角）を計算するデータクラス
 */
data class SunPosition(
    /** 方位角（度）: 0° = 北, 90° = 東, 180° = 南, 270° = 西 */
    val azimuthDegrees: Double,
    /** 仰角（度）: 0° = 地平線, 90° = 天頂, 負の値 = 地平線下（夜間） */
    val altitudeDegrees: Double,
    /** 方位の日本語名称（例: "南南西", "東"） */
    val azimuthDirectionName: String,
    /** 撮影光線の状態解説（例: "斜光線（陰影良好）", "順光チャンス", "マジックアワー"） */
    val lightCondition: String,
    /** 順光・逆光に関する簡易アドバイス */
    val photoAdvice: String
)

/**
 * 太陽位置計算エンジン (NOAA Solar Calculations 近似式)
 * 踏切撮影や鉄道写真における「今どちらから光が当たっているか」「順光・逆光」の判定に特化
 */
object SunPositionCalculator {

    /**
     * 指定された座標と日時における太陽位置を計算
     */
    fun calculate(
        lat: Double,
        lon: Double,
        dateTime: LocalDateTime = LocalDateTime.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): SunPosition {
        val zonedDateTime = dateTime.atZone(zoneId)
        val epochSeconds = zonedDateTime.toEpochSecond()
        val daysSince2000 = (epochSeconds - 946684800.0) / 86400.0 // 2000-01-01 00:00:00 UTC 起算

        // 太陽の平均黄経 L (度)
        val l = (280.460 + 0.9856474 * daysSince2000) % 360.0

        // 太陽の平均近点角 g (ラジアン)
        val gRad = Math.toRadians((357.528 + 0.9856003 * daysSince2000) % 360.0)

        // 黄道経度 lambda (ラジアン)
        val lambdaRad = Math.toRadians(l + 1.915 * sin(gRad) + 0.020 * sin(2.0 * gRad))

        // 黄道傾斜角 epsilon (ラジアン)
        val epsilonRad = Math.toRadians(23.439 - 0.0000004 * daysSince2000)

        // 赤経 alpha (ラジアン) と 赤緯 delta (ラジアン)
        val alphaRad = atan2(cos(epsilonRad) * sin(lambdaRad), cos(lambdaRad))
        val deltaRad = asin(sin(epsilonRad) * sin(lambdaRad))

        // グリニッジ平均恒星時 GMST (度)
        val gmstDeg = (280.46061837 + 360.98564736629 * daysSince2000) % 360.0
        // 地方恒星時 LMST (ラジアン)
        val lmstRad = Math.toRadians((gmstDeg + lon) % 360.0)

        // 時角 H (ラジアン)
        val hourAngleRad = lmstRad - alphaRad

        // 観測地の緯度 (ラジアン)
        val latRad = Math.toRadians(lat)

        // 太陽高度 (仰角) altitude
        val sinAlt = sin(latRad) * sin(deltaRad) + cos(latRad) * cos(deltaRad) * cos(hourAngleRad)
        val altRad = asin(sinAlt.coerceIn(-1.0, 1.0))
        val altitudeDeg = Math.toDegrees(altRad)

        // 太陽方位角 azimuth (北 = 0°, 時計回り)
        val cosAz = (sin(deltaRad) - sin(latRad) * sin(altRad)) / (cos(latRad) * cos(altRad))
        val azRad = acos(cosAz.coerceIn(-1.0, 1.0))
        var azimuthDeg = Math.toDegrees(azRad)
        if (sin(hourAngleRad) > 0) {
            azimuthDeg = 360.0 - azimuthDeg
        }

        val directionName = degreeTo16Direction(azimuthDeg)
        val (condition, advice) = evaluateLighting(altitudeDeg, azimuthDeg)

        return SunPosition(
            azimuthDegrees = (azimuthDeg + 360.0) % 360.0,
            altitudeDegrees = altitudeDeg,
            azimuthDirectionName = directionName,
            lightCondition = condition,
            photoAdvice = advice
        )
    }

    private fun degreeTo16Direction(deg: Double): String {
        val normalized = (deg % 360.0 + 360.0) % 360.0
        val dirs = arrayOf(
            "北", "北北東", "北東", "東北東",
            "東", "東南東", "南東", "南南東",
            "南", "南南西", "南西", "西南西",
            "西", "西北西", "北西", "北北西"
        )
        val idx = ((normalized + 11.25) / 22.5).toInt() % 16
        return dirs[idx]
    }

    private fun evaluateLighting(alt: Double, az: Double): Pair<String, String> {
        return when {
            alt < -6.0 -> Pair(
                "夜間（バルブ・夜景撮影）",
                "踏切の警報灯やヘッドライトの光跡撮影に適した時間帯です。"
            )
            alt < 0.0 -> Pair(
                "ブルーアワー（日没直後/日の出前）",
                "空が深い青に染まる時間帯です。情緒的なトワイライト撮影が楽しめます。"
            )
            alt < 12.0 -> Pair(
                "ゴールデンアワー（朝夕の斜光）",
                "太陽光が低くドラマチックな陰影が出ます。オレンジ色に輝く車両側面が狙い目です。"
            )
            alt < 35.0 -> Pair(
                "好光線（立体的な斜光線）",
                "車両の前面と側面の両方に光が回りやすく、編成写真が最も綺麗に撮れる推奨時間帯です。"
            )
            alt < 60.0 -> Pair(
                "トップライト（昼間光線）",
                "高い位置からの日差しです。屋根や床下の影に注意しつつ、シャッター速度を稼げます。"
            )
            else -> Pair(
                "真夏の南中（強い日差し）",
                "真上からの強い光です。PLフィルター等の活用が効果的です。"
            )
        }
    }
}
