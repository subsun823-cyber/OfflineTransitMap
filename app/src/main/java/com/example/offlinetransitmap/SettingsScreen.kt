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
    val isDownloaded: Boolean,
    val sizeBytes: Long
)

@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    onChange: (AppPreferences) -> Unit,
    dataNote: String = "",
    onMapUpdated: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var savedData by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var prefectureStates by remember { mutableStateOf<List<PrefectureDownloadState>>(emptyList()) }
    var downloadingPrefectureId by remember { mutableStateOf<String?>(null) }
    var mapDownloadPercent by remember { mutableStateOf(0) }
    var mapActionMessage by remember { mutableStateOf<String?>(null) }

    var urlDialogPrefecture by remember { mutableStateOf<PrefectureMapInfo?>(null) }
    var inputCustomUrl by remember { mutableStateOf("") }
    var showBaseUrlDialog by remember { mutableStateOf(false) }
    var inputBaseUrl by remember { mutableStateOf("") }
    var importTargetPrefecture by remember { mutableStateOf<PrefectureMapInfo?>(null) }

    suspend fun refreshPrefectureStates() {
        prefectureStates = withContext(Dispatchers.IO) {
            KantoPrefectures.all.map { pref ->
                val file = KantoPrefectures.getFile(context, pref)
                val downloaded = KantoPrefectures.isDownloaded(context, pref)
                PrefectureDownloadState(
                    info = pref,
                    isDownloaded = downloaded,
                    sizeBytes = if (downloaded) file.length() else 0L
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

            val timetableFile = context.getExternalFilesDir("timetable")?.let { File(it, "timetable.db") }
            val timetableStatus = if (timetableFile != null && timetableFile.isFile && timetableFile.length() > 0) {
                "保存済み（%.1f MB）".format(timetableFile.length() / 1024.0 / 1024.0)
            } else "未保存"

            listOf("地図" to mapStatus, "時刻表" to timetableStatus)
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
            mapActionMessage = "${pref.name}をインポート中…"
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
                SettingsGroup("ナビゲーション") {
                    SettingSwitch(
                        "ナビ中は画面を消さない", "アプリを表示している間に適用します。電池の消費が増えます。",
                        preferences.keepScreenOnDuringNavigation
                    ) { onChange(preferences.copy(keepScreenOnDuringNavigation = it)) }
                }
            }
            item {
                SettingsGroup("オフライン地図（関東各都県）") {
                    val totalMb = prefectureStates.filter { it.isDownloaded }.sumOf { it.sizeBytes } / 1024.0 / 1024.0
                    val downloadedCount = prefectureStates.count { it.isDownloaded }
                    Text(
                        if (downloadedCount > 0) "保存済み: $downloadedCount 都県（%.1f MB）".format(totalMb) else "未保存（都県を選択してダウンロードしてください）",
                        style = MaterialTheme.typography.titleSmall
                    )
                    SettingNote("オフラインで使用する地図データを都県ごとにダウンロードできます。不要な都県は削除して空き容量を確保できます。")
                    Spacer(Modifier.height(8.dp))

                    for (state in prefectureStates) {
                        val pref = state.info
                        val isDownloading = downloadingPrefectureId == pref.id
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(pref.name, style = MaterialTheme.typography.bodyLarge)
                                val statusText = when {
                                    isDownloading -> "処理中… ($mapDownloadPercent%)"
                                    state.isDownloaded -> "保存済み（%.1f MB）".format(state.sizeBytes / 1024.0 / 1024.0)
                                    pref.id == "tokyo" -> "未保存（アプリ内から即座に復元可能）"
                                    else -> "未保存（目安: 約${pref.approximateSizeMb} MB）"
                                }
                                SettingNote(statusText)
                                if (isDownloading) {
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { mapDownloadPercent / 100f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else if (!state.isDownloaded && pref.id != "tokyo") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            onClick = {
                                                urlDialogPrefecture = pref
                                                inputCustomUrl = KantoPrefectures.getDownloadUrl(pref, preferences.customMapBaseUrl)
                                            },
                                            modifier = Modifier.height(32.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                        ) {
                                            Text("URL指定", style = MaterialTheme.typography.labelMedium)
                                        }
                                        TextButton(
                                            onClick = {
                                                importTargetPrefecture = pref
                                                filePickerLauncher.launch("*/*")
                                            },
                                            modifier = Modifier.height(32.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                        ) {
                                            Text("ファイル選択", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }

                            if (state.isDownloaded) {
                                OutlinedButton(
                                    onClick = {
                                        val success = MapDownloadManager.deletePrefecture(context, pref)
                                        if (success) {
                                            mapActionMessage = "${pref.name}の地図を削除しました"
                                            scope.launch {
                                                refreshPrefectureStates()
                                                refreshSavedData()
                                                onMapUpdated()
                                            }
                                        }
                                    },
                                    enabled = downloadingPrefectureId == null
                                ) {
                                    Text("削除")
                                }
                            } else {
                                Button(
                                    onClick = {
                                        downloadingPrefectureId = pref.id
                                        mapDownloadPercent = 0
                                        val customUrl = if (preferences.customMapBaseUrl.isNotBlank()) {
                                            KantoPrefectures.getDownloadUrl(pref, preferences.customMapBaseUrl)
                                        } else null
                                        mapActionMessage = "${pref.name}を取得中…"
                                        scope.launch {
                                            val result = MapDownloadManager.downloadPrefecture(context, pref, customUrl) { p ->
                                                mapDownloadPercent = p
                                            }
                                            when (result) {
                                                is MapDownloadResult.Success -> {
                                                    mapActionMessage = "${pref.name}の取得が完了しました"
                                                    refreshPrefectureStates()
                                                    refreshSavedData()
                                                    onMapUpdated()
                                                }
                                                is MapDownloadResult.Failure -> {
                                                    mapActionMessage = "${pref.name}の取得に失敗しました:\n${result.message}"
                                                }
                                            }
                                            downloadingPrefectureId = null
                                        }
                                    },
                                    enabled = downloadingPrefectureId == null
                                ) {
                                    Text(if (isDownloading) "取得中…" else "取得")
                                }
                            }
                        }
                        HorizontalDivider()
                    }

                    if (mapActionMessage != null) {
                        Spacer(Modifier.height(8.dp))
                        SettingNote(requireNotNull(mapActionMessage))
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val activeBaseUrl = preferences.customMapBaseUrl.ifBlank { KantoPrefectures.DEFAULT_BASE_URL }
                        Column(modifier = Modifier.weight(1f)) {
                            SettingNote("配信元URL: $activeBaseUrl")
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
                title = { Text("地図の配信元ベースURL設定") },
                text = {
                    Column {
                        Text("全都県の地図ファイルが配置されているベースURLを設定します。空欄にすると既定のURLに戻ります。")
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
                title = { Text("${pref.name}のURL指定ダウンロード") },
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
                                mapActionMessage = "${pref.name}をダウンロード中…"
                                scope.launch {
                                    val result = MapDownloadManager.downloadPrefecture(context, pref, urlToDownload) { p ->
                                        mapDownloadPercent = p
                                    }
                                    when (result) {
                                        is MapDownloadResult.Success -> {
                                            mapActionMessage = "${pref.name}のダウンロードが完了しました"
                                            refreshPrefectureStates()
                                            refreshSavedData()
                                            onMapUpdated()
                                        }
                                        is MapDownloadResult.Failure -> {
                                            mapActionMessage = "${pref.name}のダウンロードに失敗しました:\n${result.message}"
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
