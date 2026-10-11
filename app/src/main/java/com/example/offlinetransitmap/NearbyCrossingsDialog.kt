package com.example.offlinetransitmap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
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
 * 周辺踏切スポット一覧・ロケハンダイアログ
 */
@Composable
fun NearbyCrossingsDialog(
    poiDatabase: PoiDatabase?,
    userLocation: Pair<Double, Double>?,
    onSelectCrossing: (LevelCrossingItem) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var crossingList by remember { mutableStateOf<List<LevelCrossingItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(searchQuery, userLocation) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val db = poiDatabase?.db
            if (db != null) {
                crossingList = if (searchQuery.isNotBlank()) {
                    CrossingRepository.searchCrossings(
                        db = db,
                        query = searchQuery,
                        userLat = userLocation?.first,
                        userLon = userLocation?.second,
                        context = context
                    )
                } else if (userLocation != null) {
                    CrossingRepository.getNearbyCrossings(
                        db = db,
                        userLat = userLocation.first,
                        userLon = userLocation.second,
                        context = context
                    )
                } else {
                    CrossingRepository.searchCrossings(
                        db = db,
                        query = "",
                        userLat = null,
                        userLon = null,
                        context = context
                    )
                }
            }
        }
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
                    Text("踏切スポット・ロケハン一覧", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "閉じる")
                }
            }
        },
        text = {
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

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (crossingList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                        Text("該当する踏切が見つかりませんでした", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(crossingList, key = { it.id }) { item ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectCrossing(item) },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                item.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Bold
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

                                        item.distanceMeters?.let { dist ->
                                            Text(
                                                "現在地から ${formatDistance(dist)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        item.sunPosition?.let { sun ->
                                            Text(
                                                "☀️ 太陽: ${sun.azimuthDirectionName} (${"%.0f".format(sun.azimuthDegrees)}°) · ${sun.lightCondition}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        val memo = item.memo
                                        if (memo != null && (memo.recommendedLens.isNotBlank() || memo.bestLightingTime.isNotBlank())) {
                                            val memoSummary = listOfNotNull(
                                                memo.recommendedLens.takeIf { it.isNotBlank() }?.let { "レンズ: $it" },
                                                memo.bestLightingTime.takeIf { it.isNotBlank() }?.let { "順光: $it" }
                                            ).joinToString(" | ")
                                            Text(
                                                "📸 $memoSummary",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.tertiary
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
        },
        confirmButton = {}
    )
}
