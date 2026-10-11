package com.example.offlinetransitmap

import android.database.sqlite.SQLiteDatabase
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 踏切スポット・路線別制覇ダイアログ
 */
@Composable
fun NearbyCrossingsDialog(
    poiDatabase: PoiDatabase?,
    userLocation: Pair<Double, Double>?,
    timetableDb: SQLiteDatabase? = null,
    onSelectCrossing: (LevelCrossingItem) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0: 路線別制覇, 1: 周辺踏切
    var searchQuery by remember { mutableStateOf("") }
    var crossingList by remember { mutableStateOf<List<LevelCrossingItem>>(emptyList()) }
    var conquestStats by remember { mutableStateOf<OverallConquestStats?>(null) }
    var expandedLineName by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    suspend fun loadData() {
        val poiDb = poiDatabase?.db ?: return
        withContext(Dispatchers.IO) {
            conquestStats = CrossingRepository.getConquestStats(poiDb, timetableDb, context)
            crossingList = if (searchQuery.isNotBlank()) {
                CrossingRepository.searchCrossings(
                    db = poiDb,
                    query = searchQuery,
                    userLat = userLocation?.first,
                    userLon = userLocation?.second,
                    context = context,
                    timetableDb = timetableDb
                )
            } else if (userLocation != null) {
                CrossingRepository.getNearbyCrossings(
                    db = poiDb,
                    userLat = userLocation.first,
                    userLon = userLocation.second,
                    context = context,
                    timetableDb = timetableDb
                )
            } else {
                CrossingRepository.searchCrossings(
                    db = poiDb,
                    query = "",
                    userLat = null,
                    userLon = null,
                    context = context,
                    timetableDb = timetableDb
                )
            }
        }
    }

    LaunchedEffect(searchQuery, userLocation, refreshTrigger) {
        isLoading = true
        loadData()
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🚥", fontSize = 22.sp)
                    Text("踏切ロケハン & 路線制覇", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "閉じる")
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // タブ切り替え（路線別制覇 / 周辺踏切）
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("🏆 路線別制覇", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("📍 周辺踏切", fontWeight = FontWeight.Bold) }
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (selectedTab == 0) {
                    // --- タブ 0: 路線別制覇 ---
                    val stats = conquestStats
                    if (stats != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 440.dp)
                        ) {
                            // 全体制覇率カード
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "関東踏切 総合制覇率",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            text = "${stats.visitedCount} / ${stats.totalCount} 箇所 (${stats.progressPercentText})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { stats.progressRatio },
                                        modifier = Modifier.fillMaxWidth().height(8.dp),
                                        color = Color(0xFF2E7D32),
                                        trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "全 ${stats.lineCount} 路線中、踏切を訪問したらチェックをつけて制覇を目指そう！",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            // 路線別リスト
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(stats.lineStats, key = { it.lineName }) { lineStat ->
                                    val isExpanded = expandedLineName == lineStat.lineName
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        expandedLineName = if (isExpanded) null else lineStat.lineName
                                                    },
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Text("🚃", fontSize = 16.sp)
                                                        Text(
                                                            text = lineStat.lineName,
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                    Spacer(Modifier.height(4.dp))
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        LinearProgressIndicator(
                                                            progress = { lineStat.progressRatio },
                                                            modifier = Modifier.weight(1f).height(6.dp),
                                                            color = if (lineStat.progressRatio >= 1.0f) Color(0xFFFFB300) else Color(0xFF2E7D32),
                                                            trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                                        )
                                                        Text(
                                                            text = "${lineStat.visitedCount}/${lineStat.totalCount} (${lineStat.progressPercentText})",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = if (lineStat.visitedCount > 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }

                                                IconButton(
                                                    onClick = {
                                                        expandedLineName = if (isExpanded) null else lineStat.lineName
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Text(if (isExpanded) "▲" else "▼", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                                }
                                            }

                                            // 展開された路線の踏切一覧
                                            AnimatedVisibility(visible = isExpanded) {
                                                Column(
                                                    modifier = Modifier.padding(top = 8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                                    for (crossing in lineStat.crossings) {
                                                        val visited = crossing.memo?.visited == true
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clickable { onSelectCrossing(crossing) }
                                                                .padding(vertical = 4.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                                modifier = Modifier.weight(1f)
                                                            ) {
                                                                // 訪問トグルボタン
                                                                IconButton(
                                                                    onClick = {
                                                                        CrossingRepository.toggleVisited(context, crossing.id)
                                                                        refreshTrigger++
                                                                    },
                                                                    modifier = Modifier.size(28.dp)
                                                                ) {
                                                                    Text(if (visited) "✅" else "⬜", fontSize = 16.sp)
                                                                }
                                                                Column {
                                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                                        Text(
                                                                            text = crossing.name,
                                                                            style = MaterialTheme.typography.bodyMedium,
                                                                            fontWeight = if (visited) FontWeight.Bold else FontWeight.Normal,
                                                                            color = if (visited) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                                                                        )
                                                                        if (crossing.memo?.isFavorite == true) {
                                                                            Text("★", color = Color(0xFFFFB300), fontSize = 12.sp)
                                                                        }
                                                                    }
                                                                    if (visited && crossing.memo?.visitedDate?.isNotBlank() == true) {
                                                                        Text(
                                                                            text = "訪問: ${crossing.memo.visitedDate}",
                                                                            fontSize = 10.sp,
                                                                            color = Color(0xFF388E3C)
                                                                        )
                                                                    }
                                                                }
                                                            }

                                                            Button(
                                                                onClick = { onSelectCrossing(crossing) },
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                                modifier = Modifier.height(28.dp)
                                                            ) {
                                                                Text("地図へ", fontSize = 11.sp)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // --- タブ 1: 周辺踏切 & 検索 ---
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("踏切名で検索 (例: 中里, 王子)") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        if (crossingList.isEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                                Text("該当する踏切が見つかりませんでした", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(crossingList, key = { it.id }) { item ->
                                    val visited = item.memo?.visited == true
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onSelectCrossing(item) },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (visited) Color(0xFFF1F8E9) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    CrossingRepository.toggleVisited(context, item.id)
                                                    refreshTrigger++
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Text(if (visited) "✅" else "⬜", fontSize = 18.sp)
                                            }

                                            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(
                                                        item.name,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (visited) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (item.memo?.isFavorite == true) {
                                                        Icon(
                                                            Icons.Default.Star,
                                                            contentDescription = "お気に入り",
                                                            tint = Color(0xFFFFB300),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }

                                                Text(
                                                    "🚃 ${item.railwayLine}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )

                                                item.distanceMeters?.let { dist ->
                                                    Text(
                                                        "現在地から ${formatDistance(dist)}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.secondary
                                                    )
                                                }

                                                item.sunPosition?.let { sun ->
                                                    Text(
                                                        "☀️ 太陽: ${sun.azimuthDirectionName} (${"%.0f".format(sun.azimuthDegrees)}°) · ${sun.lightCondition}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }

                                            Button(
                                                onClick = { onSelectCrossing(item) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Text("選択", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}
