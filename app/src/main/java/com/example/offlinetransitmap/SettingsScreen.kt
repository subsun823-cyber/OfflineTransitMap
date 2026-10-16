package com.example.offlinetransitmap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues

data class PrefectureDownloadState(
    val info: PrefectureMapInfo,
    val isMapDownloaded: Boolean,
    val mapSizeBytes: Long,
    val isRoadDownloaded: Boolean,
    val roadSizeBytes: Long
)

@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    onChange: (AppPreferences) -> Unit,
    dataNote: String = "",
    onMapUpdated: () -> Unit = {},
    onStartDemoNavigation: ((Itinerary) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var savedData by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var prefectureStates by remember { mutableStateOf<List<PrefectureDownloadState>>(emptyList()) }
    var downloadingPrefectureId by remember { mutableStateOf<String?>(null) }
    var downloadingRoadPrefectureId by remember { mutableStateOf<String?>(null) }
    var mapDownloadPercent by remember { mutableStateOf(0) }
    var roadDownloadPercent by remember { mutableStateOf(0) }
    var mapActionMessage by remember { mutableStateOf<String?>(null) }
    var roadActionMessage by remember { mutableStateOf<String?>(null) }

    var urlDialogPrefecture by remember { mutableStateOf<PrefectureMapInfo?>(null) }
    var roadUrlDialogPrefecture by remember { mutableStateOf<PrefectureMapInfo?>(null) }
    var inputCustomUrl by remember { mutableStateOf("") }
    var inputCustomRoadUrl by remember { mutableStateOf("") }
    var showBaseUrlDialog by remember { mutableStateOf(false) }
    var inputBaseUrl by remember { mutableStateOf("") }
    var importTargetPrefecture by remember { mutableStateOf<PrefectureMapInfo?>(null) }
    var importRoadTargetPrefecture by remember { mutableStateOf<PrefectureMapInfo?>(null) }

    // アプリ本体のGitHubアップデート管理用
    var appUpdateCheckStatus by remember { mutableStateOf<String?>(null) }
    var availableAppRelease by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
    var isCheckingAppUpdate by remember { mutableStateOf(false) }
    var isDownloadingAppUpdate by remember { mutableStateOf(false) }
    var appUpdateProgressPercent by remember { mutableStateOf(0) }

    suspend fun refreshPrefectureStates() {
        prefectureStates = withContext(Dispatchers.IO) {
            KantoPrefectures.nationalList.map { pref ->
                val mapFile = KantoPrefectures.getFile(context, pref)
                val mapDownloaded = KantoPrefectures.isDownloaded(context, pref)
                val roadFile = KantoPrefectures.getRoadFile(context, pref)
                val roadDownloaded = KantoPrefectures.isRoadDownloaded(context, pref)
                PrefectureDownloadState(
                    info = pref,
                    isMapDownloaded = mapDownloaded,
                    mapSizeBytes = if (mapDownloaded) mapFile.length() else 0L,
                    isRoadDownloaded = roadDownloaded,
                    roadSizeBytes = if (roadDownloaded) roadFile.length() else 0L
                )
            }
        }
    }

    suspend fun refreshSavedData() {
        savedData = withContext(Dispatchers.IO) {
            val mapDownloaded = KantoPrefectures.getDownloadedList(context)
            val mapStatus = if (mapDownloaded.isNotEmpty()) {
                val totalBytes = mapDownloaded.sumOf { KantoPrefectures.getFile(context, it).length() }
                "保存済み: ${mapDownloaded.joinToString("、") { it.name }}（%.1f MB）".format(totalBytes / 1024.0 / 1024.0)
            } else "未保存"

            val roadDownloaded = KantoPrefectures.getRoadDownloadedList(context)
            val roadStatus = if (roadDownloaded.isNotEmpty()) {
                val totalBytes = RoadDownloadManager.getTotalRoadSizeBytes(context)
                "保存済み: ${roadDownloaded.joinToString("、") { it.name }}（%.1f MB）".format(totalBytes / 1024.0 / 1024.0)
            } else "未保存（同梱データのみ）"

            val timetableFile = context.getExternalFilesDir("timetable")?.let { File(it, "timetable.db") }
            val timetableStatus = if (timetableFile != null && timetableFile.isFile && timetableFile.length() > 0) {
                "保存済み（%.1f MB）".format(timetableFile.length() / 1024.0 / 1024.0)
            } else "未保存"

            listOf(
                "地図" to mapStatus,
                "道路データ（ナビ）" to roadStatus,
                "時刻表" to timetableStatus
            )
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val pref = importTargetPrefecture
        importTargetPrefecture = null
        if (uri != null && pref != null) {
            downloadingPrefectureId = pref.id
            mapDownloadPercent = 0
            mapActionMessage = "${pref.name}の地図をインポート中…"
            scope.launch {
                val result = MapDownloadManager.importMapFile(context, pref, uri) { p ->
                    mapDownloadPercent = p
                }
                when (result) {
                    is MapDownloadResult.Success -> {
                        mapActionMessage = "${pref.name}の地図をインポートしました"
                        refreshPrefectureStates()
                        refreshSavedData()
                        onMapUpdated()
                    }
                    is MapDownloadResult.Failure -> {
                        mapActionMessage = "${pref.name}のインポートに失敗しました: ${result.message}"
                    }
                }
                downloadingPrefectureId = null
            }
        }
    }

    val roadFilePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val pref = importRoadTargetPrefecture
        importRoadTargetPrefecture = null
        if (uri != null && pref != null) {
            downloadingRoadPrefectureId = pref.id
            roadDownloadPercent = 0
            roadActionMessage = "${pref.name}の道路データをインポート中…"
            scope.launch {
                val result = RoadDownloadManager.importRoadFile(context, pref, uri) { p ->
                    roadDownloadPercent = p
                }
                when (result) {
                    is RoadDownloadResult.Success -> {
                        roadActionMessage = "${pref.name}の道路データをインポートしました"
                        refreshPrefectureStates()
                        refreshSavedData()
                    }
                    is RoadDownloadResult.Failure -> {
                        roadActionMessage = "${pref.name}の道路インポートに失敗しました: ${result.message}"
                    }
                }
                downloadingRoadPrefectureId = null
            }
        }
    }

    var updateStatusMessage by remember { mutableStateOf<String?>(null) }
    var availableUpdate by remember { mutableStateOf<TransitManifest?>(null) }
    var isCheckingOrUpdating by remember { mutableStateOf(false) }
    var updateProgressPercent by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        refreshPrefectureStates()
        refreshSavedData()
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsGroup("表示テーマ") {
                    Column(Modifier.selectableGroup()) {
                        for (mode in ThemeMode.entries) {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .selectable(preferences.theme == mode, role = Role.RadioButton) {
                                        onChange(preferences.copy(theme = mode))
                                    }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = preferences.theme == mode, onClick = null)
                                Text(mode.label, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                    SettingNote("画面と保存済み地図の配色に反映します。")
                }
            }
            item {
                SettingsGroup("地図の表示") {
                    SettingSwitch("施設名を表示", "飲食店や公園などの名前", preferences.showPoiNames) {
                        onChange(preferences.copy(showPoiNames = it))
                    }
                    HorizontalDivider()
                    SettingSwitch("施設マークを表示", "施設のアイコンと点", preferences.showPoiIcons) {
                        onChange(preferences.copy(showPoiIcons = it))
                    }
                    SettingNote("駅・バス停と、選択中の経路は引き続き表示します。")
                }
            }
            item {
                var showLcdPreviewDialog by remember { mutableStateOf(false) }
                SettingsGroup("ナビゲーション・車内LCDモニター") {
                    SettingSwitch(
                        "ナビ中は画面を消さない", "アプリを表示している間に適用します。電池の消費が増えます。",
                        preferences.keepScreenOnDuringNavigation
                    ) { onChange(preferences.copy(keepScreenOnDuringNavigation = it)) }

                    HorizontalDivider()
                    SettingSwitch(
                        "車内LCDテストモード（現地に行かずに確認）",
                        "ナビ画面で駅から離れていても車内LCD（電車・バス）を強制表示し、手動・自動デモで『この電車は』『次は』『まもなく』『ただいま』や停車駅を自由に切り替えられるようにします。",
                        preferences.lcdTestMode
                    ) { enabled ->
                        if (!enabled) LcdTestController.reset()
                        onChange(preferences.copy(lcdTestMode = enabled))
                    }

                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { showLcdPreviewDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🖥️ 車内LCDモニター（電車・バス）テスト画面を開く")
                    }
                    SettingNote("ボタンを押すと、この場で直ちにJR埼京線（参照動画再現）・中央特快・山手線・京王線・西東京バスの車内LCD表示と各フェーズを確認できます。")
                }

                if (showLcdPreviewDialog) {
                    LcdTestPreviewDialog(
                        onDismiss = { showLcdPreviewDialog = false },
                        onStartDemoNavigation = onStartDemoNavigation?.let { startNav ->
                            { demoItin ->
                                showLcdPreviewDialog = false
                                onChange(preferences.copy(lcdTestMode = true))
                                startNav(demoItin)
                            }
                        }
                    )
                }
            }
            item {
                var showCrossingSpotsDialog by remember { mutableStateOf(false) }
                SettingsGroup("趣味モード") {
                    SettingSwitch(
                        "踏切撮影アシスト", "踏切巡りや鉄道写真のロケハン・撮影に役立つ専用機能を有効にします",
                        preferences.hobbyCrossingMode
                    ) { onChange(preferences.copy(hobbyCrossingMode = it)) }

                    if (preferences.hobbyCrossingMode) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        SettingSwitch(
                            "地図上で踏切を強調表示", "広域ズームでも踏切アイコンを目立たせてロケハンを容易にします",
                            preferences.hobbyHighlightCrossings
                        ) { onChange(preferences.copy(hobbyHighlightCrossings = it)) }

                        HorizontalDivider()
                        SettingSwitch(
                            "太陽光線（順光・逆光）ガイド", "現在時刻の太陽方位・仰角をリアルタイム計算し、光線状態を案内します",
                            preferences.hobbySunGuide
                        ) { onChange(preferences.copy(hobbySunGuide = it)) }

                        HorizontalDivider()
                        SettingSwitch(
                            "踏切接近アラート", "移動中やナビ中に踏切（150m以内）に近づいた際に通知します",
                            preferences.hobbyCrossingAlert
                        ) { onChange(preferences.copy(hobbyCrossingAlert = it)) }

                        HorizontalDivider()
                        SettingSwitch(
                            "撮影構図ファインダー枠", "画面に一眼レフ（3:2）フレームと三分割構図グリッドを表示します",
                            preferences.hobbyCompositionGrid
                        ) { onChange(preferences.copy(hobbyCompositionGrid = it)) }

                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showCrossingSpotsDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🏆 路線別踏切制覇 & ロケハン一覧を開く")
                        }
                    } else {
                        SettingNote("有効にすると、地図上の踏切強調・太陽光線コンパス・接近アラート・構図枠・路線別制覇率などが利用できます。")
                    }
                }

                if (showCrossingSpotsDialog) {
                    val poiDb = remember { PoiDatabase.open(context) }
                    val timetable = remember { TimetableDb.open(context) }
                    val curLoc = remember { lastKnownLatLon(context) }
                    NearbyCrossingsDialog(
                        poiDatabase = poiDb,
                        userLocation = curLoc,
                        timetableDb = timetable?.db,
                        onSelectCrossing = {
                            showCrossingSpotsDialog = false
                            onMapUpdated()
                        },
                        onDismiss = { showCrossingSpotsDialog = false }
                    )
                }
            }
            item {
                var selectedRegionFilter by remember { mutableStateOf("関東") }
                SettingsGroup("オフライン地図・道路データ（ナビ）") {
                    val mapDownloadedCount = prefectureStates.count { it.isMapDownloaded }
                    val roadDownloadedCount = prefectureStates.count { it.isRoadDownloaded }
                    val mapTotalMb = prefectureStates.filter { it.isMapDownloaded }.sumOf { it.mapSizeBytes } / 1024.0 / 1024.0
                    val roadTotalMb = RoadDownloadManager.getTotalRoadSizeBytes(context) / 1024.0 / 1024.0

                    Text(
                        "地図保存: $mapDownloadedCount 都県（%.1f MB） / 道路保存: $roadDownloadedCount 都県（%.1f MB）".format(mapTotalMb, roadTotalMb),
                        style = MaterialTheme.typography.titleSmall
                    )
                    SettingNote("オフラインで使用する地図（PMTiles）とナビ用道路データ（OSM）を都県ごとに管理できます。越境ナビ時はダウンロード済みの複数都県を自動で連結して案内します。")
                    Spacer(Modifier.height(8.dp))

                    // 地域絞り込みボタン (横スクロール対応)
                    val regionFilters = listOf("関東", "関西", "中部・東海", "東北・北海道", "中国・四国", "九州・沖縄", "すべて")
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        regionFilters.forEach { regionFilter ->
                            val isSelected = selectedRegionFilter == regionFilter
                            if (isSelected) {
                                Button(
                                    onClick = { selectedRegionFilter = regionFilter },
                                    modifier = Modifier.height(34.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Text(regionFilter, style = MaterialTheme.typography.labelMedium)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { selectedRegionFilter = regionFilter },
                                    modifier = Modifier.height(34.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Text(regionFilter, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()

                    val filteredStates = prefectureStates.filter {
                        when (selectedRegionFilter) {
                            "関東" -> it.info.region == "関東"
                            "関西" -> it.info.region == "関西"
                            "中部・東海" -> it.info.region in listOf("甲信越", "北陸", "東海")
                            "東北・北海道" -> it.info.region in listOf("北海道", "東北")
                            "中国・四国" -> it.info.region in listOf("中国", "四国")
                            "九州・沖縄" -> it.info.region in listOf("九州", "沖縄")
                            else -> true
                        }
                    }

                    for (state in filteredStates) {
                        val pref = state.info
                        val isMapDownloading = downloadingPrefectureId == pref.id
                        val isRoadDownloading = downloadingRoadPrefectureId == pref.id

                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${pref.name} (${pref.region})",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            Spacer(Modifier.height(4.dp))

                            // 1. 地図（PMTiles）行
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    val statusText = when {
                                        isMapDownloading -> "地図取得中… ($mapDownloadPercent%)"
                                        state.isMapDownloaded -> "地図: 保存済み（%.1f MB）".format(state.mapSizeBytes / 1024.0 / 1024.0)
                                        pref.id == "tokyo" -> "地図: 未保存（同梱より即座に復元可）"
                                        else -> "地図: 未保存（約${pref.approximateSizeMb} MB）"
                                    }
                                    SettingNote(statusText)
                                    if (isMapDownloading) {
                                        Spacer(Modifier.height(2.dp))
                                        LinearProgressIndicator(
                                            progress = { mapDownloadPercent / 100f },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (state.isMapDownloaded) {
                                        OutlinedButton(
                                            onClick = {
                                                if (MapDownloadManager.deletePrefecture(context, pref)) {
                                                    mapActionMessage = "${pref.name}の地図を削除しました"
                                                    scope.launch {
                                                        refreshPrefectureStates()
                                                        refreshSavedData()
                                                        onMapUpdated()
                                                    }
                                                }
                                            },
                                            enabled = !isMapDownloading,
                                            modifier = Modifier.height(32.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text("地図削除", style = MaterialTheme.typography.labelSmall)
                                        }
                                    } else {
                                        Button(
                                            onClick = {
                                                downloadingPrefectureId = pref.id
                                                mapDownloadPercent = 0
                                                val customUrl = if (preferences.customMapBaseUrl.isNotBlank()) {
                                                    KantoPrefectures.getDownloadUrl(pref, preferences.customMapBaseUrl)
                                                } else null
                                                mapActionMessage = "${pref.name}の地図を取得中…"
                                                scope.launch {
                                                    val result = MapDownloadManager.downloadPrefecture(context, pref, customUrl) { p ->
                                                        mapDownloadPercent = p
                                                    }
                                                    when (result) {
                                                        is MapDownloadResult.Success -> {
                                                            mapActionMessage = "${pref.name}の地図を取得しました"
                                                            refreshPrefectureStates()
                                                            refreshSavedData()
                                                            onMapUpdated()
                                                        }
                                                        is MapDownloadResult.Failure -> {
                                                            mapActionMessage = "${pref.name}の地図取得失敗: ${result.message}"
                                                        }
                                                    }
                                                    downloadingPrefectureId = null
                                                }
                                            },
                                            enabled = !isMapDownloading,
                                            modifier = Modifier.height(32.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text(if (isMapDownloading) "取得中…" else "地図取得", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }

                            // 2. 道路データ（OSMナビ）行
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    val roadText = when {
                                        isRoadDownloading -> "道路データ取得中… ($roadDownloadPercent%)"
                                        state.isRoadDownloaded -> "道路: 保存済み（%.1f MB）".format(state.roadSizeBytes / 1024.0 / 1024.0)
                                        pref.id == "tokyo" -> "道路: 準備済み（アプリ内同梱）"
                                        else -> "道路: 未保存（約${pref.roadApproximateSizeMb} MB）"
                                    }
                                    SettingNote(roadText)
                                    if (isRoadDownloading) {
                                        Spacer(Modifier.height(2.dp))
                                        LinearProgressIndicator(
                                            progress = { roadDownloadPercent / 100f },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (state.isRoadDownloaded) {
                                        OutlinedButton(
                                            onClick = {
                                                if (RoadDownloadManager.deleteRoad(context, pref)) {
                                                    roadActionMessage = "${pref.name}の道路データを削除しました"
                                                    scope.launch {
                                                        refreshPrefectureStates()
                                                        refreshSavedData()
                                                    }
                                                }
                                            },
                                            enabled = !isRoadDownloading,
                                            modifier = Modifier.height(32.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text("道路削除", style = MaterialTheme.typography.labelSmall)
                                        }
                                    } else {
                                        Button(
                                            onClick = {
                                                downloadingRoadPrefectureId = pref.id
                                                roadDownloadPercent = 0
                                                val customUrl = if (preferences.customMapBaseUrl.isNotBlank()) {
                                                    KantoPrefectures.getRoadDownloadUrl(pref, preferences.customMapBaseUrl)
                                                } else null
                                                roadActionMessage = "${pref.name}の道路データを取得中…"
                                                scope.launch {
                                                    val result = RoadDownloadManager.downloadRoad(context, pref, customUrl) { p ->
                                                        roadDownloadPercent = p
                                                    }
                                                    when (result) {
                                                        is RoadDownloadResult.Success -> {
                                                            roadActionMessage = "${pref.name}の道路データを取得しました"
                                                            refreshPrefectureStates()
                                                            refreshSavedData()
                                                        }
                                                        is RoadDownloadResult.Failure -> {
                                                            roadActionMessage = "${pref.name}の道路データ取得失敗: ${result.message}"
                                                        }
                                                    }
                                                    downloadingRoadPrefectureId = null
                                                }
                                            },
                                            enabled = !isRoadDownloading,
                                            modifier = Modifier.height(32.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text(if (isRoadDownloading) "取得中…" else "道路取得", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }

                            // 補助操作（URL指定・ファイル選択）
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    onClick = {
                                        urlDialogPrefecture = pref
                                        inputCustomUrl = KantoPrefectures.getDownloadUrl(pref, preferences.customMapBaseUrl)
                                    },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Text("地図URL", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(
                                    onClick = {
                                        importTargetPrefecture = pref
                                        filePickerLauncher.launch("*/*")
                                    },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Text("地図ファイル選択", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(
                                    onClick = {
                                        roadUrlDialogPrefecture = pref
                                        inputCustomRoadUrl = KantoPrefectures.getRoadDownloadUrl(pref, preferences.customMapBaseUrl)
                                    },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Text("道路URL", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(
                                    onClick = {
                                        importRoadTargetPrefecture = pref
                                        roadFilePickerLauncher.launch("*/*")
                                    },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Text("道路ファイル選択", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
                        }
                    }

                    if (mapActionMessage != null || roadActionMessage != null) {
                        Spacer(Modifier.height(8.dp))
                        mapActionMessage?.let { SettingNote(it) }
                        roadActionMessage?.let { SettingNote(it) }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val activeBaseUrl = preferences.customMapBaseUrl.ifBlank { KantoPrefectures.DEFAULT_BASE_URL }
                        Column(modifier = Modifier.weight(1f)) {
                            SettingNote("配信元ベースURL: $activeBaseUrl")
                        }
                        TextButton(
                            onClick = {
                                inputBaseUrl = preferences.customMapBaseUrl
                                showBaseUrlDialog = true
                            }
                        ) {
                            Text("URL変更")
                        }
                    }
                }
            }

            // アプリ本体のGitHubアップデート管理グループ
            item {
                SettingsGroup("アプリ本体の更新 (GitHub)") {
                    Text(
                        "現在のバージョン: v${AppUpdateManager.getCurrentVersionName(context)}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    SettingNote("GitHubリポジトリ（subsun823-cyber/OfflineTransitMap）の最新ReleaseからAPKを自動取得し、アプリ単体でアップデートします。")
                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isCheckingAppUpdate = true
                                    appUpdateCheckStatus = "GitHubの最新リリースを確認中…"
                                    when (val result = AppUpdateManager.checkUpdate(context)) {
                                        is AppUpdateCheckResult.UpdateAvailable -> {
                                            availableAppRelease = result.info
                                            appUpdateCheckStatus = "新しいバージョン（${result.info.tagName}）が見つかりました！"
                                        }
                                        is AppUpdateCheckResult.UpToDate -> {
                                            availableAppRelease = null
                                            appUpdateCheckStatus = "お使いのアプリは最新バージョンです（${result.currentVersion}）"
                                        }
                                        is AppUpdateCheckResult.Error -> {
                                            availableAppRelease = null
                                            appUpdateCheckStatus = "確認エラー: ${result.message}"
                                        }
                                    }
                                    isCheckingAppUpdate = false
                                }
                            },
                            enabled = !isCheckingAppUpdate && !isDownloadingAppUpdate
                        ) {
                            Text(if (isCheckingAppUpdate) "確認中…" else "最新版を確認")
                        }

                        availableAppRelease?.let { release ->
                            Button(
                                onClick = {
                                    scope.launch {
                                        isDownloadingAppUpdate = true
                                        appUpdateProgressPercent = 0
                                        appUpdateCheckStatus = "最新APK（${release.apkFileName}）をダウンロード中…"
                                        val dlResult = AppUpdateManager.downloadApk(context, release) { pct ->
                                            appUpdateProgressPercent = pct
                                        }
                                        when (dlResult) {
                                            is AppDownloadResult.Success -> {
                                                appUpdateCheckStatus = "ダウンロード完了。インストーラーを起動します…"
                                                try {
                                                    AppUpdateManager.installApk(context, dlResult.apkFile)
                                                } catch (e: Exception) {
                                                    appUpdateCheckStatus = "インストーラーの起動に失敗しました: ${e.localizedMessage ?: e.message}"
                                                }
                                            }
                                            is AppDownloadResult.Failure -> {
                                                appUpdateCheckStatus = "ダウンロード失敗: ${dlResult.message}"
                                            }
                                        }
                                        isDownloadingAppUpdate = false
                                    }
                                },
                                enabled = !isDownloadingAppUpdate
                            ) {
                                Text(if (isDownloadingAppUpdate) "ダウンロード中…" else "更新をインストール")
                            }
                        }
                    }

                    if (isDownloadingAppUpdate && appUpdateProgressPercent in 1..99) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { appUpdateProgressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    appUpdateCheckStatus?.let { status ->
                        Spacer(Modifier.height(8.dp))
                        SettingNote(status)
                    }

                    availableAppRelease?.let { release ->
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "✨ ${release.releaseTitle} (${release.tagName})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (release.apkSize > 0) {
                                    Text(
                                        "ファイルサイズ: %.1f MB".format(release.apkSize / 1024.0 / 1024.0),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (release.releaseNotes.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        release.releaseNotes,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SettingsGroup("オフラインデータ") {
                    if (savedData.isEmpty()) Text("確認中…")
                    for ((label, status) in savedData) {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                    }
                    SettingNote("端末に保存されたファイルの状態です。収録範囲や時刻表の有効期間は別途ご確認ください。")
                    if (dataNote.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        SettingNote(dataNote)
                    }
                }
            }
            item {
                SettingsGroup("時刻表データの自動更新") {
                    SettingSwitch(
                        "起動時に更新を確認", "新しい時刻表（GTFS）が公開されているか定期的に確認します",
                        preferences.autoCheckTransitUpdates
                    ) { onChange(preferences.copy(autoCheckTransitUpdates = it)) }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isCheckingOrUpdating = true
                                    updateStatusMessage = "最新の時刻表データを確認中…"
                                    when (val result = TransitUpdateManager.check(context, preferences.customManifestUrl)) {
                                        is UpdateCheckResult.UpToDate -> {
                                            updateStatusMessage = "時刻表データは最新です（${result.manifest.version}）"
                                            availableUpdate = null
                                        }
                                        is UpdateCheckResult.Available -> {
                                            val validText = result.manifest.file.validityText.ifBlank { "最新版" }
                                            updateStatusMessage = "新しい時刻表が見つかりました（${validText}、約${result.manifest.file.tripCount}便）"
                                            availableUpdate = result.manifest
                                        }
                                        is UpdateCheckResult.Error -> {
                                            updateStatusMessage = "確認に失敗しました: ${result.message}"
                                            availableUpdate = null
                                        }
                                    }
                                    isCheckingOrUpdating = false
                                }
                            },
                            enabled = !isCheckingOrUpdating
                        ) {
                            Text(if (isCheckingOrUpdating && availableUpdate == null) "確認中…" else "更新を確認")
                        }

                        if (availableUpdate != null) {
                            Button(
                                onClick = {
                                    val update = availableUpdate ?: return@Button
                                    scope.launch {
                                        isCheckingOrUpdating = true
                                        val res = TransitUpdateManager.downloadAndApply(context, update.file, update.version) { msg, percent ->
                                            updateStatusMessage = msg
                                            updateProgressPercent = percent
                                        }
                                        when (res) {
                                            is UpdateApplyResult.Success -> {
                                                updateStatusMessage = "時刻表データを最新版に更新しました（${res.version}）"
                                                availableUpdate = null
                                                refreshSavedData()
                                            }
                                            is UpdateApplyResult.Failure -> {
                                                updateStatusMessage = "更新に失敗しました: ${res.message}"
                                            }
                                        }
                                        isCheckingOrUpdating = false
                                    }
                                },
                                enabled = !isCheckingOrUpdating
                            ) {
                                Text(if (isCheckingOrUpdating) "適用中…" else "最新版を適用")
                            }
                        }
                    }

                    if (isCheckingOrUpdating && updateProgressPercent in 1..99) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { updateProgressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (updateStatusMessage != null) {
                        Spacer(Modifier.height(8.dp))
                        SettingNote(requireNotNull(updateStatusMessage))
                    }

                    Spacer(Modifier.height(8.dp))
                    val activeUrl = preferences.customManifestUrl.ifBlank { TransitUpdateManager.DEFAULT_MANIFEST_URL }
                    SettingNote("配信元URL: $activeUrl")
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }

        if (showBaseUrlDialog) {
            AlertDialog(
                onDismissRequest = { showBaseUrlDialog = false },
                title = { Text("地図・道路データの配信元ベースURL設定") },
                text = {
                    Column {
                        Text("全都県の地図・道路データファイルが配置されているベースURLを設定します。空欄にすると既定のURLに戻ります。")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = inputBaseUrl,
                            onValueChange = { inputBaseUrl = it },
                            label = { Text("ベースURL") },
                            placeholder = { Text(KantoPrefectures.DEFAULT_BASE_URL) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onChange(preferences.copy(customMapBaseUrl = inputBaseUrl.trim()))
                            showBaseUrlDialog = false
                        }
                    ) {
                        Text("保存")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBaseUrlDialog = false }) {
                        Text("キャンセル")
                    }
                }
            )
        }

        urlDialogPrefecture?.let { pref ->
            AlertDialog(
                onDismissRequest = { urlDialogPrefecture = null },
                title = { Text("${pref.name}の地図URL指定ダウンロード") },
                text = {
                    Column {
                        Text("PMTiles ファイル（${pref.fileName}）のダウンロードURLを入力してください。")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = inputCustomUrl,
                            onValueChange = { inputCustomUrl = it },
                            label = { Text("ダウンロードURL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val urlToDownload = inputCustomUrl.trim()
                            urlDialogPrefecture = null
                            if (urlToDownload.isNotBlank()) {
                                downloadingPrefectureId = pref.id
                                mapDownloadPercent = 0
                                mapActionMessage = "${pref.name}の地図をダウンロード中…"
                                scope.launch {
                                    val result = MapDownloadManager.downloadPrefecture(context, pref, urlToDownload) { p ->
                                        mapDownloadPercent = p
                                    }
                                    when (result) {
                                        is MapDownloadResult.Success -> {
                                            mapActionMessage = "${pref.name}の地図ダウンロード完了"
                                            refreshPrefectureStates()
                                            refreshSavedData()
                                            onMapUpdated()
                                        }
                                        is MapDownloadResult.Failure -> {
                                            mapActionMessage = "${pref.name}の地図ダウンロード失敗: ${result.message}"
                                        }
                                    }
                                    downloadingPrefectureId = null
                                }
                            }
                        }
                    ) {
                        Text("ダウンロード")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { urlDialogPrefecture = null }) {
                        Text("キャンセル")
                    }
                }
            )
        }

        roadUrlDialogPrefecture?.let { pref ->
            AlertDialog(
                onDismissRequest = { roadUrlDialogPrefecture = null },
                title = { Text("${pref.name}の道路データURL指定ダウンロード") },
                text = {
                    Column {
                        Text("SQLite 道路データファイル（${pref.roadFileName}）のダウンロードURLを入力してください。")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = inputCustomRoadUrl,
                            onValueChange = { inputCustomRoadUrl = it },
                            label = { Text("ダウンロードURL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val urlToDownload = inputCustomRoadUrl.trim()
                            roadUrlDialogPrefecture = null
                            if (urlToDownload.isNotBlank()) {
                                downloadingRoadPrefectureId = pref.id
                                roadDownloadPercent = 0
                                roadActionMessage = "${pref.name}の道路データをダウンロード中…"
                                scope.launch {
                                    val result = RoadDownloadManager.downloadRoad(context, pref, urlToDownload) { p ->
                                        roadDownloadPercent = p
                                    }
                                    when (result) {
                                        is RoadDownloadResult.Success -> {
                                            roadActionMessage = "${pref.name}の道路データダウンロード完了"
                                            refreshPrefectureStates()
                                            refreshSavedData()
                                        }
                                        is RoadDownloadResult.Failure -> {
                                            roadActionMessage = "${pref.name}の道路データダウンロード失敗: ${result.message}"
                                        }
                                    }
                                    downloadingRoadPrefectureId = null
                                }
                            }
                        }
                    ) {
                        Text("ダウンロード")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { roadUrlDialogPrefecture = null }) {
                        Text("キャンセル")
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SettingNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            SettingNote(detail)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
