package com.example.offlinetransitmap

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal object BusUpdateDownload {
    data class Download(val hash: String, val etag: String, val modified: String)
    fun validUrl(value: String): Boolean = runCatching {
        val url=URL(value)
        url.protocol=="https" && url.host.isNotBlank() && url.userInfo==null && url.ref==null
    }.getOrDefault(false)
    fun hash(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(65536);while(true) { val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun fetch(url: String, output: File, etag: String, modified: String, openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }, check: () -> Unit): Download? {
        require(validUrl(url)) { "GTFS本体のHTTPS URLを設定してください" }
        var address=URL(url)
        repeat(6) {
            require(validUrl(address.toString())) { "HTTPS以外の転送先は使用できません" }
            val connection=openConnection(address)
            connection.connectTimeout=20_000; connection.readTimeout=30_000; connection.instanceFollowRedirects=false
            connection.setRequestProperty("Accept-Encoding","identity")
            if (etag.isNotEmpty()) connection.setRequestProperty("If-None-Match",etag)
            if (modified.isNotEmpty()) connection.setRequestProperty("If-Modified-Since",modified)
            try {
                check()
                when(val status=connection.responseCode) {
                    301,302,303,307,308 -> address=URL(address,requireNotNull(connection.getHeaderField("Location")))
                    304 -> { require(etag.isNotEmpty() || modified.isNotEmpty());return null }
                    200 -> {
                        val expected=connection.contentLengthLong
                        require(expected<=64L*1024*1024) { "GTFS ZIPが上限を超えています" }
                        var size=0L
                        connection.inputStream.use { input -> output.outputStream().use { target ->
                            val buffer=ByteArray(65536)
                            while(true) {
                                check();val n=input.read(buffer);if(n<0)break
                                size+=n;require(size<=64L*1024*1024) { "GTFS ZIPが上限を超えています" };target.write(buffer,0,n)
                            }
                            require(size>0 && (expected<0 || expected==size)) { "ダウンロードが途中で終了しました" };target.fd.sync()
                        } }
                        return Download(hash(output),connection.getHeaderField("ETag").orEmpty(),connection.getHeaderField("Last-Modified").orEmpty())
                    }
                    else -> error("GTFSを取得できません（HTTP $status）")
                }
            } finally { connection.disconnect() }
        }
        error("転送回数が多すぎます")
    }
}
