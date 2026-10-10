package com.example.offlinetransitmap

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

sealed class MapDownloadResult {
    data class Success(val file: File, val sizeBytes: Long) : MapDownloadResult()
    data class Failure(val message: String) : MapDownloadResult()
}

object MapDownloadManager {

    private const val BUFFER_SIZE = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val NUM_CHUNKS = 3
    private const val MIN_CHUNK_SIZE_FOR_PARALLEL = 5 * 1024 * 1024L // 5MB以上で並列ダウンロード

    /**
     * PMTiles v3 のヘッダーを検査。
     */
    fun validateMapFile(file: File) {
        check(file.length() >= 127) { "地図ファイルが不完全です（サイズ不足）" }
        file.inputStream().use { input ->
            val header = ByteArray(8)
            check(input.read(header) == 8 && header.copyOfRange(0, 7).toString(Charsets.US_ASCII) == "PMTiles" && header[7].toInt() == 3) {
                "PMTiles v3 の地図ファイルではありません"
            }
        }
    }

    private data class ResolvedResource(
        val finalUrl: String,
        val totalLength: Long,
        val supportsRange: Boolean
    )

    /**
     * リダイレクトを追従し、最終URL、総ファイルサイズ、Range対応可否を軽量に特定する。
     */
    private fun resolveResource(urlStr: String, prefFileName: String): ResolvedResource {
        var address = URL(urlStr)
        var finalUrl = urlStr
        var totalLength = -1L
        var supportsRange = false

        repeat(7) {
            require(address.protocol == "https" || address.protocol == "http") { "無効なURLプロトコルです: ${address.protocol}" }
            val conn = (address.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "OfflineTransitMap-Android")
                setRequestProperty("Range", "bytes=0-0")
            }

            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("リダイレクト先がありません")
                conn.disconnect()
                address = URL(address, loc)
                finalUrl = address.toString()
            } else if (code == 206) {
                supportsRange = true
                finalUrl = address.toString()
                val cr = conn.getHeaderField("Content-Range")
                if (cr != null && cr.contains("/")) {
                    val totalStr = cr.substringAfter("/").trim()
                    totalLength = totalStr.toLongOrNull() ?: -1L
                }
                if (totalLength <= 0) {
                    totalLength = conn.contentLengthLong
                }
                runCatching { conn.inputStream.use { it.read() } }
                conn.disconnect()
                return ResolvedResource(finalUrl, totalLength, supportsRange)
            } else if (code == 200) {
                finalUrl = address.toString()
                totalLength = conn.contentLengthLong
                val acceptRanges = conn.getHeaderField("Accept-Ranges")
                supportsRange = acceptRanges?.contains("bytes", ignoreCase = true) == true
                conn.disconnect()
                return ResolvedResource(finalUrl, totalLength, supportsRange)
            } else if (code == 404) {
                conn.disconnect()
                throw IllegalStateException("配布元に地図ファイル（$prefFileName）がまだ公開されていません (HTTP 404)。「URLを指定」からダウンロードURLを設定するか、「ファイル選択」をご利用ください。")
            } else {
                conn.disconnect()
                throw IllegalStateException("ダウンロード元への接続に失敗しました (HTTP $code)")
            }
        }

        throw IllegalStateException("リダイレクトの回数が上限を超えました")
    }

    /**
     * 特定の都県の地図ファイルをダウンロードして保存する。
     * - 同梱アセットがある場合はアセットから即座に展開
     * - HTTP Range に対応している場合は 3並列チャンク＋レジューム＋ストール自動再接続で爆速ダウンロード
     * - Range 非対応の場合は単一ストリームで安全にダウンロード
     */
    suspend fun downloadPrefecture(
        context: Context,
        info: PrefectureMapInfo,
        customUrl: String? = null,
        onProgress: (percent: Int) -> Unit = {}
    ): MapDownloadResult = withContext(Dispatchers.IO) {
        val target = KantoPrefectures.getFile(context, info)
        val targetDir = target.parentFile ?: return@withContext MapDownloadResult.Failure("保存先ディレクトリが取得できません")
        if (!targetDir.exists()) targetDir.mkdirs()

        val partFile = File(targetDir, "${target.name}.part")

        // 1. 同梱アセットがある場合（カスタムURL未指定時）はアセットから直接復元して404/ネット負荷を回避
        val assetPath = "bootstrap/${info.fileName}"
        val hasAsset = customUrl == null && runCatching {
            context.assets.open(assetPath).use { it.available() > 0 }
        }.getOrDefault(false)

        if (hasAsset) {
            try {
                context.assets.open(assetPath).use { input ->
                    partFile.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesCopied = 0L
                        val totalLength = info.approximateSizeMb * 1024L * 1024L
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
                validateMapFile(partFile)
                Files.move(partFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                onProgress(100)
                return@withContext MapDownloadResult.Success(target, target.length())
            } catch (e: Exception) {
                partFile.delete()
                // アセット展開に失敗した場合はネットワーク取得へフォールバック
            }
        }

        // 2. ネットワークからのダウンロード
        val downloadUrl = customUrl ?: info.downloadUrl

        try {
            val resource = resolveResource(downloadUrl, info.fileName)
            val totalLength = if (resource.totalLength > 0) resource.totalLength else (info.approximateSizeMb * 1024L * 1024L)

            // すでに完成ファイルが存在し、サイズが一致し、正当なPMTilesならダウンロード不要
            if (target.exists() && target.length() == totalLength) {
                runCatching { validateMapFile(target) }.onSuccess {
                    onProgress(100)
                    return@withContext MapDownloadResult.Success(target, target.length())
                }
            }

            if (resource.supportsRange && totalLength >= MIN_CHUNK_SIZE_FOR_PARALLEL) {
                // 3並列チャンクダウンロード（レジューム＋ストール自動再接続対応）
                downloadMultiChunk(
                    targetDir = targetDir,
                    target = target,
                    initialUrl = downloadUrl,
                    finalUrl = resource.finalUrl,
                    totalLength = totalLength,
                    onProgress = onProgress
                )
            } else {
                // 単一ストリームダウンロードフォールバック
                downloadSingleStream(
                    partFile = partFile,
                    target = target,
                    downloadUrl = downloadUrl,
                    totalLength = totalLength,
                    onProgress = onProgress
                )
            }

            onProgress(100)
            MapDownloadResult.Success(target, target.length())
        } catch (e: Exception) {
            MapDownloadResult.Failure(e.message ?: "ダウンロードエラー")
        }
    }

    /**
     * 3並列チャンクダウンロード（Range対応サーバー用）
     * - 各チャンクを独立した一時ファイル（.part.0, .part.1, .part.2）に保存
     * - 途中で中断しても既存のバイト数から即座に再開（レジューム）
     * - スロットリングや速度停滞（Stall）を自動検知して新品コネクションを張り直す
     */
    private suspend fun downloadMultiChunk(
        targetDir: File,
        target: File,
        initialUrl: String,
        finalUrl: String,
        totalLength: Long,
        onProgress: (percent: Int) -> Unit
    ) = coroutineScope {
        val numChunks = NUM_CHUNKS
        val chunkSize = (totalLength + numChunks - 1) / numChunks
        val chunkFiles = (0 until numChunks).map { File(targetDir, "${target.name}.part.$it") }

        var lastNotifiedPercent = -1
        val progressLock = Any()

        val notifyProgress: () -> Unit = {
            val currentTotal = chunkFiles.sumOf { if (it.exists()) it.length() else 0L }
            val percent = if (totalLength > 0) ((currentTotal * 100.0) / totalLength).toInt().coerceIn(0, 99) else 50
            synchronized(progressLock) {
                if (percent > lastNotifiedPercent) {
                    lastNotifiedPercent = percent
                    onProgress(percent)
                }
            }
        }

        // 初期進捗の反映（中断再開時）
        notifyProgress()

        // 3並列ダウンロード
        val deferreds = (0 until numChunks).map { chunkIndex ->
            val chunkStart = chunkIndex * chunkSize
            val chunkEnd = minOf((chunkIndex + 1) * chunkSize - 1, totalLength - 1)
            async(Dispatchers.IO) {
                downloadSingleChunk(
                    chunkFile = chunkFiles[chunkIndex],
                    chunkStart = chunkStart,
                    chunkEnd = chunkEnd,
                    initialUrl = initialUrl,
                    initialFinalUrl = finalUrl,
                    prefFileName = target.name,
                    onChunkProgress = notifyProgress
                )
            }
        }

        deferreds.awaitAll()

        // チャンク結合
        val partMerged = File(targetDir, "${target.name}.part")
        try {
            partMerged.outputStream().use { outStream ->
                for (cf in chunkFiles) {
                    cf.inputStream().use { inStream ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val r = inStream.read(buffer)
                            if (r < 0) break
                            outStream.write(buffer, 0, r)
                        }
                    }
                }
                outStream.fd.sync()
            }

            validateMapFile(partMerged)
            Files.move(partMerged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

            // 成功したためチャンク一時ファイルを削除
            chunkFiles.forEach { it.delete() }
        } catch (e: Exception) {
            partMerged.delete()
            throw e
        }
    }

    /**
     * 1つのチャンクをダウンロードする（レジューム＋ストール自動再接続＋リトライ）
     */
    private suspend fun downloadSingleChunk(
        chunkFile: File,
        chunkStart: Long,
        chunkEnd: Long,
        initialUrl: String,
        initialFinalUrl: String,
        prefFileName: String,
        onChunkProgress: () -> Unit
    ) {
        val chunkTotal = chunkEnd - chunkStart + 1
        if (chunkFile.exists() && chunkFile.length() > chunkTotal) {
            chunkFile.delete()
        }
        if (chunkFile.exists() && chunkFile.length() == chunkTotal) {
            onChunkProgress()
            return
        }

        var currentUrl = initialFinalUrl
        var attempts = 0
        val maxRetries = 15

        while (attempts < maxRetries) {
            val downloadedInChunk = if (chunkFile.exists()) chunkFile.length() else 0L
            if (downloadedInChunk >= chunkTotal) {
                break
            }
            attempts++

            val rangeStart = chunkStart + downloadedInChunk
            val rangeEnd = chunkEnd

            var conn: HttpURLConnection? = null
            try {
                var reqUrl = URL(currentUrl)
                conn = (reqUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "OfflineTransitMap-Android")
                    setRequestProperty("Range", "bytes=$rangeStart-$rangeEnd")
                }

                val code = conn.responseCode
                // SASトークンの期限切れ等の場合はリダイレクト元からURLを再解決
                if (code == 403 || code == 401) {
                    conn.disconnect()
                    val refreshed = resolveResource(initialUrl, prefFileName)
                    currentUrl = refreshed.finalUrl
                    continue
                }

                if (code != 206 && code != 200) {
                    conn.disconnect()
                    throw IllegalStateException("チャンクダウンロードエラー (HTTP $code)")
                }

                val inStream = conn.inputStream
                FileOutputStream(chunkFile, true).use { outStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesSinceCheck = 0L
                    var lastCheckTime = System.currentTimeMillis()

                    while (true) {
                        val read = inStream.read(buffer)
                        if (read < 0) break
                        outStream.write(buffer, 0, read)
                        bytesSinceCheck += read

                        val now = System.currentTimeMillis()
                        // 1秒ごとに進捗通知
                        if (now - lastCheckTime >= 1_000) {
                            onChunkProgress()
                            // 5秒間の転送量を監視。もし極端な低速（32KB未満＝約6.4KB/s未満）かつ残量がある場合、再接続
                            val elapsed = now - lastCheckTime
                            if (elapsed >= 5_000) {
                                if (bytesSinceCheck < 32 * 1024 && (chunkTotal - chunkFile.length()) > 64 * 1024) {
                                    throw SocketTimeoutException("転送速度の低下を検知したため再接続します (${bytesSinceCheck}B/${elapsed}ms)")
                                }
                                bytesSinceCheck = 0L
                                lastCheckTime = now
                            }
                        }
                    }
                    outStream.fd.sync()
                }
                conn.disconnect()
                onChunkProgress()
            } catch (e: Exception) {
                conn?.disconnect()
                if (attempts >= maxRetries) {
                    throw e
                }
                delay(500)
            }
        }

        check(chunkFile.length() == chunkTotal) {
            "チャンクのダウンロードが不完全です（${chunkFile.length()} / $chunkTotal bytes）"
        }
    }

    /**
     * 単一ストリームダウンロードフォールバック（Range非対応サーバー用）
     */
    private suspend fun downloadSingleStream(
        partFile: File,
        target: File,
        downloadUrl: String,
        totalLength: Long,
        onProgress: (percent: Int) -> Unit
    ) {
        var attempts = 0
        val maxRetries = 10
        var address = URL(downloadUrl)

        while (attempts < maxRetries) {
            attempts++
            var connection: HttpURLConnection? = null
            try {
                var currentAddr = address
                repeat(7) {
                    val conn = (currentAddr.openConnection() as HttpURLConnection).apply {
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "OfflineTransitMap-Android")
                    }
                    val code = conn.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("リダイレクト先がありません")
                        conn.disconnect()
                        currentAddr = URL(currentAddr, loc)
                    } else if (code == 200) {
                        connection = conn
                        return@repeat
                    } else {
                        conn.disconnect()
                        throw IllegalStateException("接続エラー (HTTP $code)")
                    }
                }

                val conn = connection ?: throw IllegalStateException("接続を確立できませんでした")
                val inStream = conn.inputStream
                val resolvedTotal = conn.contentLengthLong.takeIf { it > 0 } ?: totalLength

                partFile.outputStream().use { outStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesCopied = 0L
                    var lastPercent = -1

                    while (true) {
                        val bytes = inStream.read(buffer)
                        if (bytes < 0) break
                        outStream.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        val percent = if (resolvedTotal > 0) ((bytesCopied * 100.0) / resolvedTotal).toInt().coerceIn(0, 99) else 50
                        if (percent != lastPercent) {
                            onProgress(percent)
                            lastPercent = percent
                        }
                    }
                    outStream.fd.sync()
                }
                conn.disconnect()

                validateMapFile(partFile)
                Files.move(partFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (e: Exception) {
                connection?.disconnect()
                if (attempts >= maxRetries) {
                    partFile.delete()
                    throw e
                }
                delay(1000)
            }
        }
    }

    /**
     * 端末内のファイル（Uri）から都県の地図ファイルをインポートして保存する。
     */
    suspend fun importMapFile(
        context: Context,
        info: PrefectureMapInfo,
        uri: Uri,
        onProgress: (percent: Int) -> Unit = {}
    ): MapDownloadResult = withContext(Dispatchers.IO) {
        val target = KantoPrefectures.getFile(context, info)
        val targetDir = target.parentFile ?: return@withContext MapDownloadResult.Failure("保存先ディレクトリが取得できません")
        if (!targetDir.exists()) targetDir.mkdirs()

        val partFile = File(targetDir, "${target.name}.part")
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext MapDownloadResult.Failure("指定されたファイルを開けませんでした")

            val fileSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            val totalLength = if (fileSize > 0) fileSize else (info.approximateSizeMb * 1024L * 1024L)

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

            validateMapFile(partFile)
            Files.move(partFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            onProgress(100)
            MapDownloadResult.Success(target, target.length())
        } catch (e: Exception) {
            partFile.delete()
            MapDownloadResult.Failure(e.message ?: "インポートエラー")
        }
    }

    /**
     * 都県の地図ファイルを削除して空き容量を確保する。
     * （本体だけでなく、ダウンロード途中の一時ファイル群もすべて消去）
     */
    fun deletePrefecture(context: Context, info: PrefectureMapInfo): Boolean {
        val file = KantoPrefectures.getFile(context, info)
        val dir = file.parentFile
        if (dir != null && dir.exists()) {
            File(dir, "${file.name}.part").delete()
            for (i in 0 until 10) {
                File(dir, "${file.name}.part.$i").delete()
            }
        }
        if (file.exists()) {
            return file.delete()
        }
        return true
    }

    /**
     * 保存されている地図の合計サイズ（バイト）
     */
    fun getTotalDownloadedSizeBytes(context: Context): Long {
        val downloaded = KantoPrefectures.getDownloadedList(context)
        return downloaded.sumOf { KantoPrefectures.getFile(context, it).length() }
    }
}
