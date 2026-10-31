package dev.indicvoice.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Small HTTP client on HttpURLConnection (works on Android and the JVM without extra libraries). */
object Http {

    class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
        val ok: Boolean get() = status in 200..299
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    }

    fun interface DownloadProgress { fun onProgress(bytes: Long, total: Long) }

    fun request(
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20000,
    ): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = method
            conn.connectTimeout = minOf(timeoutMs, 10000)
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json, audio/wav, */*")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val hdrs = LinkedHashMap<String, String>()
            for ((k, v) in conn.headerFields) if (k != null && v != null && v.isNotEmpty()) hdrs[k] = v.last()
            return Response(status, hdrs, bytes)
        } finally {
            conn.disconnect()
        }
    }

    fun getText(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): String {
        val r = request("GET", url, headers = headers, timeoutMs = timeoutMs)
        if (!r.ok) throw VoiceException(ErrorCode.NETWORK, "GET $url -> HTTP ${r.status}")
        return r.text
    }

    /**
     * Downloads to [dest], resuming a partial file if the server supports ranges.
     * Returns the final size. Verifies sha256 when given.
     */
    fun download(url: String, dest: File, expectedSha256: String? = null, progress: DownloadProgress? = null, timeoutMs: Int = 60000): Long {
        dest.parentFile?.mkdirs()
        var attempt = 0
        while (true) {
            attempt++
            try {
                val existing = if (dest.exists()) dest.length() else 0L
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", USER_AGENT)
                if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
                val status = conn.responseCode
                if (status == 416) { conn.disconnect(); break } // already complete
                if (status !in 200..299) { conn.disconnect(); throw VoiceException(ErrorCode.NETWORK, "GET $url -> HTTP $status") }
                val resuming = status == 206 && existing > 0
                val contentLen = conn.contentLengthLong
                val total = if (contentLen > 0) (if (resuming) existing + contentLen else contentLen) else -1L
                RandomAccessFile(dest, "rw").use { raf ->
                    if (resuming) raf.seek(existing) else raf.setLength(0)
                    var done = if (resuming) existing else 0L
                    val buf = ByteArray(1 shl 16)
                    conn.inputStream.use { ins ->
                        while (true) {
                            val r = ins.read(buf); if (r < 0) break
                            raf.write(buf, 0, r); done += r
                            progress?.onProgress(done, total)
                        }
                    }
                    if (total > 0 && done != total) throw IOException("Short download: $done of $total bytes")
                }
                conn.disconnect()
                break
            } catch (e: IOException) {
                if (attempt >= 3) throw VoiceException(ErrorCode.NETWORK, "Download failed: ${e.message}", cause = e)
            }
        }
        if (expectedSha256 != null && expectedSha256.isNotBlank()) {
            val actual = sha256(dest)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                dest.delete()
                throw VoiceException(ErrorCode.PACK_CORRUPT, "sha256 mismatch for ${dest.name}: expected $expectedSha256 got $actual")
            }
        }
        return dest.length()
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 16).use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val r = ins.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Encodes a multipart/form-data body. */
    class Multipart {
        private val boundary = "----IndicVoice" + System.nanoTime()
        private val out = ByteArrayOutputStream()
        val contentType: String get() = "multipart/form-data; boundary=$boundary"
        fun field(name: String, value: String): Multipart {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8)); return this
        }
        fun file(name: String, filename: String, contentType: String, data: ByteArray): Multipart {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\nContent-Type: $contentType\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.write(data); out.write("\r\n".toByteArray()); return this
        }
        fun bytes(): ByteArray { out.write("--$boundary--\r\n".toByteArray()); return out.toByteArray() }
    }

    const val USER_AGENT = "IndicVoiceSDK/0.1"
}
