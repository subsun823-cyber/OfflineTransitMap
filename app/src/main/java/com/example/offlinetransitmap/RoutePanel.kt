package com.example.offlinetransitmap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            out.add(TlItem.Walk("徒歩 約${formatDurationHoursMinutes(leg.walkMinutes.toLong())}(${leg.walkMeters}m)"))
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
    candidates: List<Itinerary>? = null,
    onSelectCandidate: ((Itinerary) -> Unit)? = null,
    originName: String = "出発地",
    destName: String = "目的地",
    viaNames: List<String> = emptyList(),
    onSwapOriginDest: (() -> Unit)? = null,
    onAddVia: (() -> Unit)? = null,
    onRemoveVia: ((Int) -> Unit)? = null,
    onClickOrigin: (() -> Unit)? = null,
    onClickDest: (() -> Unit)? = null,
    onClickVia: ((Int) -> Unit)? = null,
    onBackToResults: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    navigating: Boolean = false,
    onStartNavigation: (() -> Unit)? = null,
    onStopNavigation: (() -> Unit)? = null
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.65f).dp
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
                TextButton(onClick = onClose) { Text("‹ 地図に戻る") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("閉じる") }
            }

            // 出発地・経由地・目的地 ボックス (入れ替え・経由地追加機能つき)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 出発地
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = onClickOrigin != null) { onClickOrigin?.invoke() }
                        ) {
                            Text(text = "🟢", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "出発: ",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = originName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            if (onClickOrigin != null) {
                                Text(
                                    text = "変更",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // 経由地リスト
                        viaNames.forEachIndexed { index, viaName ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = onClickVia != null) { onClickVia?.invoke(index) }
                            ) {
                                Text(text = "🟡", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "経由${if (viaNames.size > 1) "${index + 1}" else ""}: ",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = viaName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                if (onRemoveVia != null) {
                                    IconButton(
                                        onClick = { onRemoveVia(index) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "経由地を削除",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // 目的地
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = onClickDest != null) { onClickDest?.invoke() }
                        ) {
                            Text(text = "🔴", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "目的: ",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = destName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            if (onClickDest != null) {
                                Text(
                                    text = "変更",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // 経由地追加ボタン (経由地が2個未満の場合)
                        if (onAddVia != null && viaNames.size < 2) {
                            Text(
                                text = "＋ 経由地を追加",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { onAddVia() }
                            )
                        }
                    }

                    // 右側の入れ替えボタン (スワップ)
                    if (onSwapOriginDest != null) {
                        Spacer(Modifier.width(8.dp))
                        FilledTonalIconButton(
                            onClick = onSwapOriginDest,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text(
                                text = "⇅",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            if (candidates != null && candidates.size > 1 && onSelectCandidate != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    candidates.forEachIndexed { index, candidate ->
                        val isSelected = candidate === itin || (candidate.departure == itin.departure && candidate.arrival == itin.arrival && candidate.routeKey == itin.routeKey)
                        val durMin = java.time.temporal.ChronoUnit.MINUTES.between(candidate.departure, candidate.arrival)
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectCandidate(candidate) },
                            label = {
                                Text(
                                    text = "ルート${index + 1} (${formatDurationHoursMinutes(durMin)})",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
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
                                    val fareText = leg.fare?.let {
                                        val note = if (!leg.fareNote.isNullOrBlank()) " (${leg.fareNote})" else ""
                                        "${it}円$note"
                                    } ?: "料金不明"
                                    Text(
                                        text = "約${formatDurationHoursMinutes(minutes)} · $fareText",
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

@Composable
fun StationSelectDialog(
    title: String,
    onSearch: (String) -> List<StationEntry>,
    currentLocationStation: StationEntry? = null,
    onSelectStation: (StationEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) {
        if (query.isBlank()) emptyList() else onSearch(query)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("駅名を入力 (ひらがな可)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "クリア")
                            }
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    if (query.isBlank()) {
                        if (currentLocationStation != null) {
                            item {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectStation(currentLocationStation) }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    color = Color.Transparent
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("📍", style = MaterialTheme.typography.bodyMedium)
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = "現在地最寄り: ${currentLocationStation.name}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (currentLocationStation.kana.isNotBlank()) {
                                                Text(
                                                    text = currentLocationStation.kana,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            item {
                                Text(
                                    text = "駅名を入力して検索してください",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    } else if (results.isEmpty()) {
                        item {
                            Text(
                                text = "「$query」に一致する駅が見つかりませんでした",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    } else {
                        items(results) { st ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectStation(st) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                color = Color.Transparent
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🚉", style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = st.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (st.kana.isNotBlank()) {
                                            Text(
                                                text = st.kana,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}