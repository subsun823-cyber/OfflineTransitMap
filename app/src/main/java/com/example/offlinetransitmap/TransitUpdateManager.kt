package com.example.offlinetransitmap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class TransitRemoteFile(
    val id: String,
    val name: String,
    val url: String,
    val compressed: String?,
    val size: Long,
    val sha256: String,
    val uncompressedSize: Long,
    val uncompressedSha256: String,
    val validityText: String,
    val tripCount: Int
)

data class TransitManifest(
    val version: String,
    val updatedAt: String,
    val file: TransitRemoteFile
)

sealed interface UpdateCheckResult {
    data class Available(val manifest: TransitManifest, val currentHash: String?) : UpdateCheckResult
    data class UpToDate(val manifest: TransitManifest) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

sealed interface UpdateApplyResult {
    data class Success(val version: String, val validityText: String, val tripCount: Int) : UpdateApplyResult
    data class Failure(val message: String) : UpdateApplyResult
}

internal object TransitUpdateManager {
    const val DEFAULT_MANIFEST_URL = "https://github.com/subsun823-cyber/OfflineTransitMap/releases/download/transit-data-latest/transit-manifest.json"
    const val FALLBACK_RAW_MANIFEST_URL = "https://raw.githubusercontent.com/subsun823-cyber/OfflineTransitMap/master/app/src/main/assets/bootstrap/transit-manifest.json"

    fun currentDatabaseHash(context: Context): String? {
        val file = currentDatabaseFile(context) ?: return null
        if (!file.isFile || file.length() == 0L) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun currentDatabaseFile(context: Context): File? {
        val dir = context.getExternalFilesDir("timetable") ?: return null
        return File(dir, "timetable.db")
    }

    suspend fun check(context: Context, customManifestUrl: String? = null): UpdateCheckResult = withContext(Dispatchers.IO) {
        val manifestUrl = customManifestUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_MANIFEST_URL
        try {
            val content = fetchManifestWithFallback(manifestUrl)
            val json = JSONObject(content)
            val version = json.getString("version")
            val updatedAt = json.optString("updated_at")
            val filesArray = json.getJSONArray("files")
            var timetableSpec: TransitRemoteFile? = null
            for (i in 0 until filesArray.length()) {
                val obj = filesArray.getJSONObject(i)
                if (obj.getString("id") == "timetable") {
                    timetableSpec = TransitRemoteFile(
                        id = obj.getString("id"),
                        name = obj.optString("name", "時刻表データ"),
                        url = obj.getString("url"),
                        compressed = obj.optString("compressed", "").takeIf { it.isNotBlank() },
                        size = obj.getLong("size"),
                        sha256 = obj.getString("sha256"),
                        uncompressedSize = obj.optLong("uncompressed_size", 0L),
                        uncompressedSha256 = obj.optString("uncompressed_sha256", ""),
                        validityText = obj.optString("validity_text", ""),
                        tripCount = obj.optInt("trip_count", 0)
                    )
                    break
                }
            }
            if (timetableSpec == null) {
                return@withContext UpdateCheckResult.Error("マニフェストに時刻表ファイル情報が含まれていません")
            }
            val manifest = TransitManifest(version, updatedAt, timetableSpec)
            val localHash = currentDatabaseHash(context)
            val expectedHash = timetableSpec.uncompressedSha256.ifBlank { timetableSpec.sha256 }
            if (localHash != null && localHash.equals(expectedHash, ignoreCase = true)) {
                UpdateCheckResult.UpToDate(manifest)
            } else {
                UpdateCheckResult.Available(manifest, localHash)
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "更新情報の確認に失敗しました")
        }
    }

    suspend fun downloadAndApply(
        context: Context,
        spec: TransitRemoteFile,
        version: String,
        progress: (String, Int) -> Unit
    ): UpdateApplyResult = withContext(Dispatchers.IO) {
        val target = currentDatabaseFile(context) ?: return@withContext UpdateApplyResult.Failure("保存先を開けません")
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "timetable.update.part")
        try {
            progress("ダウンロード中…", 0)
            downloadStream(spec.url, spec.size) { rawStream, totalSize ->
                val finalStream: InputStream = if (spec.compressed == "gzip") GZIPInputStream(rawStream) else rawStream
                val digest = MessageDigest.getInstance("SHA-256")
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastPercent = -1
                    while (true) {
                        val count = finalStream.read(buffer)
                        if (count < 0) break
                        written += count
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        val expectedUncompressed = spec.uncompressedSize.takeIf { it > 0 } ?: totalSize
                        val percent = if (expectedUncompressed > 0) ((written * 100.0) / expectedUncompressed).toInt().coerceIn(0, 99) else 50
                        if (percent != lastPercent) {
                            progress("展開・検証中… $percent%", percent)
                            lastPercent = percent
                        }
                    }
                    output.fd.sync()
                }
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                val targetSha256 = spec.uncompressedSha256.ifBlank { spec.sha256 }
                check(actualSha256.equals(targetSha256, ignoreCase = true)) {
                    "ダウンロードファイルのハッシュ照合に失敗しました（期待値: $targetSha256, 実測値: $actualSha256）"
                }
            }

            progress("データベースを検証中…", 99)
            KeioDatabase.validate(part)
            val wal = File(target.path + "-wal")
            check(!wal.exists() || wal.length() == 0L) { "時刻表DBに未保存の変更があります。アプリを終了してから再度お試しください" }

            progress("データベースを更新中…", 100)
            DataFileIO.commit(part, target)
            UpdateApplyResult.Success(version, spec.validityText, spec.tripCount)
        } catch (e: Exception) {
            part.delete()
            UpdateApplyResult.Failure(e.message ?: "データベースの更新に失敗しました")
        } finally {
            part.delete()
        }
    }

    fun bundledManifest(context: Context): TransitManifest? {
        return try {
            val content = context.assets.open("bootstrap/transit-manifest.json").bufferedReader().use { it.readText() }
            val json = JSONObject(content)
            val version = json.getString("version")
            val updatedAt = json.optString("updated_at")
            val filesArray = json.getJSONArray("files")
            val obj = filesArray.getJSONObject(0)
            val fileSpec = TransitRemoteFile(
                id = obj.getString("id"),
                name = obj.optString("name", "時刻表データ"),
                url = obj.getString("url"),
                compressed = obj.optString("compressed", "").takeIf { it.isNotBlank() },
                size = obj.getLong("size"),
                sha256 = obj.getString("sha256"),
                uncompressedSize = obj.optLong("uncompressed_size", 0L),
                uncompressedSha256 = obj.optString("uncompressed_sha256", ""),
                validityText = obj.optString("validity_text", ""),
                tripCount = obj.optInt("trip_count", 0)
            )
            TransitManifest(version, updatedAt, fileSpec)
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchManifestWithFallback(manifestUrl: String): String {
        return try {
            fetchUrlText(manifestUrl)
        } catch (e: Exception) {
            if (manifestUrl == DEFAULT_MANIFEST_URL) {
                try {
                    fetchUrlText(FALLBACK_RAW_MANIFEST_URL)
                } catch (_: Exception) {
                    throw e
                }
            } else {
                throw e
            }
        }
    }

    private fun fetchUrlText(urlString: String): String {
        var address = URL(urlString)
        repeat(5) {
            val conn = address.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "OfflineTransitMap-Android/1.1")
            try {
                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    address = URL(address, requireNotNull(conn.getHeaderField("Location")))
                } else if (code == 200) {
                    return conn.inputStream.bufferedReader().use { it.readText() }
                } else if (code == 404) {
                    error("配布サーバーに最新データがまだ公開されていません（GitHub Actionsの初回実行またはRelease作成が必要です: HTTP 404）")
                } else {
                    error("マニフェストの取得に失敗しました (HTTP $code)")
                }
            } finally {
                conn.disconnect()
            }
        }
        error("リダイレクト回数が上限を超えました")
    }

    private fun <T> downloadStream(urlString: String, expectedDownloadSize: Long, block: (InputStream, Long) -> T): T {
        var address = URL(urlString)
        repeat(6) {
            val conn = address.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "OfflineTransitMap-Android/1.1")
            try {
                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    address = URL(address, requireNotNull(conn.getHeaderField("Location")))
                } else if (code == 200) {
                    val size = conn.contentLengthLong.takeIf { it > 0 } ?: expectedDownloadSize
                    return block(conn.inputStream, size)
                } else if (code == 404) {
                    error("時刻表データファイルがサーバーに見つかりません（Releaseアセット未作成: HTTP 404）")
                } else {
                    error("ダウンロードに失敗しました (HTTP $code)")
                }
            } finally {
                conn.disconnect()
            }
        }
        error("リダイレクト回数が上限を超えました")
    }
}
