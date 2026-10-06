package com.example.offlinetransitmap

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object DataFileIO {
    // 完成したファイルを同じディレクトリに用意し、検証後にだけ公開する。
    fun stage(input: InputStream, target: File, size: Long, sha256: String, progress: (Long) -> Unit = {}): File {
        val part = File(target.parentFile, target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            input.use { source ->
                require(size > 0 && sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "配布ファイルのサイズ・ハッシュが不正です" }
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= size) { "配布ファイルのサイズが一致しません" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        progress(total)
                    }
                    require(total == size) { "ダウンロードが途中で終了しました" }
                    output.fd.sync()
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(actual.equals(sha256, ignoreCase = true)) { "配布ファイルの検証に失敗しました" }
            return part
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    fun commit(part: File, target: File) {
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun download(url: String, target: File, size: Long, sha256: String, progress: (Long) -> Unit): File {
        var address = URL(url)
        repeat(6) {
            require(address.protocol == "https") { "配布URLにはHTTPSを指定してください" }
            val connection = address.openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    address = URL(address, requireNotNull(connection.getHeaderField("Location")))
                } else {
                    check(status == 200) { "ダウンロードに失敗しました（HTTP $status）" }
                    return stage(connection.inputStream, target, size, sha256, progress)
                }
            } finally {
                connection.disconnect()
            }
        }
        error("配布URLの転送回数が多すぎます")
    }
}
