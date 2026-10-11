package com.example.offlinetransitmap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 太陽光線コンパスUI
 * 太陽の方位角・高度を直感的な円形コンパスで可視化
 */
@Composable
fun SunCompassGauge(
    sunPosition: SunPosition,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 円形コンパス
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center
            ) {
                val primaryColor = MaterialTheme.colorScheme.primary
                val sunColor = Color(0xFFFFA000) // 鮮やかなアンバー・サンカラー
                val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                val isDaytime = sunPosition.altitudeDegrees > 0

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = center
                    val r = size.minDimension / 2f - 4.dp.toPx()

                    // コンパス円周
                    drawCircle(color = trackColor, radius = r, center = c, style = Stroke(width = 2.dp.toPx()))

                    // 北マーク（上）
                    drawLine(
                        color = primaryColor,
                        start = Offset(c.x, c.y - r),
                        end = Offset(c.x, c.y - r + 6.dp.toPx()),
                        strokeWidth = 2.dp.toPx()
                    )

                    // 太陽の方位 (0°=上, 90°=右, 180°=下, 270°=左)
                    val azRad = Math.toRadians(sunPosition.azimuthDegrees - 90.0)
                    val sunX = c.x + r * cos(azRad).toFloat()
                    val sunY = c.y + r * sin(azRad).toFloat()

                    // 中心から太陽への光線ライン
                    drawLine(
                        color = if (isDaytime) sunColor.copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.3f),
                        start = c,
                        end = Offset(sunX, sunY),
                        strokeWidth = 1.5.dp.toPx()
                    )

                    // 太陽アイコン位置の丸
                    drawCircle(
                        color = if (isDaytime) sunColor else Color(0xFF5C6BC0),
                        radius = 6.dp.toPx(),
                        center = Offset(sunX, sunY)
                    )

                    // 中心点
                    drawCircle(color = primaryColor, radius = 3.dp.toPx(), center = c)
                }

                Text(
                    text = "N",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp)
                )
            }

            // 太陽・光線情報のテキスト
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "☀️ 太陽: ${sunPosition.azimuthDirectionName} (${"%.0f".format(sunPosition.azimuthDegrees)}°)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "高度 ${"%.0f".format(sunPosition.altitudeDegrees)}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (sunPosition.altitudeDegrees > 0) Color(0xFFE65100) else Color.Gray,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sunPosition.lightCondition,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sunPosition.photoAdvice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 踏切撮影アシスト詳細カード
 */
@Composable
fun CrossingDetailCard(
    crossing: LevelCrossingItem,
    onStartNavWalk: () -> Unit,
    onStartNavBike: () -> Unit,
    onSaveMemo: (CrossingMemo) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMemoEditDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // ヘッダー（タイトル・お気に入り・閉じる）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFFC107).copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🚥", fontSize = 20.sp)
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = crossing.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (crossing.memo?.isFavorite == true) {
                                Text("★", color = Color(0xFFFFB300), fontSize = 18.sp)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "🚃 ${crossing.railwayLine}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (crossing.detail.isNotBlank() && crossing.detail != "施設") {
                                Text(
                                    text = crossing.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Row {
                    IconButton(
                        onClick = {
                            val current = crossing.memo ?: CrossingMemo()
                            onSaveMemo(current.copy(isFavorite = !current.isFavorite))
                        }
                    ) {
                        Text(
                            text = if (crossing.memo?.isFavorite == true) "★" else "☆",
                            fontSize = 22.sp,
                            color = if (crossing.memo?.isFavorite == true) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "閉じる")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 訪問チェックインバー
            val isVisited = crossing.memo?.visited == true
            Surface(
                onClick = {
                    val current = crossing.memo ?: CrossingMemo()
                    val newVisited = !current.visited
                    val today = if (newVisited) java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd")) else ""
                    onSaveMemo(current.copy(visited = newVisited, visitedDate = today))
                },
                shape = RoundedCornerShape(10.dp),
                color = if (isVisited) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, if (isVisited) Color(0xFF4CAF50) else Color(0x33888888)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (isVisited) "✅" else "⬜", fontSize = 18.sp)
                        Column {
                            Text(
                                text = if (isVisited) "訪問制覇済み！" else "この踏切を訪問したらチェック",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isVisited) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                            )
                            if (isVisited && crossing.memo?.visitedDate?.isNotBlank() == true) {
                                Text(
                                    text = "訪問日: ${crossing.memo.visitedDate}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF388E3C)
                                )
                            }
                        }
                    }
                    Text(
                        text = if (isVisited) "チェック解除" else "チェックをつける",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isVisited) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 太陽光線ゲージ
            crossing.sunPosition?.let { sun ->
                SunCompassGauge(sunPosition = sun, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
            }

            // ユーザー撮影メモ欄
            val memo = crossing.memo
            val hasMemo = memo != null && (memo.recommendedLens.isNotBlank() || memo.bestLightingTime.isNotBlank() || memo.notes.isNotBlank())

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("📷", fontSize = 16.sp)
                            Text(
                                text = "撮影メモ・ロケハン情報",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        TextButton(
                            onClick = { showMemoEditDialog = true },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(if (hasMemo) "編集" else "メモ追加", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    if (hasMemo) {
                        if (memo.recommendedLens.isNotBlank()) {
                            Text("🔭 推奨レンズ: ${memo.recommendedLens}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (memo.bestLightingTime.isNotBlank()) {
                            Text("⏰ 順光時間帯: ${memo.bestLightingTime}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (memo.notes.isNotBlank()) {
                            Text("📝 ${memo.notes}", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(
                            "タップして推奨レンズや順光時間帯などのロケハンメモを残せます",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ナビゲーション開始ボタン
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartNavWalk,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🚶", fontSize = 16.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("徒歩でナビ")
                }
                Button(
                    onClick = onStartNavBike,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("🚲", fontSize = 16.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("自転車でナビ")
                }
            }
        }
    }

    if (showMemoEditDialog) {
        val currentMemo = crossing.memo ?: CrossingMemo()
        var lens by remember { mutableStateOf(currentMemo.recommendedLens) }
        var lighting by remember { mutableStateOf(currentMemo.bestLightingTime) }
        var notes by remember { mutableStateOf(currentMemo.notes) }

        AlertDialog(
            onDismissRequest = { showMemoEditDialog = false },
            title = { Text("${crossing.name}の撮影メモ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = lens,
                        onValueChange = { lens = it },
                        label = { Text("推奨レンズ（焦点距離）") },
                        placeholder = { Text("例: 望遠 70-200mm, 50mm標準") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = lighting,
                        onValueChange = { lighting = it },
                        label = { Text("順光時間帯") },
                        placeholder = { Text("例: 午前順光, 午後遅め, 夕景") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("撮影地の特徴・メモ") },
                        placeholder = { Text("例: フェンス低め、上り列車抜け良し") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveMemo(
                            currentMemo.copy(
                                recommendedLens = lens.trim(),
                                bestLightingTime = lighting.trim(),
                                notes = notes.trim()
                            )
                        )
                        showMemoEditDialog = false
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMemoEditDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}
