package dev.indicvoice.core

import java.io.IOException
import java.io.InputStream

/**
 * Pure-Kotlin bzip2 decompressor (no dependencies), so model packs can be
 * downloaded straight from GitHub releases (which only publish .tar.bz2)
 * on Android/JVM without pulling in commons-compress.
 *
 * Implements the standard format: block Huffman tables, MTF/RLE2, inverse BWT,
 * RLE1 and CRC verification. Randomised blocks (an obsolete feature) are rejected.
 */
class BZip2InputStream(private val input: InputStream) : InputStream() {

    private var bitBuffer = 0L
    private var bitCount = 0
    private var blockSize100k = 0
    private var streamDone = false
    private var headerRead = false

    // Current block output state
    private var tt = IntArray(0)
    private var blockLoaded = false
    private var tPos = 0
    private var remainingInBlock = 0
    private var blockCrcExpected = 0
    private var crc = 0xFFFFFFFF.toInt()
    private var combinedCrc = 0

    // RLE1 state
    private var lastByte = -1
    private var runCount = 0
    private var pendingRepeat = 0

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n <= 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var written = 0
        while (written < len) {
            val v = nextByte()
            if (v < 0) break
            b[off + written] = v.toByte()
            written++
        }
        return if (written == 0) -1 else written
    }

    /** Produces the next decoded byte after RLE1, or -1 at end of stream. */
    private fun nextByte(): Int {
        if (pendingRepeat > 0) { pendingRepeat--; updateCrc(lastByte); return lastByte }
        while (true) {
            if (remainingInBlock == 0) {
                if (!finishBlockAndStartNext()) return -1
            }
            val raw = tt[tPos] and 0xFF
            tPos = tt[tPos] ushr 8
            remainingInBlock--
            if (runCount == 4) {
                // After four identical bytes the next byte is a repeat count.
                pendingRepeat = raw
                runCount = 0
                if (pendingRepeat > 0) { pendingRepeat--; updateCrc(lastByte); return lastByte }
                continue
            }
            if (raw == lastByte) runCount++ else { runCount = 1; lastByte = raw }
            updateCrc(raw)
            return raw
        }
    }

    private fun updateCrc(byte: Int) {
        crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor byte) and 0xFF]
    }

    private fun finishBlockAndStartNext(): Boolean {
        if (blockLoaded) {
            val actual = crc.inv()
            if (actual != blockCrcExpected) throw IOException("bzip2 block CRC mismatch")
            combinedCrc = ((combinedCrc shl 1) or (combinedCrc ushr 31)) xor actual
            blockLoaded = false
        }
        if (streamDone) return false
        if (!headerRead) readStreamHeader()
        val magic = readBits(24).toLong() shl 24 or readBits(24).toLong()
        when (magic) {
            BLOCK_MAGIC -> { decodeBlock(); return true }
            EOS_MAGIC -> {
                val storedCombined = readBits(32)
                if (storedCombined != combinedCrc) throw IOException("bzip2 stream CRC mismatch")
                streamDone = true
                return false
            }
            else -> throw IOException("bzip2: bad block magic")
        }
    }

    private fun readStreamHeader() {
        val b = readBits(8); val z = readBits(8); val h = readBits(8); val level = readBits(8)
        if (b != 'B'.code || z != 'Z'.code || h != 'h'.code || level !in '1'.code..'9'.code) throw IOException("Not a bzip2 stream")
        blockSize100k = level - '0'.code
        headerRead = true
    }

    private fun decodeBlock() {
        blockCrcExpected = readBits(32)
        crc = 0xFFFFFFFF.toInt()
        lastByte = -1; runCount = 0; pendingRepeat = 0
        if (readBits(1) == 1) throw IOException("bzip2: randomised blocks are not supported")
        val origPtr = readBits(24)

        // Symbol map
        val inUse16 = readBits(16)
        val seqToUnseq = ByteArray(256)
        var nInUse = 0
        for (i in 0 until 16) {
            if (inUse16 and (1 shl (15 - i)) != 0) {
                val bits = readBits(16)
                for (j in 0 until 16) if (bits and (1 shl (15 - j)) != 0) seqToUnseq[nInUse++] = (i * 16 + j).toByte()
            }
        }
        if (nInUse == 0) throw IOException("bzip2: block uses no symbols")
        val alphaSize = nInUse + 2

        val nGroups = readBits(3)
        if (nGroups < 2 || nGroups > 6) throw IOException("bzip2: bad number of Huffman groups")
        val nSelectors = readBits(15)
        if (nSelectors < 1) throw IOException("bzip2: bad number of selectors")

        // Selectors (MTF coded)
        val selectorMtf = ByteArray(nSelectors)
        for (i in 0 until nSelectors) {
            var j = 0
            while (readBits(1) == 1) { j++; if (j >= nGroups) throw IOException("bzip2: bad selector") }
            selectorMtf[i] = j.toByte()
        }
        val pos = ByteArray(nGroups) { it.toByte() }
        val selectors = ByteArray(nSelectors)
        for (i in 0 until nSelectors) {
            var v = selectorMtf[i].toInt()
            val tmp = pos[v]
            while (v > 0) { pos[v] = pos[v - 1]; v-- }
            pos[0] = tmp
            selectors[i] = tmp
        }

        // Code lengths per group
        val len = Array(nGroups) { IntArray(alphaSize) }
        for (t in 0 until nGroups) {
            var curr = readBits(5)
            for (i in 0 until alphaSize) {
                while (true) {
                    if (curr < 1 || curr > 20) throw IOException("bzip2: bad code length")
                    if (readBits(1) == 0) break
                    if (readBits(1) == 0) curr++ else curr--
                }
                len[t][i] = curr
            }
        }

        // Huffman decode tables
        val limit = Array(nGroups) { IntArray(MAX_CODE_LEN + 2) }
        val base = Array(nGroups) { IntArray(MAX_CODE_LEN + 2) }
        val perm = Array(nGroups) { IntArray(alphaSize) }
        val minLens = IntArray(nGroups)
        for (t in 0 until nGroups) {
            var minLen = 32; var maxLen = 0
            for (i in 0 until alphaSize) { if (len[t][i] > maxLen) maxLen = len[t][i]; if (len[t][i] < minLen) minLen = len[t][i] }
            createDecodeTables(limit[t], base[t], perm[t], len[t], minLen, maxLen, alphaSize)
            minLens[t] = minLen
        }

        // MTF / RLE2 decode into tt
        val limitSize = blockSize100k * 100000
        if (tt.size < limitSize) tt = IntArray(limitSize)
        val unzftab = IntArray(256)
        val mtf = ByteArray(256) { it.toByte() }
        val eob = nInUse + 1
        var groupNo = -1; var groupPos = 0
        var gSel = 0; var gMinLen = 0; var gLimit = limit[0]; var gBase = base[0]; var gPerm = perm[0]
        var nblock = 0

        fun nextSymbol(): Int {
            if (groupPos == 0) {
                groupNo++
                if (groupNo >= nSelectors) throw IOException("bzip2: selector overflow")
                groupPos = GROUP_SIZE
                gSel = selectors[groupNo].toInt()
                gMinLen = minLens[gSel]; gLimit = limit[gSel]; gBase = base[gSel]; gPerm = perm[gSel]
            }
            groupPos--
            var zn = gMinLen
            var zvec = readBits(zn)
            while (zvec > gLimit[zn]) {
                zn++
                if (zn > MAX_CODE_LEN) throw IOException("bzip2: bad Huffman code")
                zvec = (zvec shl 1) or readBits(1)
            }
            val idx = zvec - gBase[zn]
            if (idx < 0 || idx >= alphaSize) throw IOException("bzip2: bad Huffman symbol")
            return gPerm[idx]
        }

        var nextSym = nextSymbol()
        while (true) {
            if (nextSym == eob) break
            if (nextSym == RUNA || nextSym == RUNB) {
                var es = -1; var n = 1
                do {
                    if (nextSym == RUNA) es += n else es += 2 * n
                    n *= 2
                    if (n >= 2 * 1024 * 1024) throw IOException("bzip2: run too long")
                    nextSym = nextSymbol()
                } while (nextSym == RUNA || nextSym == RUNB)
                es++
                val uc = seqToUnseq[mtf[0].toInt() and 0xFF].toInt() and 0xFF
                unzftab[uc] += es
                if (nblock + es > limitSize) throw IOException("bzip2: block overflow")
                while (es > 0) { tt[nblock++] = uc; es-- }
                continue
            }
            if (nblock >= limitSize) throw IOException("bzip2: block overflow")
            var nn = nextSym - 1
            val tmp = mtf[nn]
            while (nn > 0) { mtf[nn] = mtf[nn - 1]; nn-- }
            mtf[0] = tmp
            val uc = seqToUnseq[tmp.toInt() and 0xFF].toInt() and 0xFF
            unzftab[uc]++
            tt[nblock++] = uc
            nextSym = nextSymbol()
        }

        if (origPtr < 0 || origPtr >= nblock) throw IOException("bzip2: bad origPtr")

        // Inverse BWT
        val cftab = IntArray(257)
        for (i in 0 until 256) cftab[i + 1] = cftab[i] + unzftab[i]
        for (i in 0 until nblock) {
            val uc = tt[i] and 0xFF
            tt[cftab[uc]] = tt[cftab[uc]] or (i shl 8)
            cftab[uc]++
        }
        tPos = tt[origPtr] ushr 8
        remainingInBlock = nblock
        blockLoaded = true
    }

    private fun createDecodeTables(limit: IntArray, base: IntArray, perm: IntArray, length: IntArray, minLen: Int, maxLen: Int, alphaSize: Int) {
        var pp = 0
        for (i in minLen..maxLen) for (j in 0 until alphaSize) if (length[j] == i) perm[pp++] = j
        for (i in 0 until MAX_CODE_LEN + 2) { base[i] = 0; limit[i] = 0 }
        for (i in 0 until alphaSize) base[length[i] + 1]++
        for (i in 1 until MAX_CODE_LEN + 2) base[i] += base[i - 1]
        var vec = 0
        for (i in minLen..maxLen) {
            vec += base[i + 1] - base[i]
            limit[i] = vec - 1
            vec = vec shl 1
        }
        for (i in minLen + 1..maxLen) base[i] = ((limit[i - 1] + 1) shl 1) - base[i]
    }

    private fun readBits(n: Int): Int {
        while (bitCount < n) {
            val b = input.read()
            if (b < 0) throw IOException("bzip2: unexpected end of stream")
            bitBuffer = (bitBuffer shl 8) or (b.toLong() and 0xFF)
            bitCount += 8
        }
        bitCount -= n
        return ((bitBuffer ushr bitCount) and ((1L shl n) - 1)).toInt()
    }

    override fun close() { input.close() }

    companion object {
        private const val BLOCK_MAGIC = 0x314159265359L
        private const val EOS_MAGIC = 0x177245385090L
        private const val MAX_CODE_LEN = 20
        private const val GROUP_SIZE = 50
        private const val RUNA = 0
        private const val RUNB = 1
        private val CRC_TABLE = IntArray(256).also { t ->
            for (i in 0 until 256) {
                var c = i shl 24
                for (k in 0 until 8) c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1
                t[i] = c
            }
        }
    }
}
