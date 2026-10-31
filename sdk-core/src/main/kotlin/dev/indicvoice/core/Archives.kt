package dev.indicvoice.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Extracts .tar, .tar.gz, .tar.bz2 and .zip archives using only the JDK plus [BZip2InputStream]. */
object Archives {

    fun interface Progress { fun onEntry(path: String, bytesSoFar: Long) }

    fun extract(archive: File, format: String, dest: File, stripTopDir: Boolean = true, progress: Progress? = null) {
        dest.mkdirs()
        archive.inputStream().buffered(1 shl 16).use { raw ->
            when (format) {
                "tar.bz2" -> extractTar(BZip2InputStream(raw), dest, stripTopDir, progress)
                "tar.gz" -> extractTar(GZIPInputStream(raw, 1 shl 16), dest, stripTopDir, progress)
                "tar" -> extractTar(raw, dest, stripTopDir, progress)
                "zip" -> extractZip(raw, dest, stripTopDir, progress)
                else -> throw VoiceException(ErrorCode.PACK_CORRUPT, "Unknown archive format '$format'")
            }
        }
    }

    private fun safeTarget(dest: File, entryName: String, stripTopDir: Boolean): File? {
        var name = entryName.replace('\\', '/').trimStart('/')
        if (name.isEmpty() || name.endsWith("/")) return null
        if (stripTopDir) {
            val slash = name.indexOf('/')
            if (slash < 0) return null // top-level file; with stripTopDir we expect everything under one dir
            name = name.substring(slash + 1)
            if (name.isEmpty()) return null
        }
        if (name.split('/').any { it == ".." }) throw VoiceException(ErrorCode.PACK_CORRUPT, "Unsafe path in archive: $entryName")
        val f = File(dest, name)
        if (!f.canonicalPath.startsWith(dest.canonicalPath + File.separator) && f.canonicalPath != dest.canonicalPath) {
            throw VoiceException(ErrorCode.PACK_CORRUPT, "Path escapes destination: $entryName")
        }
        return f
    }

    private fun extractZip(raw: InputStream, dest: File, stripTopDir: Boolean, progress: Progress?) {
        val zis = ZipInputStream(raw)
        var total = 0L
        while (true) {
            val e = zis.nextEntry ?: break
            if (!e.isDirectory) {
                val target = safeTarget(dest, e.name, stripTopDir)
                if (target != null) {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { out -> total += copy(zis, out) }
                    progress?.onEntry(e.name, total)
                }
            }
            zis.closeEntry()
        }
    }

    private fun extractTar(stream: InputStream, dest: File, stripTopDir: Boolean, progress: Progress?) {
        val header = ByteArray(512)
        var total = 0L
        var longName: String? = null
        var paxPath: String? = null
        while (true) {
            if (!readFully(stream, header, 512)) break
            if (header.all { it == 0.toByte() }) break
            var name = cstr(header, 0, 100)
            val size = parseOctal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val prefix = cstr(header, 345, 155)
            if (prefix.isNotEmpty()) name = "$prefix/$name"
            if (longName != null) { name = longName; longName = null }
            if (paxPath != null) { name = paxPath; paxPath = null }
            val padded = (size + 511) / 512 * 512
            when (type) {
                'L' -> { longName = readString(stream, size); skip(stream, padded - size) }
                'x', 'g' -> {
                    val pax = readString(stream, size); skip(stream, padded - size)
                    if (type == 'x') paxPath = parsePax(pax)["path"]
                }
                '0', '\u0000', '7' -> {
                    val target = safeTarget(dest, name, stripTopDir)
                    if (target == null) { skip(stream, padded) } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { out -> copyN(stream, out, size) }
                        skip(stream, padded - size)
                        total += size
                        progress?.onEntry(name, total)
                    }
                }
                else -> skip(stream, padded) // directories, symlinks, etc.
            }
        }
    }

    private fun parsePax(text: String): Map<String, String> {
        val m = HashMap<String, String>()
        var i = 0
        while (i < text.length) {
            val sp = text.indexOf(' ', i); if (sp < 0) break
            val len = text.substring(i, sp).toIntOrNull() ?: break
            val rec = text.substring(sp + 1, minOf(i + len, text.length)).trimEnd('\n')
            val eq = rec.indexOf('='); if (eq > 0) m[rec.substring(0, eq)] = rec.substring(eq + 1)
            i += len
            if (len <= 0) break
        }
        return m
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        val s = cstr(b, off, len).trim().trim('\u0000', ' ')
        if (s.isEmpty()) return 0
        if (b[off].toInt() and 0x80 != 0) { // base-256 encoding for big files
            var v = 0L
            for (i in 1 until len) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }
        return s.toLongOrNull(8) ?: throw IOException("Bad tar header number '$s'")
    }

    private fun readFully(s: InputStream, buf: ByteArray, n: Int): Boolean {
        var got = 0
        while (got < n) {
            val r = s.read(buf, got, n - got)
            if (r < 0) { if (got == 0) return false else throw IOException("Truncated tar header") }
            got += r
        }
        return true
    }

    private fun readString(s: InputStream, n: Long): String {
        val b = ByteArray(n.toInt()); readFully(s, b, n.toInt()); return String(b, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun skip(s: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) { val r = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) throw IOException("Truncated archive"); left -= r }
    }

    private fun copyN(s: InputStream, out: java.io.OutputStream, n: Long) {
        var left = n
        val buf = ByteArray(1 shl 16)
        while (left > 0) { val r = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) throw IOException("Truncated archive"); out.write(buf, 0, r); left -= r }
    }

    private fun copy(s: InputStream, out: java.io.OutputStream): Long {
        var total = 0L
        val buf = ByteArray(1 shl 16)
        while (true) { val r = s.read(buf); if (r < 0) break; out.write(buf, 0, r); total += r }
        return total
    }
}
