package com.example.offlinetransitmap

import android.location.Location
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import java.time.LocalDateTime

// 現在地から目標までの、地図に引く点線(GeoJSON)
fun navLineGeoJson(loc: Location?, g: Guidance?): String? {
    val lat = g?.targetLat ?: return null
    val lon = g.targetLon ?: return null
    if (loc == null) return null
    return "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\"," +
            "\"coordinates\":[[${loc.longitude},${loc.latitude}],[$lon,$lat]]}}"
}

private fun distLabel(m: Int): String =
    if (m >= 1000) String.format("%.1f km", m / 1000.0) else "$m m"

// Google Maps / カーナビ風のターンバイターン案内アイコン
@Composable
fun ManeuverIcon(maneuver: ManeuverType, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(56.dp)) {
        val w = size.width
        val h = size.height
        val strokeW = 6.5.dp.toPx()
        val arrowSize = 13.dp.toPx()

        when (maneuver) {
            ManeuverType.STRAIGHT -> {
                drawLine(
                    color = Color.White,
                    start = Offset(w * 0.5f, h * 0.88f),
                    end = Offset(w * 0.5f, h * 0.28f),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
                val head = Path().apply {
                    moveTo(w * 0.5f, h * 0.12f)
                    lineTo(w * 0.5f - arrowSize, h * 0.12f + arrowSize * 1.3f)
                    lineTo(w * 0.5f + arrowSize, h * 0.12f + arrowSize * 1.3f)
                    close()
                }
                drawPath(head, Color.White)
            }
            ManeuverType.TURN_RIGHT -> {
                val path = Path().apply {
                    moveTo(w * 0.32f, h * 0.85f)
                    lineTo(w * 0.32f, h * 0.48f)
                    quadraticTo(w * 0.32f, h * 0.35f, w * 0.46f, h * 0.35f)
                    lineTo(w * 0.70f, h * 0.35f)
                }
                drawPath(path, Color.White, style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))
                val head = Path().apply {
                    moveTo(w * 0.88f, h * 0.35f)
                    lineTo(w * 0.88f - arrowSize * 1.3f, h * 0.35f - arrowSize)
                    lineTo(w * 0.88f - arrowSize * 1.3f, h * 0.35f + arrowSize)
                    close()
                }
                drawPath(head, Color.White)
            }
            ManeuverType.TURN_LEFT -> {
                val path = Path().apply {
                    moveTo(w * 0.68f, h * 0.85f)
                    lineTo(w * 0.68f, h * 0.48f)
                    quadraticTo(w * 0.68f, h * 0.35f, w * 0.54f, h * 0.35f)
                    lineTo(w * 0.30f, h * 0.35f)
                }
                drawPath(path, Color.White, style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))
                val head = Path().apply {
                    moveTo(w * 0.12f, h * 0.35f)
                    lineTo(w * 0.12f + arrowSize * 1.3f, h * 0.35f - arrowSize)
                    lineTo(w * 0.12f + arrowSize * 1.3f, h * 0.35f + arrowSize)
                    close()
                }
                drawPath(head, Color.White)
            }
            ManeuverType.SLIGHT_RIGHT -> {
                val path = Path().apply {
                    moveTo(w * 0.36f, h * 0.85f)
                    lineTo(w * 0.36f, h * 0.58f)
                    quadraticTo(w * 0.38f, h * 0.46f, w * 0.50f, h * 0.40f)
                    lineTo(w * 0.68f, h * 0.30f)
                }
                drawPath(path, Color.White, style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))
                rotate(degrees = 50f, pivot = Offset(w * 0.78f, h * 0.22f)) {
                    val head = Path().apply {
                        val px = w * 0.78f
                        val py = h * 0.22f
                        moveTo(px, py - arrowSize * 0.8f)
                        lineTo(px - arrowSize, py + arrowSize)
                        lineTo(px + arrowSize, py + arrowSize)
                        close()
                    }
                    drawPath(head, Color.White)
                }
            }
            ManeuverType.SLIGHT_LEFT -> {
                val path = Path().apply {
                    moveTo(w * 0.64f, h * 0.85f)
                    lineTo(w * 0.64f, h * 0.58f)
                    quadraticTo(w * 0.62f, h * 0.46f, w * 0.50f, h * 0.40f)
                    lineTo(w * 0.32f, h * 0.30f)
                }
                drawPath(path, Color.White, style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))
                rotate(degrees = -50f, pivot = Offset(w * 0.22f, h * 0.22f)) {
                    val head = Path().apply {
                        val px = w * 0.22f
                        val py = h * 0.22f
                        moveTo(px, py - arrowSize * 0.8f)
                        lineTo(px - arrowSize, py + arrowSize)
                        lineTo(px + arrowSize, py + arrowSize)
                        close()
                    }
                    drawPath(head, Color.White)
                }
            }
            ManeuverType.UTURN -> {
                val path = Path().apply {
                    moveTo(w * 0.68f, h * 0.85f)
                    lineTo(w * 0.68f, h * 0.45f)
                    quadraticTo(w * 0.68f, h * 0.22f, w * 0.50f, h * 0.22f)
                    quadraticTo(w * 0.32f, h * 0.22f, w * 0.32f, h * 0.45f)
                    lineTo(w * 0.32f, h * 0.60f)
                }
                drawPath(path, Color.White, style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))
                val head = Path().apply {
                    moveTo(w * 0.32f, h * 0.76f)
                    lineTo(w * 0.32f - arrowSize, h * 0.76f - arrowSize * 1.3f)
                    lineTo(w * 0.32f + arrowSize, h * 0.76f - arrowSize * 1.3f)
                    close()
                }
                drawPath(head, Color.White)
            }
            ManeuverType.ARRIVE -> {
                drawCircle(Color.White, radius = w * 0.32f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(width = strokeW))
                drawCircle(Color(0xFF00E5FF), radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.5f))
            }
        }
    }
}

// 車のナビを彷彿とさせるデジタル＋アナログ円形速度計
@Composable
fun CarSpeedometer(
    speedKmh: Float,
    hasGps: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedSpeed by animateFloatAsState(
        targetValue = speedKmh.coerceAtLeast(0f),
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "SpeedometerAnim"
    )

    // スピードに応じた最大スケール (通常時は45km/h、速度が上がると100km/hへ自動拡大)
    val maxScale = if (animatedSpeed > 40f) 100f else 45f
    val fraction = (animatedSpeed / maxScale).coerceIn(0f, 1f)

    Card(
        modifier = modifier.size(92.dp),
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = Color(0xF2080E1A)),
        border = BorderStroke(
            1.5.dp,
            Brush.sweepGradient(
                listOf(
                    Color(0x3300E5FF),
                    Color(0x5500E676),
                    Color(0x3300E5FF),
                    Color(0x2238BDF8)
                )
            )
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val center = Offset(w / 2f, h / 2f)
                val strokeWidth = 5.5.dp.toPx()
                val radius = (w / 2f) - strokeWidth / 2f - 5.dp.toPx()

                // メーター背景円弧 (150°から240°のスイープ = 390°/30°)
                drawArc(
                    color = Color(0x2238BDF8),
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // 目盛り線 (8分割)
                val tickCount = 8
                val tickInnerR = radius - 4.dp.toPx()
                val tickOuterR = radius + 2.dp.toPx()
                for (i in 0..tickCount) {
                    val deg = 150.0 + (i.toDouble() / tickCount.toDouble()) * 240.0
                    val rad = Math.toRadians(deg)
                    val p1 = Offset(
                        (center.x + tickInnerR * cos(rad)).toFloat(),
                        (center.y + tickInnerR * sin(rad)).toFloat()
                    )
                    val p2 = Offset(
                        (center.x + tickOuterR * cos(rad)).toFloat(),
                        (center.y + tickOuterR * sin(rad)).toFloat()
                    )
                    val isMajor = (i == 0 || i == tickCount / 2 || i == tickCount)
                    drawLine(
                        color = if (isMajor) Color(0x6600E5FF) else Color(0x3394A3B8),
                        start = p1,
                        end = p2,
                        strokeWidth = if (isMajor) 1.5.dp.toPx() else 1.dp.toPx()
                    )
                }

                // アクティブ速度円弧
                val activeSweep = fraction * 240f
                if (activeSweep > 0.5f) {
                    val arcBrush = Brush.sweepGradient(
                        0.0f to Color(0xFF00E5FF),
                        0.5f to Color(0xFF00E676),
                        0.85f to Color(0xFFFF9100),
                        1.0f to Color(0xFFFF1744),
                        center = center
                    )
                    drawArc(
                        brush = arcBrush,
                        startAngle = 150f,
                        sweepAngle = activeSweep,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )

                    // 先端のネオン発光ポインタ
                    val tipRad = Math.toRadians((150f + activeSweep).toDouble())
                    val tipX = (center.x + radius * cos(tipRad)).toFloat()
                    val tipY = (center.y + radius * sin(tipRad)).toFloat()
                    drawCircle(
                        color = Color(0x8800E5FF),
                        radius = 5.dp.toPx(),
                        center = Offset(tipX, tipY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 2.dp.toPx(),
                        center = Offset(tipX, tipY)
                    )
                }
            }

            // 中央のデジタル数値と単位
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // GPS状態
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 1.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(
                                color = if (hasGps) Color(0xFF00E676) else Color(0xFF64748B),
                                shape = CircleShape
                            )
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = if (hasGps) "GPS" else "--",
                        color = Color(0xFF80D8FF),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 速度数字
                val displaySpeed = animatedSpeed.roundToInt()
                Text(
                    text = "$displaySpeed",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 28.sp
                )

                // 単位
                Text(
                    text = "km/h",
                    color = Color(0xFF00E5FF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

// 車のナビを彷彿とさせる方位コンパスバッジ
@Composable
fun CompassBadge(bearing: Double, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.size(44.dp),
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = Color(0xF2080E1A)),
        border = BorderStroke(1.dp, Color(0x3338BDF8))
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(32.dp)) {
                val w = size.width
                val h = size.height
                val center = Offset(w / 2f, h / 2f)

                // 外周サークル
                drawCircle(color = Color(0x2238BDF8), radius = w / 2f, style = Stroke(width = 1.dp.toPx()))

                // 地図方位に応じて回転する羅針盤ニードル
                rotate(degrees = -bearing.toFloat(), pivot = center) {
                    // 北(赤)
                    val northPath = Path().apply {
                        moveTo(center.x, center.y - h * 0.42f)
                        lineTo(center.x + w * 0.16f, center.y)
                        lineTo(center.x - w * 0.16f, center.y)
                        close()
                    }
                    drawPath(northPath, Color(0xFFEF4444))

                    // 南(白/銀)
                    val southPath = Path().apply {
                        moveTo(center.x, center.y + h * 0.42f)
                        lineTo(center.x + w * 0.16f, center.y)
                        lineTo(center.x - w * 0.16f, center.y)
                        close()
                    }
                    drawPath(southPath, Color(0xFFCBD5E1))

                    // センターピン
                    drawCircle(Color.White, radius = 2.dp.toPx(), center = center)
                }
            }
        }
    }
}

// 上部ターンバイターン案内カード
@Composable
private fun TopManeuverCard(
    guidance: Guidance?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xF2080E1A)),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(
                1.2.dp,
                Brush.horizontalGradient(
                    listOf(
                        Color(0x4400E5FF),
                        Color(0x2238BDF8),
                        Color(0x4400E676)
                    )
                )
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isRerouting = NavigationState.isRerouting

                // 左: マニューバーアイコンプレート
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(
                            if (isRerouting) Color(0x33FF9100) else Color(0x1F00E5FF),
                            RoundedCornerShape(16.dp)
                        )
                        .border(
                            1.dp,
                            if (isRerouting) Color(0x66FF9100) else Color(0x3D00E5FF),
                            RoundedCornerShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRerouting) {
                        Text("🔄", fontSize = 32.sp)
                    } else {
                        when (guidance?.kind) {
                            GuidanceKind.WALK, GuidanceKind.BICYCLE -> {
                                ManeuverIcon(guidance.maneuver, modifier = Modifier.size(46.dp))
                            }
                            GuidanceKind.WAIT -> Text("🚏", fontSize = 32.sp)
                            GuidanceKind.RIDE -> Text("🚌", fontSize = 32.sp)
                            else -> Text("📍", fontSize = 32.sp)
                        }
                    }
                }

                Spacer(Modifier.width(14.dp))

                // 中央: 曲がり角の案内と方面
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isRerouting) "ルートを再探索中..." else (guidance?.title ?: "案内を準備しています"),
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 23.sp
                    )
                    if (isRerouting) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "新しい最適ルートを計算しています",
                            color = Color(0xFFFFB74D),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 16.sp
                        )
                    } else if (guidance != null && guidance.subtitle.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = guidance.subtitle,
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 16.sp
                        )
                    }
                }

                // 右: 曲がり角までの距離（大きな数字＋単位）
                val meters = guidance?.distanceMeters
                if (meters != null && !isRerouting) {
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        if (meters >= 1000) {
                            val kmStr = String.format("%.1f", meters / 1000.0)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = kmStr,
                                    color = Color.White,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = " km",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 3.dp)
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "$meters",
                                    color = Color.White,
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = " m",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 次の行動カプセル (存在する場合)
        if (guidance != null && guidance.next.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Surface(
                color = Color(0xE60F172A),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0x3338BDF8)),
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "➜",
                        color = Color(0xFF00E5FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = guidance.next,
                        color = Color(0xFFE2E8F0),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// 下部ナビゲーションダッシュボード
@Composable
private fun BottomNavigationDashboard(
    guidance: Guidance?,
    mapBearing: Double,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xF5080E1A),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        border = BorderStroke(1.dp, Color(0x2E38BDF8)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 最上部のグローアクセントライン
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0x0000E5FF),
                                Color(0xFF00E5FF),
                                Color(0xFF00E676),
                                Color(0x0000E676)
                            )
                        )
                    )
            )

            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左: 終了ボタン (洗練されたクリムゾンガラスサークル)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0x2AEF4444), CircleShape)
                        .border(1.2.dp, Color(0x66EF4444), CircleShape)
                        .clickable(onClick = onStop),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Color(0xFFFF6B6B), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.weight(1f))

                // 中央: 所要時間・残り距離・到着予定時刻
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (guidance != null) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            if (guidance.remainingMinutes >= 60) {
                                val h = guidance.remainingMinutes / 60
                                val m = guidance.remainingMinutes % 60
                                Text(
                                    text = "$h",
                                    color = Color(0xFF00E676),
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Black,
                                    lineHeight = 36.sp
                                )
                                Text(
                                    text = " 時間",
                                    color = Color(0xFF00E676),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 4.dp, end = 2.dp)
                                )
                                if (m > 0) {
                                    Text(
                                        text = "$m",
                                        color = Color(0xFF00E676),
                                        fontSize = 36.sp,
                                        fontWeight = FontWeight.Black,
                                        lineHeight = 36.sp
                                    )
                                    Text(
                                        text = " 分",
                                        color = Color(0xFF00E676),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    )
                                }
                            } else {
                                Text(
                                    text = "${guidance.remainingMinutes}",
                                    color = Color(0xFF00E676),
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Black,
                                    lineHeight = 36.sp
                                )
                                Text(
                                    text = " 分",
                                    color = Color(0xFF00E676),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            val roadM = guidance.remainingRoadMeters
                            if (roadM != null) {
                                Text(
                                    text = " · ${distLabel(roadM)}",
                                    color = Color.White,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "到着 ${hm(guidance.arrival)}",
                                color = Color(0xFF94A3B8),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            val modeIcon = if (guidance.kind == GuidanceKind.BICYCLE) " 🚲" else " 🚶"
                            Text(
                                text = modeIcon,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        Text("案内を準備中…", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(Modifier.weight(1f))

                // 右: デジタルコンパス (自動車ナビのインジケーター)
                CompassBadge(bearing = mapBearing)
            }
        }
    }
}

// 駅名から英語表記を生成
private fun stationNameToRomaji(name: String): String {
    val clean = name.removeSuffix("駅").trim()
    val dict = mapOf(
        "新宿" to "Shinjuku", "東京" to "Tokyo", "渋谷" to "Shibuya", "池袋" to "Ikebukuro",
        "上野" to "Ueno", "品川" to "Shinagawa", "秋葉原" to "Akihabara", "立川" to "Tachikawa",
        "八王子" to "Hachioji", "高尾" to "Takao", "吉祥寺" to "Kichijoji", "三鷹" to "Mitaka",
        "中野" to "Nakano", "荻窪" to "Ogikubo", "国分寺" to "Kokubunji", "武蔵小金井" to "Musashi-Koganei",
        "横浜" to "Yokohama", "大宮" to "Omiya", "川崎" to "Kawasaki", "町田" to "Machida",
        "高尾山口" to "Takaosanguchi", "調布" to "Chofu", "府中" to "Fuchu", "橋本" to "Hashimoto"
    )
    return dict[clean] ?: clean
}

// 電車の車内LCD案内表示（トレインビジョン）または駅ホーム発車標風のトップカード
@Composable
private fun TrainLcdTopCard(
    guidance: Guidance?,
    itinerary: Itinerary?,
    modifier: Modifier = Modifier
) {
    val legs = itinerary?.legs ?: emptyList()
    val now = LocalDateTime.now()
    val trainLeg = legs.firstOrNull { leg ->
        !leg.isWalk && leg.lineName != "自転車" &&
        (leg.arrTime == null || now.isBefore(leg.arrTime.plusMinutes(2)))
    } ?: legs.firstOrNull { !it.isWalk && it.lineName != "自転車" }
    val lineColor = if (trainLeg != null && trainLeg.lineColor != 0L) Color(trainLeg.lineColor) else Color(0xFFF15A22)
    val lineName = trainLeg?.lineName ?: "電車"
    val trainType = trainLeg?.trainType?.ifBlank { "各停" } ?: "普通"
    val headsign = trainLeg?.headsign ?: ""
    val rawPlatform = trainLeg?.platform
    val platform = if (!rawPlatform.isNullOrBlank() && rawPlatform != "0") {
        if (rawPlatform.endsWith("番線") || rawPlatform.endsWith("のりば")) rawPlatform else "${rawPlatform}番線"
    } else ""
    val carCount = trainLeg?.let { PlatformAndCarResolver.resolveCarCount(it.lineName, it.trainType, false) } ?: 10

    val isWaiting = guidance?.kind == GuidanceKind.WAIT
    val isRiding = guidance?.kind == GuidanceKind.RIDE

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xF50F141C)),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.5.dp, Color(0xFF334155)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 最上部: 路線カラーストライプ帯
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(lineColor)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "🚃 $lineName",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp
                    )
                    Surface(
                        color = Color.Black.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = trainType,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                    if (headsign.isNotBlank()) {
                        Text(
                            text = "$headsign 方面",
                            color = Color.White.copy(alpha = 0.9f),
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                        )
                    }
                }

                Surface(
                    color = Color.White.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "${carCount}両編成",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }

            // メインコンテンツ (トレインビジョン / 発車標)
            if (isWaiting) {
                // ホームLED発車標スタイル
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(color = Color(0xFF1E293B), shape = RoundedCornerShape(4.dp)) {
                                Text(
                                    text = "発車標",
                                    color = Color(0xFFFFB300),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = "まもなく電車がまいります",
                                color = Color(0xFFFFD54F),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (platform.isNotBlank()) {
                            Text(
                                text = platform,
                                color = Color(0xFF00E5FF),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0A0F16), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = guidance?.title ?: "乗車案内",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = guidance?.subtitle ?: "",
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else if (isRiding) {
                // ドア上車内LCDトレインビジョン
                val destStation = guidance?.title?.removeSuffix("で降車")?.removeSuffix("へ") ?: (trainLeg?.toName ?: "")
                val romaji = stationNameToRomaji(destStation)

                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "次は",
                                color = Color(0xFF38BDF8),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = destStation,
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Next $romaji",
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // 到着予定
                        guidance?.let { g ->
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${hm(g.arrival)} 着",
                                    color = Color(0xFF00E676),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "あと約${formatDurationHoursMinutes(g.remainingMinutes)}",
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // 停車駅シークエンスライン
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0A0F16), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("●", color = Color(0xFF64748B), fontSize = 10.sp)
                            Text(trainLeg?.fromName ?: "乗車駅", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            Text("───", color = Color(0xFF334155), fontSize = 11.sp)
                            Text("🚃 走行中", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("───", color = Color(0xFF334155), fontSize = 11.sp)
                            Text("◯", color = Color(0xFF00E676), fontSize = 12.sp)
                            Text(destStation, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        if (platform.isNotBlank()) {
                            Surface(
                                color = Color(0x3300E5FF),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "$platform 着",
                                    color = Color(0xFF80D8FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                // 駅への徒歩移動など
                TopManeuverCard(guidance = guidance)
            }
        }
    }
}

// 電車の運転台風・デジタルATC速度計＆マスコン計器
@Composable
private fun TrainCabSpeedometer(
    speedKmh: Float,
    hasGps: Boolean,
    guidance: Guidance?,
    itinerary: Itinerary?,
    modifier: Modifier = Modifier
) {
    val legs = itinerary?.legs ?: emptyList()
    val now = LocalDateTime.now()
    val trainLeg = legs.firstOrNull { leg ->
        !leg.isWalk && leg.lineName != "自転車" &&
        (leg.arrTime == null || now.isBefore(leg.arrTime.plusMinutes(2)))
    } ?: legs.firstOrNull { !it.isWalk && it.lineName != "自転車" }
    val trainType = trainLeg?.trainType?.ifBlank { "快速" } ?: "快速"

    val animatedSpeed by animateFloatAsState(
        targetValue = speedKmh,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "trainSpeedAnimation"
    )

    // ノッチ・運転ステータス判定 (疑似力行・定速・惰行・ブレーキ)
    val notchText: String
    val notchColor: Color
    when {
        animatedSpeed < 3f -> {
            notchText = "停車"
            notchColor = Color(0xFF94A3B8)
        }
        animatedSpeed in 3f..35f -> {
            notchText = "力行 P2"
            notchColor = Color(0xFF00E676)
        }
        animatedSpeed in 35f..75f -> {
            notchText = "力行 P4"
            notchColor = Color(0xFF00E676)
        }
        animatedSpeed in 75f..100f -> {
            notchText = "定速"
            notchColor = Color(0xFF00E5FF)
        }
        else -> {
            notchText = "高速巡航"
            notchColor = Color(0xFFFF9100)
        }
    }

    Card(
        modifier = modifier.size(width = 126.dp, height = 118.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF50F141C)),
        border = BorderStroke(1.2.dp, Color(0xFF334155))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 上部: ATC / 信号現示バー
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(if (hasGps) Color(0xFF00E676) else Color.Gray, CircleShape)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(text = "ATC 100", color = Color(0xFF38BDF8), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }

                Surface(
                    color = Color(0xFFF15A22).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(0.8.dp, Color(0xFFF15A22))
                ) {
                    Text(
                        text = trainType,
                        color = Color(0xFFFFAB91),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp)
                    )
                }
            }

            // 中央: 速度計 (デジタル数字)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val displaySpeed = animatedSpeed.roundToInt()
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$displaySpeed",
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 32.sp
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "km/h",
                        color = Color(0xFF00E5FF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }

                // スピードバーインジケーター (0〜120km/h)
                val fraction = (animatedSpeed / 120f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(4.dp)
                        .background(Color(0xFF1E293B), RoundedCornerShape(2.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(4.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF00E5FF), Color(0xFF00E676), Color(0xFFFF9100))
                                ),
                                RoundedCornerShape(2.dp)
                            )
                    )
                }
            }

            // 下部: ノッチ / マスコンステータス
            Surface(
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "⚡ $notchText",
                        color = notchColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// 電車の運行情報・スタフ風ダッシュボードバー
@Composable
private fun TrainBottomDashboard(
    guidance: Guidance?,
    itinerary: Itinerary?,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val legs = itinerary?.legs ?: emptyList()
    val finalDest = legs.lastOrNull { !it.isWalk }?.toName ?: legs.lastOrNull()?.toName ?: "目的地"

    Surface(
        color = Color(0xF50F141C),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 最上部: 鉄道信号風アクセントバー
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF0288D1),
                                Color(0xFF00E676),
                                Color(0xFF0288D1)
                            )
                        )
                    )
            )

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左: ナビ終了ボタン
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(Color(0x2AEF4444), CircleShape)
                        .border(1.2.dp, Color(0x66EF4444), CircleShape)
                        .clickable(onClick = onStop),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Color(0xFFFF6B6B), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.weight(1f))

                // 中央: ダイヤ運行ステータス & 到着予定
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (guidance != null) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            if (guidance.remainingMinutes >= 60) {
                                val h = guidance.remainingMinutes / 60
                                val m = guidance.remainingMinutes % 60
                                Text(
                                    text = "$h",
                                    color = Color(0xFF00E676),
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    lineHeight = 32.sp
                                )
                                Text(
                                    text = " 時間",
                                    color = Color(0xFF00E676),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 3.dp, end = 2.dp)
                                )
                                if (m > 0) {
                                    Text(
                                        text = "$m",
                                        color = Color(0xFF00E676),
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Black,
                                        lineHeight = 32.sp
                                    )
                                    Text(
                                        text = " 分",
                                        color = Color(0xFF00E676),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 3.dp)
                                    )
                                }
                            } else {
                                Text(
                                    text = "${guidance.remainingMinutes}",
                                    color = Color(0xFF00E676),
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    lineHeight = 32.sp
                                )
                                Text(
                                    text = " 分",
                                    color = Color(0xFF00E676),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 3.dp)
                                )
                            }
                            Text(
                                text = " · 終着 ${finalDest}",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 3.dp, start = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "終着 ${hm(guidance.arrival)} 着予定",
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "🟢 定刻運行中",
                                color = Color(0xFF81C784),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text("運行情報取得中…", color = Color.White, fontSize = 15.sp)
                    }
                }

                Spacer(Modifier.weight(1f))

                // 右: 電車ピクトグラムバッジ
                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🚃", fontSize = 16.sp)
                        Text("ダイヤ", color = Color(0xFF80D8FF), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ナビ中に、地図の上に重ねる表示(上の案内バナー、下の速度計とダッシュボード)
@Composable
fun NavigationOverlay(
    guidance: Guidance?,
    location: Location?,
    mapBearing: Double,
    followLost: Boolean,
    onRecenter: () -> Unit,
    onStop: () -> Unit,
    nearbyCrossing: LevelCrossingItem? = null,
    modifier: Modifier = Modifier
) {
    val speedKmh = if (location != null && location.hasSpeed()) {
        (location.speed * 3.6f).coerceAtLeast(0f)
    } else {
        0f
    }
    val hasGps = location != null

    // 電車・バス乗車中または駅で待機中かどうか判定（トレインビジョンや鉄道計器の表示対象）
    val isTrainLcdMode = guidance?.kind == GuidanceKind.RIDE || guidance?.kind == GuidanceKind.WAIT

    Box(modifier = modifier.fillMaxSize()) {
        // 上部: 案内カード (電車乗車・待機時はトレインビジョン/発車標、徒歩・自転車移動時はターンバイターン案内)
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isTrainLcdMode) {
                TrainLcdTopCard(
                    guidance = guidance,
                    itinerary = NavigationState.itinerary
                )
            } else {
                TopManeuverCard(guidance = guidance)
            }

            // 趣味モード: 踏切接近アラートバナー
            if (nearbyCrossing != null) {
                Surface(
                    color = Color(0xF21A1608),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.2.dp, Color(0xFFFFB300)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("⚠️", fontSize = 22.sp)
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "まもなく踏切: ${nearbyCrossing.name}",
                                    color = Color(0xFFFFD54F),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                nearbyCrossing.distanceMeters?.let { d ->
                                    Text(
                                        text = "（あと${d}m）",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            nearbyCrossing.sunPosition?.let { sun ->
                                Text(
                                    text = "☀️ 光線: ${sun.azimuthDirectionName} (${"%.0f".format(sun.azimuthDegrees)}°) · ${sun.lightCondition}",
                                    color = Color(0xFFE0E0E0),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // 下部エリア (速度計 + 再センタリング + ダッシュボード)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            // フローティング計器行 (速度計 + 現在地に戻るボタン)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 左下: 電車乗車・待機中なら鉄道運転台計器、徒歩・自転車移動中はナビ風速度計
                if (isTrainLcdMode) {
                    TrainCabSpeedometer(
                        speedKmh = speedKmh,
                        hasGps = hasGps,
                        guidance = guidance,
                        itinerary = NavigationState.itinerary
                    )
                } else {
                    CarSpeedometer(
                        speedKmh = speedKmh,
                        hasGps = hasGps
                    )
                }

                // 右下: 追従が外れた場合の「現在地に戻る」ボタン
                if (followLost) {
                    Surface(
                        color = Color(0xF2080E1A),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.2.dp, Color(0xFF00E5FF)),
                        modifier = Modifier.clickable(onClick = onRecenter)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "➤",
                                color = Color(0xFF00E5FF),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "現在地に戻る",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            // 最下部: 電車乗車・待機中なら鉄道ダッシュボード、徒歩・自転車移動中はナビゲーションダッシュボード
            if (isTrainLcdMode) {
                TrainBottomDashboard(
                    guidance = guidance,
                    itinerary = NavigationState.itinerary,
                    onStop = onStop
                )
            } else {
                BottomNavigationDashboard(
                    guidance = guidance,
                    mapBearing = mapBearing,
                    onStop = onStop
                )
            }
        }
    }
}