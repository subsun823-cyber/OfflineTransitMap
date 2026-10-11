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

    Box(modifier = modifier.fillMaxSize()) {
        // 上部: ターンバイターン案内カード
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TopManeuverCard(guidance = guidance)

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
                // 左下: 車のナビ風速度計
                CarSpeedometer(
                    speedKmh = speedKmh,
                    hasGps = hasGps
                )

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

            // 最下部: 自動車ナビ風ダッシュボードバー
            BottomNavigationDashboard(
                guidance = guidance,
                mapBearing = mapBearing,
                onStop = onStop
            )
        }
    }
}