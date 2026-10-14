package com.example.offlinetransitmap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

private val WALK_GRAY = Color(0xFF9E9E9E)

// 縦の流れに並べるもの:停留所、乗車区間、徒歩区間
private sealed interface TlItem {
    data class Stop(
        val time: String,
        val name: String,
        val sub: String,
        val color: Color,
        val isTerminal: Boolean
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
            out.add(TlItem.Walk("徒歩 約${formatDurationHoursMinutes(leg.walkMinutes.toLong())} (${leg.walkMeters}m)"))
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
                    isTerminal = false
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

// 左に縦の線と丸、右に内容を並べる1行 (モダンなタイムライン)
@Composable
private fun TimelineRow(
    lineColor: Color,
    isWalk: Boolean = false,
    dotContent: (@Composable () -> Unit)? = null,
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
                .width(28.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            if (isWalk) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(WALK_GRAY.copy(alpha = 0.5f))
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(lineColor)
                )
            }
            dotContent?.invoke()
        }
        Spacer(Modifier.width(12.dp))
        content()
    }
}

// 画面下に出す、経路の詳細パネル (洗練されたデザイン & 運行なし時の案内対応)
@Composable
fun RouteDetailPanel(
    itin: Itinerary?,
    candidates: List<Itinerary>? = null,
    onSelectCandidate: ((Itinerary) -> Unit)? = null,
    originName: String = "出発地",
    destName: String = "目的地",
    viaNames: List<String> = emptyList(),
    targetTime: LocalDateTime = LocalDateTime.now(),
    timeType: TimeTargetType = TimeTargetType.DEPART,
    onTimeChanged: ((LocalDateTime, TimeTargetType) -> Unit)? = null,
    onSearchTomorrowMorning: (() -> Unit)? = null,
    onSwapOriginDest: (() -> Unit)? = null,
    onAddVia: (() -> Unit)? = null,
    onRemoveVia: ((Int) -> Unit)? = null,
    onClickOrigin: (() -> Unit)? = null,
    onClickDest: (() -> Unit)? = null,
    onClickVia: ((Int) -> Unit)? = null,
    onBackToResults: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    isSearching: Boolean = false,
    navigating: Boolean = false,
    onStartNavigation: (() -> Unit)? = null,
    onStopNavigation: (() -> Unit)? = null
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
    val timeline = remember(itin) { if (itin != null) buildTimeline(itin) else emptyList() }
    var showTimeDialog by remember { mutableStateOf(false) }
    var showDateDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(modifier = Modifier.heightIn(max = maxHeight)) {
            // 最上部: ドラッグハンドルバー ＆ 閉じるボタン
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        .align(Alignment.Center)
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.CenterEnd)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "閉じる",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 再検索中プログレスバー
            if (isSearching) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )
            } else {
                Spacer(Modifier.height(3.dp))
            }

            // 出発地・経由地・目的地 コネクタボックス
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左側: コネクタ表示 (🟢 ─ 🟡 ─ 🔴)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, end = 10.dp)
                    ) {
                        Box(modifier = Modifier.size(10.dp).background(Color(0xFF2E7D32), CircleShape))
                        Box(modifier = Modifier.width(2.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        if (viaNames.isNotEmpty()) {
                            viaNames.forEach { _ ->
                                Box(modifier = Modifier.size(8.dp).background(Color(0xFFF57C00), CircleShape))
                                Box(modifier = Modifier.width(2.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                            }
                        }
                        Box(modifier = Modifier.size(10.dp).background(Color(0xFFD32F2F), CircleShape))
                    }

                    // 中央: 出発・経由・目的地 テキスト
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 出発地
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = onClickOrigin != null) { onClickOrigin?.invoke() }
                        ) {
                            Text(
                                text = "発: ",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = originName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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

                        // 経由地
                        viaNames.forEachIndexed { index, viaName ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = onClickVia != null) { onClickVia?.invoke(index) }
                            ) {
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
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (onRemoveVia != null) {
                                    IconButton(
                                        onClick = { onRemoveVia(index) },
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "経由地を削除",
                                            modifier = Modifier.size(14.dp),
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
                            Text(
                                text = "着: ",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = destName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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

                        // 経由地追加リンク
                        if (onAddVia != null && viaNames.size < 2) {
                            Text(
                                text = "＋ 経由地を追加",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { onAddVia() }
                            )
                        }
                    }

                    // 右側: 出発・目的地スワップボタン
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

            // 出発日・時刻設定バー
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. 日付選択チップ
                val curDate = targetTime.toLocalDate()
                val dow = "月火水木金土日"[curDate.dayOfWeek.value - 1]
                val dateLabel = "${curDate.monthValue}/${curDate.dayOfMonth}($dow)"
                Surface(
                    onClick = { showDateDialog = true },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "📅", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = dateLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "▼",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 2. 時刻選択チップ
                val typeLabel = if (timeType == TimeTargetType.DEPART) "出発" else "到着"
                val timeLabel = "${hm(targetTime)} $typeLabel"
                Surface(
                    onClick = { showTimeDialog = true },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "🕒", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "▼",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 3. 現在時刻ボタン
                OutlinedButton(
                    onClick = {
                        onTimeChanged?.invoke(LocalDateTime.now(), TimeTargetType.DEPART)
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("現在", style = MaterialTheme.typography.labelSmall)
                }
            }

            // 再検索中案内バナー
            if (isSearching) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "経路を再検索しています…",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // 日付ダイアログ
            if (showDateDialog) {
                val pickerState = rememberDatePickerState(
                    initialSelectedDateMillis = targetTime.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                )
                DatePickerDialog(
                    onDismissRequest = { showDateDialog = false },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pickerState.selectedDateMillis?.let { ms ->
                                    val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                                    val newDateTime = LocalDateTime.of(d, targetTime.toLocalTime())
                                    onTimeChanged?.invoke(newDateTime, timeType)
                                }
                                showDateDialog = false
                            }
                        ) {
                            Text("決定")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDateDialog = false }) {
                            Text("キャンセル")
                        }
                    }
                ) {
                    DatePicker(state = pickerState)
                }
            }

            // 時刻ダイアログ
            if (showTimeDialog) {
                RouteTimePickerDialog(
                    initialTime = targetTime,
                    initialType = timeType,
                    onConfirm = { newTime, newType ->
                        onTimeChanged?.invoke(newTime, newType)
                        showTimeDialog = false
                    },
                    onDismiss = { showTimeDialog = false }
                )
            }

            if (itin != null) {
                // 終電後の翌朝始発案内カード
                if (itin.isNextDayFirstTrain) {
                    val depDate = itin.departure.toLocalDate()
                    val dayOfWeekStr = arrayOf("月", "火", "水", "木", "金", "土", "日")[depDate.dayOfWeek.value - 1]
                    val dateText = "${depDate.monthValue}/${depDate.dayOfMonth}($dayOfWeekStr)"
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFFF3E0),
                        border = BorderStroke(1.dp, Color(0xFFFFB74D))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🌅", fontSize = 22.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "終電後のため、翌朝の始発ルートを表示しています",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE65100)
                                )
                                Text(
                                    text = "始発: $dateText ${hm(itin.departure)}発の最も早い便",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFBF360C)
                                )
                            }
                        }
                    }
                }

                // 候補ルートタブ (複数ある場合)
                if (candidates != null && candidates.size > 1 && onSelectCandidate != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (isSearching) 0.55f else 1.0f)
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        candidates.forEachIndexed { index, candidate ->
                            val isSelected = candidate === itin || (candidate.departure == itin.departure && candidate.arrival == itin.arrival && candidate.routeKey == itin.routeKey)
                            val durMin = java.time.temporal.ChronoUnit.MINUTES.between(candidate.departure, candidate.arrival)
                            val isFastest = index == 0
                            Surface(
                                onClick = { onSelectCandidate(candidate) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "ルート${index + 1}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isFastest) {
                                            Spacer(Modifier.width(4.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.primary
                                            ) {
                                                Text(
                                                    text = "最速",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "${hm(candidate.departure)} → ${hm(candidate.arrival)} (${formatDurationHoursMinutes(durMin)})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // サマリーヘッダー (発着時刻・所要時間・乗換回数・運賃バッジ & ナビボタン)
                val durMin = Duration.between(itin.departure, itin.arrival).toMinutes()
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = hm(itin.departure),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "  ➔  ",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = hm(itin.arrival),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            if (onStartNavigation != null || onStopNavigation != null) {
                                if (navigating) {
                                    OutlinedButton(
                                        onClick = { onStopNavigation?.invoke() },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text("ナビ終了", style = MaterialTheme.typography.labelMedium)
                                    }
                                } else if (onStartNavigation != null) {
                                    Button(
                                        onClick = onStartNavigation,
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text("🧭 ナビ開始", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "所要 約${formatDurationHoursMinutes(durMin)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = if (itin.transfers == 0) "乗換なし (直通)" else "乗換 ${itin.transfers}回",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (itin.fare != null || itin.knownFare > 0) {
                                val f = itin.fare ?: itin.knownFare
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = "${f}円",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // タイムライン一覧
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .alpha(if (isSearching) 0.55f else 1.0f)
                ) {
                    items(timeline) { item ->
                        when (item) {
                            is TlItem.Stop -> TimelineRow(
                                lineColor = item.color,
                                dotContent = {
                                    if (item.isTerminal) {
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                                                .border(3.dp, item.color, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(item.color, CircleShape)
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .background(item.color, CircleShape)
                                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                        )
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = item.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (item.sub.isNotBlank()) {
                                                Spacer(Modifier.width(8.dp))
                                                Surface(
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = item.sub,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = item.time,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            is TlItem.Ride -> {
                                val leg = item.leg
                                val minutes = if (leg.depTime != null && leg.arrTime != null) {
                                    Duration.between(leg.depTime, leg.arrTime).toMinutes()
                                } else {
                                    0L
                                }
                                TimelineRow(lineColor = Color(leg.lineColor)) {
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(vertical = 4.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                val isBus = leg.trainType.contains("バス") || leg.lineName.contains("バス")
                                                Text(if (isBus) "🚌" else "🚆", fontSize = 16.sp)
                                                Spacer(Modifier.width(6.dp))
                                                LineBadge(leg.lineName, leg.lineColor)
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = (if (leg.trainType.isBlank()) "" else "${leg.trainType} ") + "${leg.headsign}行き",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            val fareText = leg.fare?.let {
                                                val note = if (!leg.fareNote.isNullOrBlank()) " (${leg.fareNote})" else ""
                                                "${it}円$note"
                                            } ?: "運賃込"
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = "⏱️ 約${formatDurationHoursMinutes(minutes)}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = "·",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = "🎫 $fareText",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            is TlItem.Walk -> TimelineRow(lineColor = WALK_GRAY.copy(alpha = 0.6f), isWalk = true) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🚶", fontSize = 14.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = item.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            } else {
                if (isSearching) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp, horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                        Text(
                            text = "最適な経路を検索しています…",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "時刻表データと路線ネットワークを照合中",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // 運行便なし (終電後など) プレースホルダー表示
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp, horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("🌙", fontSize = 42.sp)
                        Text(
                            text = "指定された日時に運行便がありません",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "終電後などの時間帯です。\n上記の日付・時刻チップから運行時間帯を指定するか、翌朝の便で再検索してください。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (onSearchTomorrowMorning != null) {
                            Button(
                                onClick = onSearchTomorrowMorning,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text("翌朝 06:00 で再検索", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteTimePickerDialog(
    initialTime: LocalDateTime,
    initialType: TimeTargetType,
    onConfirm: (LocalDateTime, TimeTargetType) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedType by remember { mutableStateOf(initialType) }
    val timeState = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "日時の設定",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 出発 / 到着 切り替え
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    FilterChip(
                        selected = selectedType == TimeTargetType.DEPART,
                        onClick = { selectedType = TimeTargetType.DEPART },
                        label = { Text("出発時刻指定") },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    FilterChip(
                        selected = selectedType == TimeTargetType.ARRIVE,
                        onClick = { selectedType = TimeTargetType.ARRIVE },
                        label = { Text("到着時刻指定") }
                    )
                }

                // Material3 TimePicker
                TimePicker(state = timeState)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newTime = LocalDateTime.of(
                        initialTime.toLocalDate(),
                        LocalTime.of(timeState.hour, timeState.minute)
                    )
                    onConfirm(newTime, selectedType)
                }
            ) {
                Text("決定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}