package com.example.offlinetransitmap

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceSearchScreen(
    searcher: PlaceSearcher,
    currentLocation: Pair<Double, Double>?,
    initialQuery: String = "",
    onSelectPlace: (PlaceSearchResult) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    var query by remember { mutableStateOf(initialQuery) }
    var selectedCategory by remember { mutableStateOf<PlaceCategory?>(null) }
    var results by remember { mutableStateOf<List<PlaceSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(query, selectedCategory, currentLocation) {
        isSearching = true
        val searchResults = withContext(Dispatchers.Default) {
            searcher.search(
                query = query,
                currentLat = currentLocation?.first,
                currentLon = currentLocation?.second,
                categoryFilter = selectedCategory,
                limit = 40
            )
        }
        results = searchResults
        isSearching = false
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 検索バーヘッダー
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "戻る",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text(
                            "施設、場所、駅、バス停を検索",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "検索",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "クリア",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                )
            }

            // カテゴリフィルターチップ
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("すべて") }
                )
                FilterChip(
                    selected = selectedCategory == PlaceCategory.STATION,
                    onClick = {
                        selectedCategory = if (selectedCategory == PlaceCategory.STATION) null else PlaceCategory.STATION
                    },
                    label = { Text("🚉 駅") }
                )
                FilterChip(
                    selected = selectedCategory == PlaceCategory.BUS_STOP,
                    onClick = {
                        selectedCategory = if (selectedCategory == PlaceCategory.BUS_STOP) null else PlaceCategory.BUS_STOP
                    },
                    label = { Text("🚏 バス停") }
                )
                FilterChip(
                    selected = selectedCategory == PlaceCategory.LANDMARK,
                    onClick = {
                        selectedCategory = if (selectedCategory == PlaceCategory.LANDMARK) null else PlaceCategory.LANDMARK
                    },
                    label = { Text("🗼 名所・観光") }
                )
                FilterChip(
                    selected = selectedCategory == PlaceCategory.COMMERCIAL,
                    onClick = {
                        selectedCategory = if (selectedCategory == PlaceCategory.COMMERCIAL) null else PlaceCategory.COMMERCIAL
                    },
                    label = { Text("🛍️ 商業施設") }
                )
                FilterChip(
                    selected = selectedCategory == PlaceCategory.PARK,
                    onClick = {
                        selectedCategory = if (selectedCategory == PlaceCategory.PARK) null else PlaceCategory.PARK
                    },
                    label = { Text("🌳 公園") }
                )
                FilterChip(
                    selected = selectedCategory == PlaceCategory.LEISURE,
                    onClick = {
                        selectedCategory = if (selectedCategory == PlaceCategory.LEISURE) null else PlaceCategory.LEISURE
                    },
                    label = { Text("🎡 レジャー") }
                )
                FilterChip(
                    selected = selectedCategory == PlaceCategory.FACILITY,
                    onClick = {
                        selectedCategory = if (selectedCategory == PlaceCategory.FACILITY) null else PlaceCategory.FACILITY
                    },
                    label = { Text("🏛️ 公共・文化") }
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.8.dp
            )

            // 検索結果リスト
            if (isSearching && results.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 40.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            } else if (results.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (query.isBlank()) "施設名、駅名、バス停名を入力してください" else "該当する施設・場所・駅・バス停が見つかりませんでした",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(results, key = { it.place.id }) { item ->
                        PlaceItemRow(
                            item = item,
                            onClick = { onSelectPlace(item) }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceItemRow(
    item: PlaceSearchResult,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // アイコンバッジ
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = item.place.category.iconEmoji,
                fontSize = 20.sp
            )
        }

        Spacer(Modifier.width(14.dp))

        // 名称・カテゴリ詳細
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.place.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val subtitle = if (item.place.detail.isNotBlank()) {
                "${item.place.category.label} · ${item.place.detail}"
            } else {
                item.place.category.label
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 距離・所要時間（現在地からの情報がある場合）
        if (item.distanceText.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = item.distanceText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (item.durationText.isNotBlank()) {
                    Text(
                        text = item.durationText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
