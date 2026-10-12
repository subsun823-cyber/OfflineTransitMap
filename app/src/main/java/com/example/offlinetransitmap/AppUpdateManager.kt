package com.example.offlinetransitmap

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class GitHubReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkFileName: String,
    val apkSize: Long,
    val isNewerThanCurrent: Boolean
)

sealed class AppUpdateCheckResult {
    data class UpdateAvailable(val info: GitHubReleaseInfo) : AppUpdateCheckResult()
    data class UpToDate(val currentVersion: String, val latestVersion: String) : AppUpdateCheckResult()
    data class Error(val message: String) : AppUpdateCheckResult()
}

sealed class AppDownloadResult {
    data class Success(val apkFile: File) : AppDownloadResult()
    data class Failure(val message: String) : AppDownloadResult()
}

object AppUpdateManager {
    private const val GITHUB_REPO_OWNER = "subsun823-cyber"
    private const val GITHUB_REPO_NAME = "OfflineTransitMap"
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"

    /**
     * 現在インストールされているアプリのバージョン名を取得
     */
    fun getCurrentVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.1"
        } catch (_: Exception) {
            "1.1"
        }
    }

    /**
     * GitHub Releases API を取得して最新リリース情報をチェックする。
     */
    suspend fun checkUpdate(context: Context, customApiUrl: String? = null): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        val targetUrl = customApiUrl?.trim()?.takeIf { it.isNotBlank() } ?: LATEST_RELEASE_API_URL
        var connection: HttpURLConnection? = null
        try {
            val url = URL(targetUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "OfflineTransitMap-AndroidApp")
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return@withContext AppUpdateCheckResult.Error("サーバーから応答コード $responseCode を受信しました。")
            }

            val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)

            val tagName = root.optString("tag_name", "")
            val releaseTitle = root.optString("name", tagName)
            val releaseNotes = root.optString("body", "")

            // assets から .apk を探す
            var apkUrl: String? = null
            var apkFileName = "OfflineTransitMap-latest.apk"
            var apkSize = 0L

            val assets = root.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        apkFileName = name
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (apkUrl.isNullOrBlank()) {
                return@withContext AppUpdateCheckResult.Error("最新リリースにAPKファイルが見つかりませんでした。")
            }

            val currentVersion = getCurrentVersionName(context)
            val cleanTagName = tagName.removePrefix("v").removePrefix("V")
            val isNewer = isVersionNewer(currentVersion, cleanTagName)

            val info = GitHubReleaseInfo(
                tagName = tagName,
                versionName = cleanTagName,
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                apkUrl = apkUrl,
                apkFileName = apkFileName,
                apkSize = apkSize,
                isNewerThanCurrent = isNewer
            )

            if (isNewer) {
                AppUpdateCheckResult.UpdateAvailable(info)
            } else {
                AppUpdateCheckResult.UpToDate(currentVersion, cleanTagName)
            }
        } catch (e: Exception) {
            AppUpdateCheckResult.Error("更新確認に失敗しました: ${e.localizedMessage ?: e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * バージョン番号比較（例: 1.2 > 1.1）
     */
    fun isVersionNewer(current: String, latest: String): Boolean {
        try {
            val currParts = current.split(".").mapNotNull { it.toIntOrNull() }
            val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(currParts.size, latestParts.size)
            for (i in 0 until maxLen) {
                val c = currParts.getOrElse(i) { 0 }
                val l = latestParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        } catch (e: Exception) {
            // パースに失敗した場合は文字列不一致で判定
            return current != latest
        }
    }

    /**
     * APKをダウンロードする。
     */
    suspend fun downloadApk(
        context: Context,
        info: GitHubReleaseInfo,
        onProgress: (percent: Int) -> Unit
    ): AppDownloadResult = withContext(Dispatchers.IO) {
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        val updatesDir = File(cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()

        val destFile = File(updatesDir, info.apkFileName)
        val tempFile = File(updatesDir, "${info.apkFileName}.tmp")
        if (tempFile.exists()) tempFile.delete()

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            var downloadUrl = info.apkUrl
            var redirectCount = 0

            // GitHub Releases はリダイレクト（S3など）されるため手動追跡
            while (redirectCount < 5) {
                val url = URL(downloadUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "OfflineTransitMap-AndroidApp")
                }

                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location != null) {
                        downloadUrl = location
                        redirectCount++
                        continue
                    }
                }
                break
            }

            val finalConn = connection ?: return@withContext AppDownloadResult.Failure("接続の初期化に失敗しました。")
            if (finalConn.responseCode !in 200..299) {
                return@withContext AppDownloadResult.Failure("ダウンロードサーバーの応答コード: ${finalConn.responseCode}")
            }

            val totalBytes = finalConn.contentLengthLong.let { if (it > 0) it else info.apkSize }
            inputStream = finalConn.inputStream
            outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(32 * 1024)
            var downloadedBytes = 0L
            var lastReportedPercent = -1

            while (true) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                outputStream.write(buffer, 0, read)
                downloadedBytes += read

                if (totalBytes > 0) {
                    val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                    if (percent != lastReportedPercent) {
                        lastReportedPercent = percent
                        onProgress(percent)
                    }
                }
            }
            outputStream.flush()
            outputStream.close()
            outputStream = null
            inputStream.close()
            inputStream = null

            if (destFile.exists()) destFile.delete()
            if (!tempFile.renameTo(destFile)) {
                return@withContext AppDownloadResult.Failure("ダウンロードファイルの配置に失敗しました。")
            }

            onProgress(100)
            AppDownloadResult.Success(destFile)
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            AppDownloadResult.Failure("ダウンロード中にエラーが発生しました: ${e.localizedMessage ?: e.message}")
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            connection?.disconnect()
        }
    }

    /**
     * システムのパッケージインストーラーを起動してAPKのインストールを行う。
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists() || !apkFile.isFile) {
            throw IllegalArgumentException("APKファイルが存在しません: ${apkFile.absolutePath}")
        }

        // Android 8.0 (API 26) 以上で不明なアプリのインストール権限があるか確認
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        }

        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(installIntent)
    }
}
