package com.example.offlinetransitmap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Duration

private val WALK_GRAY = Color(0xFF9E9E9E)

// 縦の流れに並べるもの:停留所、バスに乗っている区間、徒歩の区間
private sealed interface TlItem {
    data class Stop(
        val time: String,
        val name: String,
        val sub: String,
        val color: Color,
        val big: Boolean
    ) : TlItem

    data class Ride(val leg: RouteLeg) : TlItem
    data class Walk(val text: String) : TlItem
}

private fun buildTimeline(itin: Itinerary): List<TlItem> {
    val out = ArrayList<TlItem>()
    val last = itin.legs.size - 1
    for ((i, leg) in itin.legs.withIndex()) {
        if (leg.isWalk) {
            if (i == 0) out.add(TlItem.Stop(hm(itin.departure), leg.fromName, "出発", WALK_GRAY, true))
            out.add(TlItem.Walk("徒歩 約${leg.walkMinutes}分(${leg.walkMeters}m)"))
            if (i == last) out.add(TlItem.Stop(hm(itin.arrival), leg.toName, "到着", WALK_GRAY, true))
        } else {
            val color = Color(leg.lineColor)
            val dep = leg.depTime ?: itin.departure
            val arr = leg.arrTime ?: itin.arrival
            val prev = out.lastOrNull()
            if (i > 0 && prev is TlItem.Stop && prev.name == leg.fromName) {
                // 同じ停留所での乗換は、降りる行と乗る行を1つにまとめる
                val sub = if (leg.platform.isBlank()) "乗換" else "乗換 · ${leg.platform}"
                out[out.lastIndex] = prev.copy(
                    time = "${prev.time} → ${hm(dep)}",
                    sub = sub,
                    color = color,
                    big = false
                )
            } else {
                out.add(TlItem.Stop(hm(dep), leg.fromName, leg.platform, color, i == 0))
            }
            out.add(TlItem.Ride(leg))
            out.add(TlItem.Stop(hm(arr), leg.toName, if (i == last) "到着" else "", color, i == last))
        }
    }
    return out
}

// 左に縦の線と丸、右に内容を並べる1行
@Composable
private fun TimelineRow(
    lineColor: Color,
    dotSize: Dp,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(24.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(lineColor)
            )
            if (dotSize > 0.dp) {
                Box(
                    modifier = Modifier
                        .size(dotSize)
                        .background(Color.White, CircleShape)
                        .border(2.dp, lineColor, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        content()
    }
}

// 画面下に出す、経路の詳細パネル
@Composable
fun RouteDetailPanel(
    itin: Itinerary,
    onBackToResults: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    navigating: Boolean = false,
    onStartNavigation: (() -> Unit)? = null,
    onStopNavigation: (() -> Unit)? = null
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    val timeline = remember(itin) { buildTimeline(itin) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(modifier = Modifier.heightIn(max = maxHeight)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                TextButton(onClick = onBackToResults) { Text("‹ 結果に戻る") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("閉じる") }
            }
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "${hm(itin.departure)} → ${hm(itin.arrival)}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = summaryText(itin),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onStartNavigation != null || onStopNavigation != null) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (navigating) {
                        Text(
                            text = "ナビ中(通知でお知らせします)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = { onStopNavigation?.invoke() }) { Text("ナビ終了") }
                    } else {
                        Button(onClick = { onStartNavigation?.invoke() }) { Text("ナビ開始") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(timeline) { item ->
                    when (item) {
                        is TlItem.Stop -> TimelineRow(item.color, if (item.big) 16.dp else 11.dp) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (item.sub.isNotBlank()) {
                                    Text(
                                        text = item.sub,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(text = item.time, style = MaterialTheme.typography.bodyMedium)
                        }

                        is TlItem.Ride -> {
                            val leg = item.leg
                            val minutes = if (leg.depTime != null && leg.arrTime != null) {
                                Duration.between(leg.depTime, leg.arrTime).toMinutes()
                            } else {
                                0L
                            }
                            TimelineRow(Color(leg.lineColor), 0.dp) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        LineBadge(leg.lineName, leg.lineColor)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = (if (leg.trainType.isBlank()) "" else "${leg.trainType} ") + "${leg.headsign}行き",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Text(
                                        text = "約${minutes}分 · " + (leg.fare?.let { "${it}円" } ?: "料金不明"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        is TlItem.Walk -> TimelineRow(WALK_GRAY.copy(alpha = 0.6f), 0.dp) {
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}