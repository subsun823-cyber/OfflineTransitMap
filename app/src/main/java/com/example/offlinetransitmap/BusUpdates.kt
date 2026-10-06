package com.example.offlinetransitmap

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

internal object BusUpdates {
    private val lock=Mutex()
    private const val PERIODIC="bus-gtfs-periodic"
    private class UpdateProblem(message: String): Exception(message)
    fun storage(context: Context) = context.getSharedPreferences("bus_updates",Context.MODE_PRIVATE)
    fun feeds(context: Context): List<BusFeed> {
        val defaults=JSONObject(context.assets.open("bootstrap/bus-updates.json").bufferedReader().use { it.readText() })
        val preferences=storage(context)
        return listOf(Triple("ntbus","NT_","西東京バス等"),Triple("keio-bus",KeioDatabase.BUS_PREFIX,"京王バス")).map { (id,prefix,label) ->
            BusFeed(id,prefix,label,preferences.getString("$id.url",defaults.optString(id)).orEmpty().trim())
        }
    }
    fun schedule(context: Context) {
        val preferences=storage(context);val manager=WorkManager.getInstance(context)
        if (!preferences.getBoolean("enabled",true)) { manager.cancelUniqueWork(PERIODIC);return }
        val request=PeriodicWorkRequestBuilder<BusUpdateWorker>(24,TimeUnit.HOURS)
            .setConstraints(constraints(context)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,request)
    }
    private fun constraints(context: Context) = Constraints.Builder()
        .setRequiredNetworkType(if(storage(context).getBoolean("wifi",true)) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true).setRequiresBatteryNotLow(true).build()
    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("bus-gtfs-manual",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BusUpdateWorker>().setConstraints(constraints(context)).build())
    }
    private fun today() = LocalDate.now(ZoneId.of("Asia/Tokyo")).format(DateTimeFormatter.BASIC_ISO_DATE).toInt()
    private fun directory(context: Context) = File(context.filesDir,"bus-updates").apply { mkdirs() }
    private fun state(context: Context, id: String, message: String) {
        storage(context).edit().putString("$id.status",message).putLong("$id.checked",System.currentTimeMillis()).apply()
    }
    fun status(context: Context, feed: BusFeed): String {
        val p=storage(context);val end=p.getInt("${feed.id}.end",0)
        val expiry=if(end>0 && end<today()) "保存済み時刻表の有効期限が切れています。" else ""
        if(feed.url.isBlank()) return expiry+"更新元URLが未設定です"
        return expiry+p.getString("${feed.id}.status","次の確認を待っています").orEmpty()
    }

    // Workers serialize conversion, but do not hold the publication lock while downloading.
    private val workerLock=Mutex()
    suspend fun check(context: Context): Boolean = workerLock.withLock {
        val task=currentCoroutineContext();var failed=false
        for(feed in feeds(context)) {
            task.ensureActive()
            if(feed.url.isBlank()) continue
            val prefs=storage(context)
            val work=java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(),"gtfs-").toFile()
            try {
                state(context,feed.id,"更新を確認中…")
                val pending=File(directory(context),"${feed.id}.json")
                val hasKnownData=prefs.getString("${feed.id}.validatorUrl",null)==feed.url &&
                    ((pending.isFile && File(directory(context),"${feed.id}.db").isFile) || prefs.getString("${feed.id}.appliedHash",null)!=null)
                val download=BusUpdateDownload.fetch(feed.url,File(work,"feed.zip"),
                    if(hasKnownData) prefs.getString("${feed.id}.etag","").orEmpty() else "",
                    if(hasKnownData) prefs.getString("${feed.id}.modified","").orEmpty() else "") { task.ensureActive() }
                if(download==null) {
                    state(context,feed.id,if(pending.isFile) pendingMessage(pending) else "更新はありません")
                    continue
                }
                if(download.hash==prefs.getString("${feed.id}.appliedHash",null) && prefs.getString("${feed.id}.validatorUrl",null)==feed.url) {
                    state(context,feed.id,"更新はありません");continue
                }
                val pendingMatches=lock.withLock {
                    runCatching { val saved=JSONObject(pending.readText())
                        saved.getString("url")==feed.url && saved.getString("hash")==download.hash &&
                            File(directory(context),"${feed.id}.db").isFile
                    }.getOrDefault(false)
                }
                if(pendingMatches) { state(context,feed.id,pendingMessage(pending));continue }
                val seed=File(work,"seed.db")
                val info=BusSeedDatabase.build(context,File(work,"feed.zip"),seed,feed) { task.ensureActive() }
                if(info.end<today()) throw UpdateProblem("配布元の時刻表も期限切れです。最新版の公開を待って再確認します。")
                if(info.end<prefs.getInt("${feed.id}.end",0)) throw UpdateProblem("配布元の有効期限が現在より古いため、保存済みデータを維持します。")
                KeioDatabase.validate(seed)
                val metadata=JSONObject().put("url",feed.url).put("hash",download.hash)
                    .put("seedHash",BusUpdateDownload.hash(seed)).put("start",info.start).put("end",info.end)
                val metaFile=File(work,"metadata.json").apply { writeText(metadata.toString()) }
                lock.withLock {
                    task.ensureActive()
                    if(feeds(context).first { it.id==feed.id }.url!=feed.url) return@withLock
                    // Persist payload first, then metadata. A crash between files is rejected by SHA-256 on application.
                    DataFileIO.commit(seed,File(directory(context),"${feed.id}.db"))
                    DataFileIO.commit(metaFile,pending)
                    prefs.edit().putString("${feed.id}.validatorUrl",feed.url).putString("${feed.id}.etag",download.etag)
                        .putString("${feed.id}.modified",download.modified).apply()
                    state(context,feed.id,if(info.start>today()) "次のダイヤを取得済みです（${info.start}から適用）" else "更新を取得済みです。次回起動時に自動適用します")
                }
            } catch(e: CancellationException) { state(context,feed.id,"確認を中断しました。保存済みデータを維持しています。");throw e }
            catch(e: Exception) {
                failed=true
                // Do not leak a provider URL/API key through arbitrary network exception messages.
                state(context,feed.id,if(e is UpdateProblem) e.message.orEmpty() else "更新できませんでした。保存済みデータを維持します。配布元・通信・空き容量をご確認ください。")
                prefs.edit().remove("${feed.id}.etag").remove("${feed.id}.modified").apply()
            } finally { work.deleteRecursively() }
        }
        !failed
    }

    suspend fun applyPending(context: Context, target: File): List<String> = lock.withLock {
        val warnings=mutableListOf<String>();val task=currentCoroutineContext()
        for(feed in feeds(context)) {
            val meta=File(directory(context),"${feed.id}.json");val seed=File(directory(context),"${feed.id}.db")
            if(!meta.isFile) continue
            val staged=File(target.parentFile,"bus-update.new.db")
            try {
                val info=JSONObject(meta.readText())
                if(info.getString("url")!=feed.url) { meta.delete();seed.delete();continue }
                val currentEnd=android.database.sqlite.SQLiteDatabase.openDatabase(target.path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                    KeioDatabase.feedEnd(db,feed.prefix)
                }
                when(busUpdateDecision(info.getInt("start"),info.getInt("end"),currentEnd,today())) {
                    UpdateDecision.WAIT -> continue
                    UpdateDecision.EXPIRED -> error("取得済みの更新が期限切れです")
                    UpdateDecision.OLDER -> error("現在より古い有効期間の更新です")
                    UpdateDecision.APPLY -> Unit
                }
                require(BusUpdateDownload.hash(seed)==info.getString("seedHash")) { "更新ファイルの検証に失敗しました" }
                require(!File(target.path+"-wal").let { it.exists() && it.length()>0 })
                clear(staged)
                target.copyTo(staged,true)
                KeioDatabase.merge(staged,seed,info.getString("hash"),feed.prefix,"${feed.id}.version")
                android.database.sqlite.SQLiteDatabase.openDatabase(staged.path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.execSQL("INSERT OR REPLACE INTO app_data VALUES (?,?)",arrayOf("update.${feed.id}",info.getString("hash")))
                }
                task.ensureActive();DataFileIO.commit(staged,target)
                storage(context).edit().putString("${feed.id}.appliedHash",info.getString("hash")).putInt("${feed.id}.end",info.getInt("end")).apply()
                state(context,feed.id,"更新を適用しました（有効期限 ${info.getInt("end")}）")
                meta.delete();seed.delete()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                warnings.add("${feed.label}の更新を適用できなかったため、保存済み時刻表を使います。")
                state(context,feed.id,"更新適用に失敗しました。保存済みデータを維持しています。")
                // Retry download instead of permanently trusting a damaged pending package.
                meta.delete();seed.delete()
                storage(context).edit().remove("${feed.id}.etag").remove("${feed.id}.modified").apply()
            } finally { runCatching { clear(staged) } }
        }
        warnings
    }
    private fun pendingMessage(file: File): String = runCatching {
        val start=JSONObject(file.readText()).getInt("start")
        if(start>today()) "次のダイヤを取得済みです（$start 以降の起動時に適用）" else "検証済み更新を次回起動時に適用します"
    }.getOrDefault("取得済み更新を次回起動時に確認します")
    private fun clear(file: File) {
        for(suffix in listOf("","-journal","-wal","-shm")) File(file.path+suffix).let { check(!it.exists() || it.delete()) }
    }
}

class BusUpdateWorker(context: Context, parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if(BusUpdates.check(applicationContext)) Result.success() else if(runAttemptCount<3) Result.retry() else Result.failure()
    }
}
