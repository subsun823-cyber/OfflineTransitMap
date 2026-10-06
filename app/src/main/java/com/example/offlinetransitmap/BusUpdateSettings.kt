package com.example.offlinetransitmap

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BusUpdateSettings() {
    val context=LocalContext.current.applicationContext
    val preferences=remember { BusUpdates.storage(context) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(preferences) {
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_ -> revision++ }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val feeds=remember(revision) { BusUpdates.feeds(context) }
    Column {
        Row {
            Text("時刻表を自動更新",Modifier.weight(1f))
            Switch(preferences.getBoolean("enabled",true),onCheckedChange={ preferences.edit().putBoolean("enabled",it).apply();BusUpdates.schedule(context) })
        }
        Row {
            Text("Wi-Fi等の従量制でない接続のみ",Modifier.weight(1f))
            Switch(preferences.getBoolean("wifi",true),onCheckedChange={ preferences.edit().putBoolean("wifi",it).apply();BusUpdates.schedule(context) })
        }
        Text("1日ごとに確認します。省電力設定・通信状況により遅れることがあります。取得した更新は、検索やナビを保護するため次回起動時に自動適用します。",style=MaterialTheme.typography.bodySmall)
        for(feed in feeds) {
            var url by remember(feed.url) { mutableStateOf(feed.url) }
            var error by remember { mutableStateOf(false) }
            Text(feed.label,Modifier.padding(top=16.dp),style=MaterialTheme.typography.titleSmall)
            Text(BusUpdates.status(context,feed),style=MaterialTheme.typography.bodySmall)
            val checked=preferences.getLong("${feed.id}.checked",0)
            if(checked>0) Text("最終確認: ${DateFormat.getDateTimeInstance().format(Date(checked))}",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value=url,onValueChange={url=it;error=false},modifier=Modifier.fillMaxWidth(),singleLine=true,
                label={Text("GTFS ZIPのHTTPS URL")},isError=error)
            if(error) Text("HTTPSのファイル本体URLを入力してください",color=MaterialTheme.colorScheme.error)
            Button(onClick={
                val value=url.trim()
                if(value.isNotEmpty() && !BusUpdateDownload.validUrl(value)) error=true
                else {
                    preferences.edit().putString("${feed.id}.url",value).remove("${feed.id}.etag").remove("${feed.id}.modified")
                        .putString("${feed.id}.status","更新元を保存しました").apply()
                    BusUpdates.schedule(context)
                }
            },enabled=url.trim()!=feed.url) { Text("更新元を保存") }
        }
        Button(onClick={BusUpdates.checkNow(context)},enabled=feeds.any { it.url.isNotBlank() }) { Text("今すぐ更新を確認") }
        Text("有効期限が切れても最新版が未公開の場合は延長しません。取得失敗時は保存済みの時刻表を残します。",style=MaterialTheme.typography.bodySmall)
    }
}
