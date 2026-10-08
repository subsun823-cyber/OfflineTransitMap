package com.example.offlinetransitmap

import android.content.Context
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream

internal object OfflineDataSetup {
    private val lock = Mutex()

    suspend fun prepare(context: Context, allowDownloads: Boolean = true, progress: (String) -> Unit): List<String> = lock.withLock {
        withContext(Dispatchers.IO) {
            val task = currentCoroutineContext()
            val warnings = mutableListOf<String>()
            val mapDir = requireNotNull(context.getExternalFilesDir("maps")) { "地図の保存先を開けません" }
            val dbDir = requireNotNull(context.getExternalFilesDir("timetable")) { "時刻表の保存先を開けません" }
            val targets = mapOf("map" to File(mapDir, "tokyo.pmtiles"), "timetable" to File(dbDir, "timetable.db"))
            val config = JSONObject(context.assets.open("bootstrap/data-files.json").bufferedReader().use { it.readText() }).getJSONArray("files")
            val ids = mutableSetOf<String>()
            for (i in 0 until config.length()) {
                val spec = config.getJSONObject(i)
                val id = spec.getString("id")
                require(ids.add(id)) { "配布設定が重複しています" }
                val target = requireNotNull(targets[id]) { "配布設定のファイル種別が不正です" }
                // 初回配布のみ。手動配置済みのデータや、京王追加済みのDBは上書きしない。
                if (target.isFile && target.length() > 0) {
                    val valid = runCatching { if (id == "map") validateMap(target) else KeioDatabase.validate(target) }.isSuccess
                    val bootstrapOnly = id == "timetable" && valid && KeioDatabase.isBootstrapOnly(target)
                    if (valid && !bootstrapOnly) continue
                }
                val size = spec.getLong("size")
                val hash = spec.getString("sha256")
                val label = if (id == "map") "地図" else "時刻表"
                var lastPercent = -1
                val report: (Long) -> Unit = { bytes ->
                    task.ensureActive()
                    val percent = (bytes * 100.0 / size).toInt()
                    if (percent != lastPercent) {
                        progress("$label を準備中 $percent%")
                        lastPercent = percent
                    }
                }
                val asset = spec.optString("asset")
                val url = spec.optString("url")
                require(asset.isNotBlank() xor url.isNotBlank()) { "同梱ファイルまたは配布URLを指定してください" }
                if (url.isNotBlank() && !allowDownloads) {
                    warnings.add("$label のダウンロードを省略しました。次回の起動時に再度取得します。")
                    continue
                }
                val part = if (asset.isNotBlank()) {
                    DataFileIO.stage(context.assets.open(asset), target, size, hash, report)
                } else DataFileIO.download(url, target, size, hash, report)
                try {
                    if (id == "map") validateMap(part) else KeioDatabase.validate(part)
                    if (id == "timetable") checkNoWal(target)
                    task.ensureActive()
                    DataFileIO.commit(part, target)
                } finally { part.delete() }
            }
            progress("京王・小田急の駅・バス停・時刻表を準備中…")
            val target = targets.getValue("timetable")
            if (target.exists() && target.length() > 0) KeioDatabase.validate(target)
            for ((assetName, prefix) in listOf("keio" to "ODPT_KEIO:", "keio-bus" to KeioDatabase.BUS_PREFIX, "odakyu" to KeioDatabase.ODAKYU_PREFIX)) {
                val info = JSONObject(context.assets.open("bootstrap/$assetName-info.json").bufferedReader().use { it.readText() })
                val version = info.getString("sha256")
                if (!target.exists() || target.length() == 0L || KeioDatabase.version(target, "$assetName.version") != version) {
                    // 未チェックポイントのDBをファイルコピーすると内容を失うため停止する。
                    checkNoWal(target)
                    val seedName = File(dbDir, "$assetName-seed.db")
                    val seed = DataFileIO.stage(GZIPInputStream(context.assets.open("bootstrap/$assetName.bundle")), seedName, info.getLong("size"), version)
                    val staged = File(dbDir, "timetable.new.db")
                    try {
                        clearStaging(staged)
                        KeioDatabase.validate(seed)
                        val fresh = !target.exists() || target.length() == 0L
                        if (!fresh) target.copyTo(staged, overwrite = true) else seed.copyTo(staged, overwrite = true)
                        KeioDatabase.merge(staged, seed, version, prefix, "$assetName.version")
                        if (fresh) KeioDatabase.markBootstrapOnly(staged)
                        task.ensureActive()
                        DataFileIO.commit(staged, target)
                    } finally {
                        seed.delete()
                        clearStaging(staged)
                    }
                }
                warnings.add(info.getString("note"))
            }
            val map = targets.getValue("map")
            if (map.isFile) validateMap(map) else warnings.add("地図データが未準備です。配布元が設定されるまで、背景地図なしで駅を表示します。")
            // データの範囲を端末ごとに確認可能にする。
            if (KeioDatabase.isBootstrapOnly(target)) warnings.add("この端末には京王・小田急のみ収録されています。JR・西東京バス入り時刻表の配布設定が必要です。")
            warnings
        }
    }

    private fun checkNoWal(file: File) {
        check(!File(file.path + "-wal").let { it.exists() && it.length() > 0 }) { "時刻表DBの更新が完了していません。アプリを終了してから再度お試しください" }
    }

    private fun clearStaging(file: File) {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val temporary = File(file.path + suffix)
            check(!temporary.exists() || temporary.delete()) { "準備中ファイルを整理できません。空き容量・保存先を確認してください" }
        }
    }

    private fun validateMap(file: File) {
        check(file.length() >= 127) { "地図ファイルが不完全です" }
        file.inputStream().use { input ->
            val header = ByteArray(8)
            check(input.read(header) == 8 && header.copyOfRange(0,7).toString(Charsets.US_ASCII) == "PMTiles" && header[7].toInt() == 3) { "PMTiles v3 の地図ファイルではありません" }
        }
    }
}

@Composable
internal fun PreparedOfflineApp(content: @Composable (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    var attempt by remember { mutableIntStateOf(0) }
    var allowDownloads by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf("オフラインデータを確認中…") }
    var failure by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf<List<String>?>(null) }
    var accepted by remember { mutableStateOf(false) }
    val storage = remember { context.getSharedPreferences("data_setup", Context.MODE_PRIVATE) }
    LaunchedEffect(attempt) {
        failure = null
        try {
            val result = OfflineDataSetup.prepare(context, allowDownloads) { progress = it }
            notes = result
            accepted = storage.getString("accepted_notice", null) == result.joinToString("\n")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = "データを準備できませんでした。通信と空き容量を確認してください。\n${e.message.orEmpty()}" }
    }
    val ready = notes
    if (ready != null && accepted) {
        content(ready.joinToString("\n"))
    } else {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("オフラインデータの準備", style = MaterialTheme.typography.headlineSmall)
                if (failure != null) {
                    Text(requireNotNull(failure))
                    Button(onClick = { allowDownloads = true; attempt++ }) { Text("再試行") }
                    Button(onClick = { allowDownloads = false; attempt++ }) { Text("同梱・保存済みデータで開始") }
                } else if (ready != null) {
                    ready.forEach { Text(it) }
                    Text("取得済みのデータは、次回から通信なしで利用できます。")
                    Button(onClick = {
                        storage.edit().putString("accepted_notice", ready.joinToString("\n")).apply()
                        accepted = true
                    }) { Text("開始") }
                } else {
                    CircularProgressIndicator()
                    Text(progress)
                    Text("初回は同梱データの展開や、設定された配布先からの取得を行います。")
                }
            }
        }
    }
}
