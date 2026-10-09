package com.example.offlinetransitmap

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

sealed class MapDownloadResult {
    data class Success(val file: File, val sizeBytes: Long) : MapDownloadResult()
    data class Failure(val message: String) : MapDownloadResult()
}

object MapDownloadManager {

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

    /**
     * 特定の都県の地図ファイルをダウンロードして保存する。
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
        val downloadUrl = customUrl ?: info.downloadUrl

        try {
            var address = URL(downloadUrl)
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null

            // リダイレクト追従（最大5回）
            repeat(6) {
                require(address.protocol == "https" || address.protocol == "http") { "無効なURLプロトコルです" }
                val conn = (address.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                }
                val status = conn.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location") ?: throw IllegalStateException("リダイレクト先がありません")
                    conn.disconnect()
                    address = URL(address, location)
                } else if (status == 200) {
                    connection = conn
                    inputStream = conn.inputStream
                    return@repeat
                } else {
                    conn.disconnect()
                    throw IllegalStateException("ダウンロードに失敗しました (HTTP $status)")
                }
            }

            val stream = inputStream ?: throw IllegalStateException("接続を確立できませんでした")
            val totalLength = connection?.contentLengthLong?.takeIf { it > 0 } ?: (info.approximateSizeMb * 1024L * 1024L)

            try {
                partFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesCopied = 0L
                    var lastPercent = -1

                    while (true) {
                        val bytes = stream.read(buffer)
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
            } finally {
                connection?.disconnect()
            }

            // PMTiles ヘッダー検証
            validateMapFile(partFile)

            // アトミックに本番配置
            Files.move(partFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            onProgress(100)

            MapDownloadResult.Success(target, target.length())
        } catch (e: Exception) {
            partFile.delete()
            MapDownloadResult.Failure(e.message ?: "ダウンロードエラー")
        }
    }

    /**
     * 都県の地図ファイルを削除して空き容量を確保する。
     */
    fun deletePrefecture(context: Context, info: PrefectureMapInfo): Boolean {
        val file = KantoPrefectures.getFile(context, info)
        if (file.exists()) {
            return file.delete()
        }
        return false
    }

    /**
     * 保存されている地図の合計サイズ（バイト）
     */
    fun getTotalDownloadedSizeBytes(context: Context): Long {
        val downloaded = KantoPrefectures.getDownloadedList(context)
        return downloaded.sumOf { KantoPrefectures.getFile(context, it).length() }
    }
}
