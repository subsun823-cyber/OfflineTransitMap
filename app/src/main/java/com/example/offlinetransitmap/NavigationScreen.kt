package com.example.offlinetransitmap

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val NavTeal = Color(0xFF00605A)
private val NavBlack = Color(0xFF000000)

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

// Google Maps風のターンバイターン案内アイコン
@Composable
fun ManeuverIcon(maneuver: ManeuverType, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(56.dp)) {
        val w = size.width
        val h = size.height
        val strokeW = 7.dp.toPx()
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
                drawCircle(Color.White, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.5f))
            }
        }
    }
}

// ナビ中に、地図の上に重ねる表示(上の案内バナー、下の残り時間のバー)
@Composable
fun NavigationOverlay(
    guidance: Guidance?,
    location: Location?,
    mapBearing: Double,
    followLost: Boolean,
    onRecenter: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // 上: 次の行動
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = NavTeal),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        when (guidance?.kind) {
                            GuidanceKind.WALK, GuidanceKind.BICYCLE -> {
                                ManeuverIcon(guidance.maneuver)
                            }
                            GuidanceKind.WAIT -> Text("🚏", fontSize = 36.sp)
                            GuidanceKind.RIDE -> Text("🚌", fontSize = 36.sp)
                            else -> Text("📍", fontSize = 36.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = guidance?.title ?: "案内を準備しています",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (guidance != null && guidance.subtitle.isNotBlank()) {
                            Text(
                                text = guidance.subtitle,
                                color = Color.White.copy(alpha = 0.85f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    val meters = guidance?.distanceMeters
                    if (meters != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = distLabel(meters),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            if (guidance != null && guidance.next.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = NavTeal),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = guidance.next,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // 下: 現在地に戻る、残り時間
        Column(modifier = Modifier.align(Alignment.BottomCenter)) {
            if (followLost) {
                Surface(
                    color = NavBlack,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .padding(start = 16.dp, bottom = 12.dp)
                        .clickable(onClick = onRecenter)
                ) {
                    Text(
                        text = "➤  現在地に戻る",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                    )
                }
            }
            Surface(
                color = NavBlack,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .border(1.dp, Color.Gray, CircleShape)
                            .clickable(onClick = onStop),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✕", color = Color.White, fontSize = 22.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (guidance != null) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "${guidance.remainingMinutes}",
                                    color = Color.White,
                                    fontSize = 34.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = " 分",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    modifier = Modifier.padding(bottom = 5.dp)
                                )
                                val roadM = guidance.remainingRoadMeters
                                if (roadM != null) {
                                    Text(
                                        text = " · ${distLabel(roadM)}",
                                        color = Color.White.copy(alpha = 0.9f),
                                        fontSize = 18.sp,
                                        modifier = Modifier.padding(bottom = 5.dp, start = 4.dp)
                                    )
                                }
                            }
                            Text(
                                text = "到着 ${hm(guidance.arrival)}",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            Text("準備中…", color = Color.White)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(52.dp))
                }
            }
        }
    }
}