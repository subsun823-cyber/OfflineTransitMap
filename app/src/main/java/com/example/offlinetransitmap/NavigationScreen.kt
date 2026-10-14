package com.example.offlinetransitmap

import android.location.Location
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import java.time.Duration
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

// バス停留所名から英語表記を生成
private fun busStopRomaji(name: String): String {
    val clean = name.replace("（バス）", "").replace("停留所", "").trim()
    val dict = mapOf(
        "新宿" to "Shinjuku", "東京" to "Tokyo", "渋谷" to "Shibuya", "池袋" to "Ikebukuro",
        "立川" to "Tachikawa", "八王子" to "Hachioji", "高尾" to "Takao", "高尾山口" to "Takaosanguchi",
        "福生" to "Fussa", "羽村" to "Hamura", "小作" to "Ozaku", "河辺" to "Kabe", "青梅" to "Ome",
        "拝島" to "Haijima", "昭島" to "Akishima", "秋川" to "Akigawa", "武蔵五日市" to "Itsukaichi",
        "陣馬高原下" to "Jimba-Kogen", "恩方" to "Ongata", "戸吹" to "Tobuki",
        "創価大学" to "Soka Univ.", "工学院大学" to "Kogakuin Univ.", "中央大学" to "Chuo Univ.",
        "楢原町" to "Narahara", "追分" to "Oiwake", "四谷" to "Yotsuya"
    )
    for ((k, v) in dict) {
        if (clean.startsWith(k)) {
            val rest = clean.removePrefix(k).trim()
            val suffix = when {
                rest.contains("西口") -> " West"
                rest.contains("東口") -> " East"
                rest.contains("南口") -> " South"
                rest.contains("北口") -> " North"
                rest.contains("駅西") -> " Sta. West"
                rest.contains("駅東") -> " Sta. East"
                rest.contains("駅") -> " Sta."
                rest.contains("入口") -> " Iriguchi"
                rest.contains("前") -> " Mae"
                rest.contains("通り") -> " Dori"
                rest.isNotBlank() -> " $rest"
                else -> ""
            }
            return "$v$suffix"
        }
    }
    return stationNameToRomaji(clean)
}

// 西東京バスなどの車内LCDモニターに表示される [ 系 統 ▶ ] [ 経 由 ▶ ] [ 行 先 ▶ ] バッジ
// （アイボリーの長方形本体＋黒文字、右端に西東京バスレッドの三角形シェブロン矢印）
@Composable
private fun NishiTokyoBusArrowBadge(
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.height(28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(66.dp)
                .fillMaxHeight()
                .background(
                    color = Color(0xFFFAF6EE),
                    shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color(0xFF181B20),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.5.sp,
                letterSpacing = 2.sp
            )
        }
        Canvas(
            modifier = Modifier
                .width(11.dp)
                .fillMaxHeight()
        ) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path, color = Color(0xFFC0262D))
        }
    }
}

// 停留所データクラス
private data class BusMonitorStop(
    val name: String,
    val isNext: Boolean,
    val isTerminus: Boolean,
    val isAlight: Boolean,
    val indexInTrip: Int
)

// 西東京バスなどの車内LCDモニター風案内表示トップカード
// （画面A: 手前から奥へパースペクティブに広がるルートリボン ＆ 画面B: 系統・経由・行先モニターを一定時間ごとにクロスフェード切替）
@Composable
private fun BusMonitorTopCard(
    guidance: Guidance?,
    itinerary: Itinerary?,
    location: Location?,
    modifier: Modifier = Modifier
) {
    val legs = itinerary?.legs ?: emptyList()
    val now = LocalDateTime.now()
    val busLeg = legs.firstOrNull { leg ->
        !leg.isWalk && leg.lineName != "自転車" &&
        (leg.arrTime == null || now.isBefore(leg.arrTime.plusMinutes(2)))
    } ?: legs.firstOrNull { !it.isWalk && it.lineName != "自転車" }

    // バス事業者名 (西東京バス等の表示)
    val rawOp = busLeg?.operatorLabel ?: ""
    val operatorName = when {
        rawOp.contains("西東京") || rawOp.isBlank() -> "西東京バス"
        rawOp.contains("京王") -> "京王バス"
        rawOp.contains("都営") -> "都営バス"
        rawOp.contains("小田急") -> "小田急バス"
        rawOp.contains("東急") -> "東急バス"
        rawOp.contains("関東") -> "関東バス"
        rawOp.contains("神奈中") || rawOp.contains("神奈川中央") -> "神奈中バス"
        else -> rawOp
    }

    val rawLineName = busLeg?.lineName?.ifBlank { "路線バス" } ?: "路線バス"
    val rawHeadsign = busLeg?.headsign?.trim() ?: ""
    val alightStation = busLeg?.toName ?: ""

    // 停留所リスト (intermediateStops を活用)
    val rawStops = if (busLeg != null && busLeg.intermediateStops.isNotEmpty()) {
        busLeg.intermediateStops
    } else if (busLeg != null) {
        listOf(busLeg.fromName, busLeg.toName)
    } else {
        listOf("乗車停留所", "降車停留所")
    }
    // 重複を隣接除去
    val allStops = remember(rawStops) {
        val list = ArrayList<String>()
        for (s in rawStops) {
            if (list.isEmpty() || list.last() != s) list.add(s)
        }
        list
    }

    // 1. 系統番号の抽出 (例: 福30, 陣01, 八01, 01)
    val routeNumber = remember(busLeg, rawLineName, rawHeadsign) {
        if (busLeg == null) return@remember "福30"
        val combined = "${busLeg.lineName} ${busLeg.headsign} ${busLeg.trainType}"
        val pattern = Regex("""([一-龥ぁ-んァ-ヶa-zA-Z]{1,3}\s*\d{1,3}[-a-zA-Z\d]*)""")
        val match = pattern.find(combined)
        if (match != null && match.value.isNotBlank()) {
            return@remember match.value.replace(" ", "")
        }
        val numMatch = Regex("""(\d{1,3}[-a-zA-Z\d]*)""").find(combined)
        if (numMatch != null && numMatch.value.isNotBlank()) {
            return@remember numMatch.value
        }
        val clean = busLeg.lineName
            .replace("西東京バス", "")
            .replace("路線バス", "")
            .replace("バス", "")
            .replace("系統", "")
            .trim()
        if (clean.isNotBlank()) clean.take(6) else "福30"
    }

    // 2. 経由地の抽出 (例: 羽村駅西)
    val viaName = remember(busLeg, rawHeadsign, allStops) {
        if (busLeg == null) return@remember "羽村駅西"
        val viaMatch = Regex("""([^\s（(]+)経由""").find(rawHeadsign)
        if (viaMatch != null) {
            val v = viaMatch.groupValues[1]
                .replace("（バス）", "")
                .replace("停留所", "")
                .trim()
            if (v.isNotBlank()) return@remember v
        }
        val candidates = allStops.filter { it != busLeg.fromName && it != busLeg.toName }
        if (candidates.isNotEmpty()) {
            val prio = candidates.firstOrNull {
                it.contains("駅") || it.contains("口") || it.contains("団地") ||
                it.contains("前") || it.contains("通り") || it.contains("高校")
            } ?: candidates[candidates.size / 2]
            val v = prio.replace("（バス）", "").replace("停留所", "").trim()
            if (v.isNotBlank()) return@remember v
        }
        if (busLeg.fromName.contains("羽村") || busLeg.toName.contains("福生")) {
            "羽村駅西"
        } else if (candidates.isNotEmpty()) {
            candidates.first().replace("（バス）", "").trim()
        } else {
            "直行"
        }
    }

    // 3. 行先の抽出 (例: 福生駅)
    val destinationName = remember(busLeg, rawHeadsign) {
        val raw = if (rawHeadsign.isNotBlank()) rawHeadsign else (busLeg?.toName ?: "福生駅")
        val cleaned = raw
            .replace(Regex("""[（(].*?[）)]"""), "")
            .replace(Regex("""経由.*"""), "")
            .replace("行", "")
            .replace("方面", "")
            .replace("（バス）", "")
            .replace("停留所", "")
            .trim()
        cleaned.ifBlank { busLeg?.toName?.replace("（バス）", "")?.trim() ?: "福生駅" }
    }

    // 現在地と各停留所座標から、次の停留所のインデックス(nextStopIdx)を判定
    val stopCoords = busLeg?.stopCoords ?: emptyList()
    val isRiding = guidance?.kind == GuidanceKind.RIDE

    val nextStopIdx = remember(location, busLeg, guidance) {
        if (!isRiding) {
            0
        } else if (location != null && stopCoords.size == allStops.size && allStops.size > 1) {
            var bestIdx = 1
            var bestDist = Double.MAX_VALUE
            for (i in 1 until stopCoords.size) {
                val d = calcDistanceMeters(location.latitude, location.longitude, stopCoords[i].first, stopCoords[i].second)
                if (d < bestDist) {
                    bestDist = d.toDouble()
                    bestIdx = i
                }
            }
            bestIdx.coerceIn(0, allStops.lastIndex)
        } else if (busLeg?.depTime != null && busLeg.arrTime != null && allStops.size > 1) {
            val totalSec = Duration.between(busLeg.depTime, busLeg.arrTime).seconds.coerceAtLeast(1)
            val elapsedSec = Duration.between(busLeg.depTime, now).seconds.coerceAtLeast(0)
            val frac = (elapsedSec.toFloat() / totalSec).coerceIn(0f, 1f)
            val estimated = (frac * (allStops.size - 1)).roundToInt()
            (estimated + 1).coerceIn(1, allStops.lastIndex)
        } else {
            if (allStops.size > 1) 1 else 0
        }
    }

    // 表示する停留所リスト (路線図用)
    val terminusName = if (destinationName.isNotBlank()) destinationName else allStops.last()
    val displayedStops = remember(allStops, nextStopIdx, terminusName, alightStation) {
        val list = ArrayList<BusMonitorStop>()
        val nextName = allStops.getOrElse(nextStopIdx) { allStops.last() }
        list.add(
            BusMonitorStop(
                name = nextName,
                isNext = true,
                isTerminus = nextName == terminusName,
                isAlight = nextName == alightStation,
                indexInTrip = nextStopIdx
            )
        )

        var curr = nextStopIdx + 1
        while (curr < allStops.size && list.size < 4) {
            val sName = allStops[curr]
            if (sName != terminusName) {
                list.add(
                    BusMonitorStop(
                        name = sName,
                        isNext = false,
                        isTerminus = false,
                        isAlight = sName == alightStation,
                        indexInTrip = curr
                    )
                )
            } else {
                break
            }
            curr++
        }

        if (list.none { it.name == terminusName }) {
            list.add(
                BusMonitorStop(
                    name = terminusName,
                    isNext = false,
                    isTerminus = true,
                    isAlight = terminusName == alightStation,
                    indexInTrip = allStops.lastIndex
                )
            )
        }
        list
    }

    val nextStopName = displayedStops.firstOrNull { it.isNext }?.name ?: alightStation
    val isNearAlight = (guidance?.distanceMeters != null && guidance.distanceMeters <= 150) ||
            (guidance?.remainingMinutes != null && guidance.remainingMinutes <= 1)

    // 停留所間の平均所要分数 (ダイヤまたは標準2分)
    val minutesPerStop = remember(busLeg, allStops) {
        if (busLeg?.depTime != null && busLeg.arrTime != null && allStops.size > 1) {
            val totalSec = Duration.between(busLeg.depTime, busLeg.arrTime).seconds
            val m = (totalSec / 60.0f / (allStops.size - 1)).roundToInt().coerceIn(1, 4)
            m
        } else {
            2
        }
    }

    // 停留所行ごとの垂直中心Y座標 (引出線用)
    val stopYPositions = remember { mutableStateMapOf<Int, Float>() }

    // 画面切り替え状態 (0: 路線図パースペクティブ画面, 1: 系統・経由・行先モニター画面)
    var monitorPage by remember { mutableStateOf(0) }

    // 6秒ごとに画面を自動切り替え
    LaunchedEffect(Unit) {
        while (true) {
            delay(6000L)
            monitorPage = (monitorPage + 1) % 2
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16191D)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(2.dp, Color(0xFF26303E)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                // タップで即時切り替え
                monitorPage = (monitorPage + 1) % 2
            }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // メインモニター領域 (Crossfadeで路線図画面と系統案内画面をスムーズに切り替え)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                Crossfade(
                    targetState = monitorPage,
                    animationSpec = tween(durationMillis = 600),
                    label = "BusMonitorPageCrossfade"
                ) { page ->
                    if (page == 0) {
                        // ── 画面A: 路線図パースペクティブ画面 (西東京バスイメージカラー ＆ 黒背景LCD) ──
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF14171C))
                        ) {
                            // 最上部: バス車内モニターヘッダー
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFF16191D), Color(0xFF222A35))
                                        )
                                    )
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f, fill = false)
                                ) {
                                    Surface(
                                        color = Color(0xFFC0262D),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = operatorName,
                                            color = Color.White,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Surface(
                                        color = Color(0xFF2E3846),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = routeNumber,
                                            color = Color(0xFFFAF6EE),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = "$destinationName 行",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (isNearAlight) {
                                        Surface(
                                            color = Color(0xFFC0262D),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "まもなく到着",
                                                color = Color.White,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 10.5.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    } else if (guidance != null) {
                                        Text(
                                            text = "${hm(guidance.arrival)} 着",
                                            color = Color(0xFF4ADE80),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = "(あと${formatDurationHoursMinutes(guidance.remainingMinutes)})",
                                            color = Color(0xFFCBD5E1),
                                            fontSize = 10.5.sp
                                        )
                                    }
                                }
                            }

                            // ヘッダー下部の薄い区切り線
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Color(0xFF26303E))
                            )

                            // 路線図曲線 ＆ 停留所リスト (黒背景 ＆ 西東京バスカラー)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val w = size.width
                                    val h = size.height

                                    val p0 = Offset(x = 22.dp.toPx(), y = h - 10.dp.toPx())
                                    val c1 = Offset(x = 18.dp.toPx(), y = h * 0.50f)
                                    val c2 = Offset(x = 38.dp.toPx(), y = h * 0.22f)
                                    val p1 = Offset(x = 72.dp.toPx(), y = 14.dp.toPx())

                                    val startRibbonW = 28.dp.toPx()
                                    val endRibbonW = 14.dp.toPx()

                                    fun cubicBezier(t: Float): Offset {
                                        val u = 1f - t
                                        val tt = t * t
                                        val uu = u * u
                                        val uuu = uu * u
                                        val ttt = tt * t
                                        val x = uuu * p0.x + 3 * uu * t * c1.x + 3 * u * tt * c2.x + ttt * p1.x
                                        val y = uuu * p0.y + 3 * uu * t * c1.y + 3 * u * tt * c2.y + ttt * p1.y
                                        return Offset(x, y)
                                    }

                                    fun cubicTangent(t: Float): Offset {
                                        val u = 1f - t
                                        val dx = 3 * u * u * (c1.x - p0.x) + 6 * u * t * (c2.x - c1.x) + 3 * t * t * (p1.x - c2.x)
                                        val dy = 3 * u * u * (c1.y - p0.y) + 6 * u * t * (c2.y - c1.y) + 3 * t * t * (p1.y - c2.y)
                                        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(0.001f)
                                        return Offset(dx / len, dy / len)
                                    }

                                    val steps = 30
                                    val leftEdge = ArrayList<Offset>()
                                    val rightEdge = ArrayList<Offset>()
                                    for (i in 0..steps) {
                                        val t = i / steps.toFloat()
                                        val center = cubicBezier(t)
                                        val tan = cubicTangent(t)
                                        val norm = Offset(-tan.y, tan.x)
                                        val halfW = (startRibbonW + (endRibbonW - startRibbonW) * t) / 2f
                                        leftEdge.add(center + norm * halfW)
                                        rightEdge.add(center - norm * halfW)
                                    }

                                    val ribbonPath = Path().apply {
                                        if (leftEdge.isNotEmpty()) {
                                            moveTo(leftEdge.first().x, leftEdge.first().y)
                                            for (k in 1 until leftEdge.size) lineTo(leftEdge[k].x, leftEdge[k].y)
                                            for (k in rightEdge.indices.reversed()) lineTo(rightEdge[k].x, rightEdge[k].y)
                                            close()
                                        }
                                    }

                                    // 西東京バス風ディープネイビー〜ロイヤルブルーグラデーションリボン
                                    drawPath(
                                        path = ribbonPath,
                                        brush = Brush.linearGradient(
                                            colors = listOf(Color(0xFF1E3A8A), Color(0xFF1D4ED8), Color(0xFF2563EB)),
                                            start = p0,
                                            end = p1
                                        )
                                    )

                                    // リボンのセンターに走る西東京バスレッドの走行軌道ライン
                                    val centerlinePath = Path().apply {
                                        moveTo(p0.x, p0.y)
                                        for (i in 1..steps) {
                                            val pt = cubicBezier(i / steps.toFloat())
                                            lineTo(pt.x, pt.y)
                                        }
                                    }
                                    drawPath(
                                        path = centerlinePath,
                                        color = Color(0x88C0262D),
                                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                                    )

                                    // 外周のハイライトエッジライン
                                    val highlightPath = Path().apply {
                                        if (leftEdge.isNotEmpty()) {
                                            moveTo(leftEdge.first().x, leftEdge.first().y)
                                            for (k in 1 until leftEdge.size) lineTo(leftEdge[k].x, leftEdge[k].y)
                                        }
                                    }
                                    drawPath(
                                        path = highlightPath,
                                        color = Color(0xFF93C5FD),
                                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                                    )

                                    // 各停留所のマーカー ＆ 西東京バスカラー引出線 ＆ 先端矢印
                                    displayedStops.forEachIndexed { index, stop ->
                                        val targetY = stopYPositions[index] ?: return@forEachIndexed
                                        var lo = 0.0f
                                        var hi = 1.0f
                                        for (iter in 0..8) {
                                            val mid = (lo + hi) / 2f
                                            val yMid = cubicBezier(mid).y
                                            if (yMid > targetY) lo = mid else hi = mid
                                        }
                                        val tFound = (lo + hi) / 2f
                                        val center = cubicBezier(tFound)
                                        val tan = cubicTangent(tFound)
                                        val norm = Offset(-tan.y, tan.x)
                                        val halfW = (startRibbonW + (endRibbonW - startRibbonW) * tFound) / 2f
                                        val rightPoint = center - norm * halfW

                                        val leaderStartX = rightPoint.x
                                        val leaderEndX = 78.dp.toPx()

                                        val isNext = stop.isNext
                                        val leaderColor = if (isNext) Color(0xFFC0262D) else Color(0xFF475569)
                                        val leaderStrokeW = if (isNext) 2.2.dp.toPx() else 1.2.dp.toPx()

                                        // 引出線
                                        val leaderPath = Path().apply {
                                            moveTo(leaderStartX, targetY)
                                            val midX = (leaderStartX + 10.dp.toPx()).coerceAtMost(leaderEndX - 10.dp.toPx())
                                            lineTo(midX, targetY)
                                            lineTo(leaderEndX - 4.dp.toPx(), targetY)
                                        }
                                        drawPath(
                                            path = leaderPath,
                                            color = leaderColor,
                                            style = Stroke(width = leaderStrokeW, cap = StrokeCap.Round)
                                        )

                                        // 引出線先端の右向きシェブロン矢印 (西東京バスイメージカラー)
                                        val arrowW = 6.dp.toPx()
                                        val arrowH = 4.5.dp.toPx()
                                        val arrowPath = Path().apply {
                                            moveTo(leaderEndX, targetY)
                                            lineTo(leaderEndX - arrowW, targetY - arrowH)
                                            lineTo(leaderEndX - arrowW * 0.4f, targetY)
                                            lineTo(leaderEndX - arrowW, targetY + arrowH)
                                            close()
                                        }
                                        drawPath(arrowPath, color = leaderColor)

                                        // リボン上の停留所マーカー
                                        if (stop.isNext) {
                                            // 次の停留所: 黄色の楕円ディスク ＋ 西東京バスレッド外枠
                                            val ovalW = 18.dp.toPx()
                                            val ovalH = 12.dp.toPx()
                                            drawOval(
                                                color = Color(0xFFFACC15),
                                                topLeft = Offset(center.x - ovalW / 2f, center.y - ovalH / 2f),
                                                size = Size(ovalW, ovalH)
                                            )
                                            drawOval(
                                                color = Color(0xFFC0262D),
                                                topLeft = Offset(center.x - ovalW / 2f, center.y - ovalH / 2f),
                                                size = Size(ovalW, ovalH),
                                                style = Stroke(width = 2.2.dp.toPx())
                                            )
                                            drawCircle(
                                                color = Color(0xFF181B20),
                                                radius = 2.dp.toPx(),
                                                center = center
                                            )
                                        } else if (stop.isTerminus) {
                                            // 終着駅: 白い円 ＋ 西東京バスレッド二重リング
                                            val rad = 6.5.dp.toPx()
                                            drawCircle(color = Color.White, radius = rad, center = center)
                                            drawCircle(color = Color(0xFFC0262D), radius = rad, center = center, style = Stroke(width = 2.2.dp.toPx()))
                                            drawCircle(color = Color(0xFFC0262D), radius = rad * 0.45f, center = center)
                                        } else {
                                            // その後の停留所: 白い円 ＋ チャコール枠
                                            val rad = 5.2.dp.toPx()
                                            drawCircle(color = Color.White, radius = rad, center = center)
                                            drawCircle(color = Color(0xFF0F172A), radius = rad, center = center, style = Stroke(width = 1.5.dp.toPx()))
                                        }
                                    }

                                    // 手前最前面 (現在地): 西東京バスレッドの先頭バス矢印ポインター
                                    val curCenter = cubicBezier(0.04f)
                                    val tan0 = cubicTangent(0.04f)
                                    val angleDeg = Math.toDegrees(kotlin.math.atan2(tan0.y.toDouble(), tan0.x.toDouble())).toFloat()
                                    rotate(degrees = angleDeg - 90f, pivot = curCenter) {
                                        val pointerW = 20.dp.toPx()
                                        val pointerH = 16.dp.toPx()
                                        val hexPath = Path().apply {
                                            moveTo(curCenter.x, curCenter.y - pointerH / 2f)
                                            lineTo(curCenter.x + pointerW / 2f, curCenter.y)
                                            lineTo(curCenter.x + pointerW * 0.35f, curCenter.y + pointerH / 2f)
                                            lineTo(curCenter.x, curCenter.y + pointerH * 0.25f)
                                            lineTo(curCenter.x - pointerW * 0.35f, curCenter.y + pointerH / 2f)
                                            lineTo(curCenter.x - pointerW / 2f, curCenter.y)
                                            close()
                                        }
                                        drawPath(hexPath, color = Color(0xFFC0262D))
                                        drawPath(hexPath, color = Color(0xFFFAF6EE), style = Stroke(width = 1.8.dp.toPx()))
                                        drawCircle(color = Color(0xFFFAF6EE), radius = 2.2.dp.toPx(), center = curCenter)
                                    }
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(start = 82.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    displayedStops.indices.reversed().forEach { index ->
                                        val stop = displayedStops[index]
                                        val deltaStops = (stop.indexInTrip - nextStopIdx).coerceAtLeast(0)
                                        val stopRemainingMin = (guidance?.remainingMinutes ?: 2) + deltaStops * minutesPerStop
                                        val stopEtaTime = now.plusMinutes(stopRemainingMin.toLong())
                                        val romajiName = remember(stop.name) { busStopRomaji(stop.name) }

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onGloballyPositioned { coordinates ->
                                                    val localY = coordinates.positionInParent().y + coordinates.size.height / 2f
                                                    stopYPositions[index] = localY
                                                }
                                                .padding(vertical = 1.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.weight(1f, fill = false)
                                            ) {
                                                when {
                                                    stop.isNext -> {
                                                        Surface(
                                                            color = Color(0xFFC0262D),
                                                            shape = RoundedCornerShape(4.dp)
                                                        ) {
                                                            Text(
                                                                text = "次は",
                                                                color = Color.White,
                                                                fontWeight = FontWeight.Black,
                                                                fontSize = 11.sp,
                                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                        Column {
                                                            Text(
                                                                text = stop.name,
                                                                color = Color.White,
                                                                fontSize = 16.5.sp,
                                                                fontWeight = FontWeight.Black,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            if (romajiName.isNotBlank() && romajiName != stop.name) {
                                                                Text(
                                                                    text = romajiName,
                                                                    color = Color(0xFF94A3B8),
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                        }
                                                    }
                                                    stop.isTerminus -> {
                                                        Surface(
                                                            color = Color(0xFFC0262D),
                                                            shape = RoundedCornerShape(3.dp)
                                                        ) {
                                                            Text(
                                                                text = "終点",
                                                                color = Color.White,
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 9.5.sp,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                        Column {
                                                            Text(
                                                                text = stop.name,
                                                                color = Color.White,
                                                                fontSize = 13.5.sp,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            if (romajiName.isNotBlank() && romajiName != stop.name) {
                                                                Text(
                                                                    text = romajiName,
                                                                    color = Color(0xFF64748B),
                                                                    fontSize = 8.5.sp,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                        }
                                                    }
                                                    else -> {
                                                        Text(
                                                            text = "・",
                                                            color = Color(0xFF64748B),
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Column {
                                                            Text(
                                                                text = stop.name,
                                                                color = Color(0xFFE2E8F0),
                                                                fontSize = 12.5.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            if (romajiName.isNotBlank() && romajiName != stop.name) {
                                                                Text(
                                                                    text = romajiName,
                                                                    color = Color(0xFF64748B),
                                                                    fontSize = 8.sp,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            // 右側: 到着予測時刻 ＆ 降車バッジ (余白を有効活用)
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                if (stop.isAlight) {
                                                    Surface(
                                                        color = Color(0x33C0262D),
                                                        shape = RoundedCornerShape(3.dp),
                                                        border = BorderStroke(1.dp, Color(0xFFC0262D))
                                                    ) {
                                                        Text(
                                                            text = "🚏 降車",
                                                            color = Color(0xFFFF6B6B),
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 9.5.sp,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }

                                                Surface(
                                                    color = if (stop.isNext) Color(0xFF1E2836) else Color(0x441E2836),
                                                    shape = RoundedCornerShape(4.dp),
                                                    border = BorderStroke(1.dp, if (stop.isNext) Color(0xFF334155) else Color(0x22334155))
                                                ) {
                                                    Text(
                                                        text = if (stop.isNext) "${hm(stopEtaTime)} (あと${stopRemainingMin}分)" else hm(stopEtaTime),
                                                        color = if (stop.isNext) Color(0xFF4ADE80) else Color(0xFF94A3B8),
                                                        fontWeight = if (stop.isNext) FontWeight.Bold else FontWeight.Medium,
                                                        fontSize = if (stop.isNext) 10.5.sp else 9.5.sp,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // ── 画面B: 系統・経由・行先モニター画面 (西東京バス LECIP LCD 完全再現) ──
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF181B20))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            // 1行目: [系 統 ▶] 福30
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                NishiTokyoBusArrowBadge(label = "系 統")
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = routeNumber,
                                    color = Color.White,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // 区切り線
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Color(0xFF333B47))
                            )

                            // 2行目: [経 由 ▶] 羽村駅西
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                NishiTokyoBusArrowBadge(label = "経 由")
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = viaName,
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // 区切り線
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Color(0xFF333B47))
                            )

                            // 3行目: [行 先 ▶] 福生駅
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                NishiTokyoBusArrowBadge(label = "行 先")
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = destinationName,
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // 区切り線
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Color(0xFF333B47))
                            )

                            // 4行目: ご乗車ありがとうございます。
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isNearAlight) "まもなくお降りの停留所です。" else "ご乗車ありがとうございます。",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                if (guidance != null) {
                                    Text(
                                        text = "${hm(guidance.arrival)} 着",
                                        color = Color(0xFF4ADE80),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 最下部: バス車内電光テロップ風バー (アンバーLED文字 ＆ 車両番号 1780)
            val marqueeText = if (isNearAlight) {
                "🛑 つぎ・とまります　まもなく ${nextStopName} です。お降りの方はブザーでお知らせください。"
            } else {
                "次は ${nextStopName} です。ご乗車ありがとうございました。"
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F1317))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = marqueeText,
                    color = Color(0xFFF59E0B),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 縦の区切り線
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .height(14.dp)
                        .background(Color(0xFF64748B))
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 写真の車両番号 1780
                Text(
                    text = "1780",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    }
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
    val isBus = trainLeg != null && (
        trainLeg.lineName.contains("バス") ||
        trainLeg.operatorLabel.contains("バス") ||
        trainLeg.operatorLabel.contains("西東京") ||
        trainLeg.trainType.contains("バス")
    )
    val modeEmoji = if (isBus) "🚌" else "🚃"
    val defaultLineName = if (isBus) "路線バス" else "電車"
    val lineColor = if (trainLeg != null && trainLeg.lineColor != 0L) Color(trainLeg.lineColor) else (if (isBus) Color(0xFF0284C7) else Color(0xFFF15A22))
    val lineName = trainLeg?.lineName ?: defaultLineName
    val trainType = trainLeg?.trainType?.ifBlank { if (isBus) "普通" else "各停" } ?: "普通"
    val headsign = trainLeg?.headsign ?: ""
    val rawPlatform = trainLeg?.platform
    val platform = if (!rawPlatform.isNullOrBlank() && rawPlatform != "0") {
        if (rawPlatform.endsWith("番線") || rawPlatform.endsWith("のりば")) rawPlatform else (if (isBus) "${rawPlatform}のりば" else "${rawPlatform}番線")
    } else ""
    val carCount = if (isBus) null else trainLeg?.let { PlatformAndCarResolver.resolveCarCount(it.lineName, it.trainType, false) } ?: 10

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
                        text = "$modeEmoji $lineName",
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

                if (carCount != null) {
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
                                    text = if (isBus) "接近情報" else "発車標",
                                    color = Color(0xFFFFB300),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = if (isBus) "まもなくバスがまいります" else "まもなく電車がまいります",
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
                // ドア上車内LCDトレインビジョン / バス停留所ビジョン
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
                            Text("$modeEmoji 走行中", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
    val isBus = trainLeg != null && (
        trainLeg.lineName.contains("バス") ||
        trainLeg.operatorLabel.contains("バス") ||
        trainLeg.operatorLabel.contains("西東京") ||
        trainLeg.trainType.contains("バス")
    )
    val trainType = trainLeg?.trainType?.ifBlank { if (isBus) "路線バス" else "快速" } ?: (if (isBus) "路線バス" else "快速")

    val animatedSpeed by animateFloatAsState(
        targetValue = speedKmh,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "trainSpeedAnimation"
    )

    // ノッチ・運転ステータス判定 (疑似力行・定速・惰行・ブレーキ)
    val notchText: String
    val notchColor: Color
    if (isBus) {
        when {
            animatedSpeed < 3f -> {
                notchText = "停車中"
                notchColor = Color(0xFF94A3B8)
            }
            animatedSpeed in 3f..45f -> {
                notchText = "一般走行"
                notchColor = Color(0xFF00E676)
            }
            else -> {
                notchText = "定速走行"
                notchColor = Color(0xFF00E5FF)
            }
        }
    } else {
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
                    Text(text = if (isBus) "GPS速度" else "ATC 100", color = Color(0xFF38BDF8), fontSize = 9.sp, fontWeight = FontWeight.Bold)
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

    val itin = NavigationState.itinerary
    val legs = itin?.legs ?: emptyList()
    val now = LocalDateTime.now()
    val activeTransitLeg = legs.firstOrNull { leg ->
        !leg.isWalk && leg.lineName != "自転車" &&
        (leg.arrTime == null || now.isBefore(leg.arrTime.plusMinutes(2)))
    } ?: legs.firstOrNull { !it.isWalk && it.lineName != "自転車" }

    val isBus = activeTransitLeg != null && (
        activeTransitLeg.lineName.contains("バス") ||
        activeTransitLeg.operatorLabel.contains("バス") ||
        activeTransitLeg.operatorLabel.contains("西東京") ||
        activeTransitLeg.trainType.contains("バス")
    )

    // 電車・バス乗車中または駅で待機中かどうか判定（トレインビジョンやバスモニター、鉄道計器の表示対象）
    val isTrainLcdMode = guidance?.kind == GuidanceKind.RIDE || guidance?.kind == GuidanceKind.WAIT

    Box(modifier = modifier.fillMaxSize()) {
        // 上部: 案内カード (バス乗車時は西東京バス風モニター表示、電車時はトレインビジョン/発車標、徒歩・自転車はターンバイターン案内)
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isTrainLcdMode) {
                if (isBus) {
                    BusMonitorTopCard(
                        guidance = guidance,
                        itinerary = NavigationState.itinerary,
                        location = location
                    )
                } else {
                    TrainLcdTopCard(
                        guidance = guidance,
                        itinerary = NavigationState.itinerary
                    )
                }
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