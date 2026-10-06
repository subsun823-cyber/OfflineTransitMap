package com.example.offlinetransitmap

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

// 一覧に並べるもの:日付の見出し、または出発1件
private sealed interface SheetItem {
    data class Header(val label: String) : SheetItem
    data class Entry(val departure: Departure) : SheetItem
}

private fun dateLabel(date: LocalDate, today: LocalDate): String {
    val md = "${date.monthValue}月${date.dayOfMonth}日"
    return if (ChronoUnit.DAYS.between(today, date) == 1L) {
        "明日 $md"
    } else {
        val w = "月火水木金土日"[date.dayOfWeek.value - 1]
        "$md($w)"
    }
}

// 時刻順に並べ、日付が変わるところに見出しを入れる(今日の分には見出しなし)
private fun buildItems(departures: List<Departure>, now: LocalDateTime): List<SheetItem> {
    val today = now.toLocalDate()
    val result = mutableListOf<SheetItem>()
    var lastDate = today
    for (d in departures.sortedBy { it.time }) {
        val date = d.time.toLocalDate()
        if (date != lastDate) {
            result.add(SheetItem.Header(dateLabel(date, today)))
            lastDate = date
        }
        result.add(SheetItem.Entry(d))
    }
    return result
}

private fun formatTime(t: LocalDateTime): String =
    "${t.hour}:${"%02d".format(t.minute)}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepartureSheet(
    stationName: String,
    departures: List<Departure>,
    note: String? = null,
    loadTripStops: (Departure) -> List<TripStop> = { emptyList() },
    onDismiss: () -> Unit
) {
    val now = remember { LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES) }
    val sheetItems = remember(departures) { buildItems(departures, now) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 一覧の高さは画面の半分まで(地図が上に見えるようにする)
    val listMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    // タップされた出発(null のときは出発一覧を表示)
    var selected by remember { mutableStateOf<Departure?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false
    ) {
        // 詳細表示中の「戻る」操作は、シートを閉じずに一覧へ戻る
        BackHandler(enabled = selected != null) { selected = null }

        val detail = selected
        if (detail == null) {
            Text(
                text = stationName,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            if (sheetItems.isEmpty()) {
                Text(
                    text = "この駅の出発情報がありません(時刻表の有効期間外の可能性があります)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = listMaxHeight)
            ) {
                items(sheetItems) { item ->
                    when (item) {
                        is SheetItem.Header -> DateHeader(item.label)
                        is SheetItem.Entry -> {
                            val dep = item.departure
                            DepartureRow(
                                d = dep,
                                minutes = ChronoUnit.MINUTES.between(now, dep.time),
                                // DBの便(tripNo != 0)だけ、タップで詳細を開く
                                onClick = if (dep.tripNo != 0L) ({ selected = dep }) else null
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        } else {
            val stops = remember(detail) { loadTripStops(detail) }
            TripDetailContent(
                departure = detail,
                stationName = stationName,
                stops = stops,
                listMaxHeight = listMaxHeight,
                onBack = { selected = null }
            )
        }
    }
}

@Composable
private fun DateHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp)
    )
}

// 「事業者 / 路線バッジ / ◯◯行き」の1行
@Composable
private fun LineTitle(d: Departure, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = d.operatorLabel,
            style = MaterialTheme.typography.labelSmall,
            color = Color(d.lineColor),
            maxLines = 1,
            modifier = Modifier
                .border(1.dp, Color(d.lineColor), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = d.lineName,
            color = if (Color(d.lineColor).luminance() > 0.6f) Color.Black else Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier
                .background(Color(d.lineColor), RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 1.dp)
        )
        Spacer(Modifier.width(6.dp))
            if (d.trainType.isNotBlank()) {
                Text(
                    text = d.trainType,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                Spacer(Modifier.width(6.dp))
            }

                    Text(
                    text = d.headsign + if (d.isBus || d.tripNo != 0L) "行き" else "方面",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun DepartureRow(d: Departure, minutes: Long, onClick: (() -> Unit)? = null) {
    val within1Hour = minutes < 60
    val status = if (within1Hour) "定刻" else "定刻出発予定"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LineTitle(d)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (d.detail.isBlank()) status else "$status · ${d.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (within1Hour) {
                if (minutes <= 0) {
                    Text("まもなく", style = MaterialTheme.typography.titleSmall)
                } else {
                    Text("$minutes", style = MaterialTheme.typography.titleLarge)
                    Text("分後", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Text(formatTime(d.time), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

// 便の詳細(経由地と時刻の一覧)
@Composable
private fun ColumnScope.TripDetailContent(
    departure: Departure,
    stationName: String,
    stops: List<TripStop>,
    listMaxHeight: Dp,
    onBack: () -> Unit
) {
    var showPrevious by remember { mutableStateOf(false) }
    // 乗る停留所の位置(見つからなければ先頭扱い)
    val boardIndex = stops.indexOfFirst { it.seq == departure.seq }.coerceAtLeast(0)
    val shown = if (showPrevious) stops else stops.drop(boardIndex)
    val lineColor = Color(departure.lineColor)

    TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 12.dp)) {
        Text("‹ 一覧に戻る")
    }
    LineTitle(departure, Modifier.padding(horizontal = 24.dp))
    Text(
        text = "$stationName 発",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp)
    )

    if (stops.isEmpty()) {
        Text(
            text = "この便の詳細情報がありません",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )
    } else {
        if (boardIndex > 0) {
            TextButton(
                onClick = { showPrevious = !showPrevious },
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Text(if (showPrevious) "前の経由地を非表示" else "前の経由地を表示(${boardIndex}か所)")
            }
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(max = listMaxHeight)
        ) {
            items(shown) { stop ->
                StopRow(
                    stop = stop,
                    isBoarding = stop.seq == departure.seq,
                    isBefore = stop.seq < departure.seq,
                    color = lineColor
                )
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

// 経由地1つ分:左に縦のラインと丸、中央に停留所名、右に時刻
@Composable
private fun StopRow(stop: TripStop, isBoarding: Boolean, isBefore: Boolean, color: Color) {
    val lineColor = if (isBefore) color.copy(alpha = 0.35f) else color

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 24.dp),
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
            Box(
                modifier = Modifier
                    .size(if (isBoarding) 16.dp else 10.dp)
                    .background(Color.White, CircleShape)
                    .border(2.dp, lineColor, CircleShape)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp)
        ) {
            Text(
                text = stop.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isBoarding) FontWeight.Bold else FontWeight.Normal
            )
            if (stop.platform.isNotBlank()) {
                Text(
                    text = stop.platform,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = formatTime(stop.time),
            style = if (isBoarding) MaterialTheme.typography.titleLarge
            else MaterialTheme.typography.bodyMedium
        )
    }
}