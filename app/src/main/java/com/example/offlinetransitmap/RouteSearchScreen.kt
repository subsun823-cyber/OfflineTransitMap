package com.example.offlinetransitmap

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

// 検索画面の入力と結果(画面を閉じても残しておくために、画面の外で持つ)
class RouteSearchState {
    var originText by mutableStateOf("")
    var destText by mutableStateOf("")
    var origin by mutableStateOf<StationEntry?>(null)
    var dest by mutableStateOf<StationEntry?>(null)
    var departAt by mutableStateOf<LocalDateTime?>(null) // null = いま
    var candidates by mutableStateOf<List<Itinerary>?>(null)
    var searchKey by mutableStateOf<String?>(null)
    var searching by mutableStateOf(false)
    var sortMode by mutableStateOf(SortMode.FASTEST)
    var activeField by mutableIntStateOf(1) // 0 = 出発地, 1 = 目的地
    var initialized by mutableStateOf(false)
}

fun hm(t: LocalDateTime): String = "${t.hour}:${"%02d".format(t.minute)}"

private fun dateText(d: LocalDate): String {
    val w = "月火水木金土日"[d.dayOfWeek.value - 1]
    return "${d.monthValue}/${d.dayOfMonth}($w)"
}

private fun ymdText(d: LocalDate): String = "${d.year}/${d.monthValue}/${d.dayOfMonth}"

// 「約36分 · 乗換1回 · 410円」(小さい文字で表示する)
fun summaryText(itin: Itinerary): String {
    val minutes = ChronoUnit.MINUTES.between(itin.departure, itin.arrival)
    val transfer = if (itin.transfers == 0) "乗換なし" else "乗換${itin.transfers}回"
    val total = itin.fare
    val fare = when {
        total != null -> "${total}円"
        itin.knownFare > 0 -> "${itin.knownFare}円+運賃不明の区間"
        else -> "料金不明"
    }
    return "約${minutes}分 · $transfer · $fare"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteSearchScreen(
    state: RouteSearchState,
    searcher: RouteSearcher,
    currentLocation: Pair<Double, Double>?,
    onSelect: (Itinerary) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    val range = remember { searcher.validityRange() }

    // 画面を開いたとき: 駅データを読み込み、(最初の1回だけ)現在地に最も近い停留所を出発地にする
    LaunchedEffect(Unit) {
        val st = withContext(Dispatchers.Default) {
            searcher.warmUp()
            currentLocation?.let { searcher.nearestStation(it.first, it.second) }
        }
        if (!state.initialized) {
            state.initialized = true
            if (st != null && state.origin == null && state.originText.isEmpty()) {
                state.origin = st
                state.originText = st.name
            }
        }
    }

    // 出発地・目的地・出発日時が変わったら検索する(同じ条件で既に検索済みなら何もしない)
    LaunchedEffect(state.origin, state.dest, state.departAt) {
        val o = state.origin
        val d = state.dest
        if (o == null || d == null) {
            state.candidates = null
            state.searching = false
            state.searchKey = null
            return@LaunchedEffect
        }
        val key = "${o.id}|${d.id}|${state.departAt}"
        if (key == state.searchKey) return@LaunchedEffect
        state.searching = true
        val at = state.departAt ?: LocalDateTime.now()
        val result = withContext(Dispatchers.Default) {
            searcher.findRoutes(o.id, d.id, at)
        }
        state.candidates = result
        state.searchKey = key
        state.searching = false
    }

    val activeText = if (state.activeField == 0) state.originText else state.destText
    val activeSelected = if (state.activeField == 0) state.origin else state.dest
    val suggestions = remember(activeText, activeSelected?.id) {
        if (activeText.isNotBlank() && activeSelected?.name != activeText) {
            searcher.searchStations(activeText)
        } else {
            emptyList()
        }
    }

    fun choose(st: StationEntry) {
        if (state.activeField == 0) {
            state.origin = st
            state.originText = st.name
            if (state.dest == null) state.activeField = 1
        } else {
            state.dest = st
            state.destText = st.name
        }
    }

    val base = state.departAt ?: LocalDateTime.now()

    Surface(modifier = Modifier.fillMaxSize().imePadding()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("‹ 地図に戻る") }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        val o = state.origin
                        val d = state.dest
                        val ot = state.originText
                        state.originText = state.destText
                        state.destText = ot
                        state.origin = d
                        state.dest = o
                    }
                ) { Text("⇄ 入れ替え") }
            }
            OutlinedTextField(
                value = state.originText,
                onValueChange = {
                    state.originText = it
                    state.origin = null
                },
                label = { Text("出発地") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) state.activeField = 0 }
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.destText,
                onValueChange = {
                    state.destText = it
                    state.dest = null
                },
                label = { Text("目的地") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) state.activeField = 1 }
            )

            // 出発日時
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "出発",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { showDate = true }) { Text(dateText(base.toLocalDate())) }
                TextButton(onClick = { showTime = true }) { Text(hm(base)) }
                if (state.departAt != null) {
                    TextButton(onClick = { state.departAt = null }) { Text("いま") }
                } else {
                    Text(
                        "(いま)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val day = base.toLocalDate()
            if (range != null && (day.isBefore(range.first) || day.isAfter(range.second))) {
                Text(
                    "この日は時刻表のデータの範囲外です(${ymdText(range.first)}〜${ymdText(range.second)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(8.dp))

            val all = state.candidates
            if (suggestions.isNotEmpty()) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(suggestions) { st ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { choose(st) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(st.name, style = MaterialTheme.typography.bodyLarge)
                            if (st.kana.isNotEmpty()) {
                                Text(
                                    st.kana,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            } else if (state.searching) {
                Text("検索中…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
            } else if (all == null) {
                Text(
                    "出発地と目的地を入力してください(ひらがなでも検索できます)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
            } else if (all.isEmpty()) {
                Text(
                    "経路が見つかりませんでした。\n(バスとJR東日本の首都圏の路線を対象に、徒歩300m以内の乗換・出発から6時間以内で検索しています。時刻や日付を変えると見つかることがあります)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(8.dp)
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.sortMode == SortMode.FASTEST,
                        onClick = { state.sortMode = SortMode.FASTEST },
                        label = { Text("早い順") }
                    )
                    FilterChip(
                        selected = state.sortMode == SortMode.FEWEST_TRANSFERS,
                        onClick = { state.sortMode = SortMode.FEWEST_TRANSFERS },
                        label = { Text("乗換少ない順") }
                    )
                    FilterChip(
                        selected = state.sortMode == SortMode.CHEAPEST,
                        onClick = { state.sortMode = SortMode.CHEAPEST },
                        label = { Text("料金安い順") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                val ranked = remember(all, state.sortMode) { searcher.rank(all, state.sortMode) }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(ranked) { itin ->
                        ItineraryCard(itin = itin, onClick = { onSelect(itin) })
                        Spacer(Modifier.height(8.dp))
                    }
                    item {
                        Text(
                            "カードをタップすると、地図に経路を表示します。料金は大人・データ上の運賃の合計です(IC割引・小児運賃は含みません)。「料金不明」は運賃データのない区間を含みます。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }

    if (showDate) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = base.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { ms ->
                            val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                            state.departAt = LocalDateTime.of(d, base.toLocalTime().withSecond(0).withNano(0))
                        }
                        showDate = false
                    }
                ) { Text("決定") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("キャンセル") } }
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTime) {
        val timeState = rememberTimePickerState(
            initialHour = base.hour,
            initialMinute = base.minute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.departAt = LocalDateTime.of(
                            base.toLocalDate(),
                            LocalTime.of(timeState.hour, timeState.minute)
                        )
                        showTime = false
                    }
                ) { Text("決定") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("キャンセル") } },
            text = { TimePicker(state = timeState) }
        )
    }
}

@Composable
fun LineBadge(name: String, color: Long) {
    val bg = Color(color)
    Text(
        text = name,
        color = if (bg.luminance() > 0.6f) Color.Black else Color.White,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = Modifier
            .background(bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
private fun ItineraryCard(itin: Itinerary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${hm(itin.departure)} → ${hm(itin.arrival)}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.weight(1f))
                for (leg in itin.legs) {
                    if (!leg.isWalk) {
                        Spacer(Modifier.width(4.dp))
                        LineBadge(leg.lineName, leg.lineColor)
                    }
                }
            }
            Text(
                text = summaryText(itin),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}