package com.example.offlinetransitmap

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

sealed class RoadDownloadResult {
    data class Success(val file: File, val sizeBytes: Long) : RoadDownloadResult()
    data class Failure(val message: String) : RoadDownloadResult()
}

object RoadDownloadManager {

    private const val BUFFER_SIZE = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    /**
     * SQLite 3 のヘッダーを検査。
     */
    fun validateRoadDbFile(file: File) {
        check(file.length() >= 1024) { "道路データファイルが不完全です（サイズ不足）" }
        file.inputStream().use { input ->
            val header = ByteArray(16)
            val read = input.read(header)
            check(read == 16 && String(header, Charsets.US_ASCII).startsWith("SQLite format 3")) {
                "有効なSQLite道路データベースファイルではありません"
            }
        }
    }

    /**
     * 都府県の道路データ（SQLite）をダウンロードする。
     */
    suspend fun downloadRoad(
        context: Context,
        info: PrefectureMapInfo,
        customUrl: String? = null,
        onProgress: (percent: Int) -> Unit = {}
    ): RoadDownloadResult = withContext(Dispatchers.IO) {
        val target = KantoPrefectures.getRoadFile(context, info)
        val targetDir = target.parentFile ?: return@withContext RoadDownloadResult.Failure("保存先ディレクトリが取得できません")
        if (!targetDir.exists()) targetDir.mkdirs()

        val partFile = File(targetDir, "${target.name}.part")
        if (partFile.exists()) partFile.delete()

        // 1. 同梱アセットがある場合（東京など roads.db / roads_tokyo.db）
        val assetNames = listOf("bootstrap/${info.roadFileName}", "bootstrap/roads.db")
        for (assetPath in assetNames) {
            val hasAsset = customUrl == null && runCatching {
                context.assets.open(assetPath).use { it.available() > 0 }
            }.getOrDefault(false)

            if (hasAsset) {
                try {
                    context.assets.open(assetPath).use { input ->
                        partFile.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesCopied = 0L
                            val totalLength = info.roadApproximateSizeMb * 1024L * 1024L
                            while (true) {
                                val bytes = input.read(buffer)
                                if (bytes < 0) break
                                output.write(buffer, 0, bytes)
                                bytesCopied += bytes
                                val percent = if (totalLength > 0) ((bytesCopied * 100.0) / totalLength).toInt().coerceIn(0, 99) else 50
                                onProgress(percent)
                            }
                            output.fd.sync()
                        }
                    }
                    validateRoadDbFile(partFile)
                    Files.move(partFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    RoadRouter.reload(context)
                    onProgress(100)
                    return@withContext RoadDownloadResult.Success(target, target.length())
                } catch (e: Exception) {
                    partFile.delete()
                }
            }
        }

        // 2. ネットワークからのダウンロード
        val downloadUrl = customUrl ?: KantoPrefectures.getRoadDownloadUrl(info)
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            var currentUrl = downloadUrl
            var redirectCount = 0

            while (redirectCount < 5) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "OfflineTransitMap-Android")
                }

                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location != null) {
                        currentUrl = location
                        redirectCount++
                        continue
                    }
                }
                break
            }

            val finalConn = connection ?: return@withContext RoadDownloadResult.Failure("接続の確立に失敗しました")
            val code = finalConn.responseCode
            if (code == 404) {
                return@withContext RoadDownloadResult.Failure("配布元に道路データ（${info.roadFileName}）がまだ公開されていません (HTTP 404)。「ファイル選択」から直接取り込むか、URLをご確認ください。")
            }
            if (code !in 200..299) {
                return@withContext RoadDownloadResult.Failure("サーバーエラーが発生しました (HTTP $code)")
            }

            val totalBytes = finalConn.contentLengthLong.takeIf { it > 0 } ?: (info.roadApproximateSizeMb * 1024L * 1024L)
            inputStream = finalConn.inputStream
            outputStream = FileOutputStream(partFile)

            val buffer = ByteArray(BUFFER_SIZE)
            var downloaded = 0L
            var lastPercent = -1

            while (true) {
                val read = inputStream.read(buffer)
                if (read < 0) break
                outputStream.write(buffer, 0, read)
                downloaded += read

                val percent = if (totalBytes > 0) ((downloaded * 100.0) / totalBytes).toInt().coerceIn(0, 99) else 50
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
            outputStream.flush()
            outputStream.fd.sync()
            outputStream.close()
            outputStream = null
            inputStream.close()
            inputStream = null

            validateRoadDbFile(partFile)
            Files.move(partFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

            RoadRouter.reload(context)
            onProgress(100)
            RoadDownloadResult.Success(target, target.length())
        } catch (e: Exception) {
            partFile.delete()
            RoadDownloadResult.Failure(e.localizedMessage ?: e.message ?: "ダウンロード失敗")
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            connection?.disconnect()
        }
    }

    /**
     * ローカルファイル（Uri）から都府県の道路データをインポートする。
     */
    suspend fun importRoadFile(
        context: Context,
        info: PrefectureMapInfo,
        uri: Uri,
        onProgress: (percent: Int) -> Unit = {}
    ): RoadDownloadResult = withContext(Dispatchers.IO) {
        val target = KantoPrefectures.getRoadFile(context, info)
        val targetDir = target.parentFile ?: return@withContext RoadDownloadResult.Failure("保存先ディレクトリが取得できません")
        if (!targetDir.exists()) targetDir.mkdirs()

        val partFile = File(targetDir, "${target.name}.part")
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext RoadDownloadResult.Failure("指定されたファイルを開けませんでした")

            val fileSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            val totalLength = if (fileSize > 0) fileSize else (info.roadApproximateSizeMb * 1024L * 1024L)

            inputStream.use { input ->
                partFile.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesCopied = 0L
                    var lastPercent = -1

                    while (true) {
                        val bytes = input.read(buffer)
                        if (bytes < 0) break
                        output.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        val percent = if (totalLength > 0) ((bytesCopied * 100.0) / totalLength).toInt().coerceIn(0, 99) else 50
                        if (percent != lastPercent) {
                            onProgress(percent)
                            lastPercent = percent
                        }
                    }
                    output.fd.sync()
                }
            }

            validateRoadDbFile(partFile)
            Files.move(partFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            RoadRouter.reload(context)
            onProgress(100)
            RoadDownloadResult.Success(target, target.length())
        } catch (e: Exception) {
            partFile.delete()
            RoadDownloadResult.Failure(e.message ?: "インポートエラー")
        }
    }

    /**
     * 都府県の道路データを削除する。
     */
    fun deleteRoad(context: Context, info: PrefectureMapInfo): Boolean {
        val file = KantoPrefectures.getRoadFile(context, info)
        val dir = file.parentFile
        if (dir != null && dir.exists()) {
            File(dir, "${file.name}.part").delete()
        }
        val result = if (file.exists()) file.delete() else true
        RoadRouter.reload(context)
        return result
    }

    /**
     * ダウンロード済み道路データの合計サイズ（バイト）
     */
    fun getTotalRoadSizeBytes(context: Context): Long {
        val dir = context.getExternalFilesDir("roads") ?: File(context.filesDir, "roads")
        if (!dir.exists()) return 0L
        return dir.listFiles { f -> f.extension == "db" }?.sumOf { it.length() } ?: 0L
    }
}
