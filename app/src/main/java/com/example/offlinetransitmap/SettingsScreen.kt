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

@Composable
fun SettingsScreen(preferences: AppPreferences, onChange: (AppPreferences) -> Unit, dataNote: String = "") {
    val context = LocalContext.current
    var savedData by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        savedData = withContext(Dispatchers.IO) {
            listOf("地図" to ("maps" to "tokyo.pmtiles"), "時刻表" to ("timetable" to "timetable.db"))
                .map { (label, path) ->
                    val file = context.getExternalFilesDir(path.first)?.let { File(it, path.second) }
                    val status = if (file != null && file.isFile && file.length() > 0) {
                        "保存済み（%.1f MB）".format(file.length() / 1024.0 / 1024.0)
                    } else "未保存"
                    label to status
                }
        }
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
            item { Spacer(Modifier.height(16.dp)) }
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
