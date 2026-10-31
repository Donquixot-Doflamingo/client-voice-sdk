package dev.indicvoice.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16-bit mono PCM audio. All engines in the SDK speak this type; platform code
 * converts to/from whatever the OS wants.
 */
class PcmAudio(val samples: ShortArray, val sampleRate: Int) {

    val durationMs: Long get() = if (sampleRate <= 0) 0 else samples.size * 1000L / sampleRate

    fun toFloatArray(): FloatArray {
        val out = FloatArray(samples.size)
        for (i in samples.indices) out[i] = samples[i] / 32768f
        return out
    }

    /** Root-mean-square level in dBFS. Useful to tell silence from speech in tests. */
    fun rmsDb(): Double {
        if (samples.isEmpty()) return -120.0
        var acc = 0.0
        for (s in samples) { val f = s / 32768.0; acc += f * f }
        val rms = Math.sqrt(acc / samples.size)
        return if (rms <= 1e-9) -120.0 else 20 * Math.log10(rms)
    }

    fun resampled(targetRate: Int): PcmAudio {
        if (targetRate == sampleRate || samples.isEmpty()) return if (targetRate == sampleRate) this else PcmAudio(ShortArray(0), targetRate)
        val ratio = sampleRate.toDouble() / targetRate
        val n = (samples.size / ratio).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val i1 = minOf(i0 + 1, samples.size - 1)
            val frac = pos - i0
            out[i] = (samples[i0] * (1 - frac) + samples[i1] * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
        return PcmAudio(out, targetRate)
    }

    fun slice(startSample: Int, endSample: Int): PcmAudio =
        PcmAudio(samples.copyOfRange(startSample.coerceIn(0, samples.size), endSample.coerceIn(0, samples.size)), sampleRate)

    fun toWavBytes(): ByteArray = Wav.encode(this)

    fun writeWav(file: File) { file.parentFile?.mkdirs(); file.writeBytes(toWavBytes()) }

    fun toByteArrayLE(): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putShort(s)
        return bb.array()
    }

    companion object {
        fun fromFloat(samples: FloatArray, sampleRate: Int): PcmAudio {
            val out = ShortArray(samples.size)
            for (i in samples.indices) out[i] = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            return PcmAudio(out, sampleRate)
        }

        fun fromBytesLE(bytes: ByteArray, sampleRate: Int): PcmAudio {
            val n = bytes.size / 2
            val out = ShortArray(n)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) out[i] = bb.getShort()
            return PcmAudio(out, sampleRate)
        }

        fun silence(ms: Int, sampleRate: Int = 16000) = PcmAudio(ShortArray(sampleRate * ms / 1000), sampleRate)

        fun concat(parts: List<PcmAudio>, sampleRate: Int): PcmAudio {
            val total = parts.sumOf { it.resampled(sampleRate).samples.size }
            val out = ShortArray(total)
            var pos = 0
            for (p in parts) { val r = p.resampled(sampleRate); System.arraycopy(r.samples, 0, out, pos, r.samples.size); pos += r.samples.size }
            return PcmAudio(out, sampleRate)
        }
    }
}

/** Minimal RIFF/WAVE reader and writer (PCM 16-bit, mono or stereo→mono). */
object Wav {
    fun encode(audio: PcmAudio): ByteArray {
        val dataLen = audio.samples.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII)); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII)); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(audio.sampleRate); bb.putInt(audio.sampleRate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII)); bb.putInt(dataLen)
        for (s in audio.samples) bb.putShort(s)
        return bb.array()
    }

    fun decode(bytes: ByteArray): PcmAudio {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(): String { val b = ByteArray(4); bb.get(b); return String(b, Charsets.US_ASCII) }
        if (bytes.size < 12 || tag() != "RIFF") throw VoiceException(ErrorCode.BAD_AUDIO, "Not a RIFF file")
        bb.getInt(); if (tag() != "WAVE") throw VoiceException(ErrorCode.BAD_AUDIO, "Not a WAVE file")
        var channels = 1; var rate = 16000; var bits = 16; var format = 1
        var data: ByteArray? = null
        while (bb.remaining() >= 8) {
            val id = tag(); val len = bb.getInt()
            val chunkLen = if (len < 0 || len > bb.remaining()) bb.remaining() else len
            when (id) {
                "fmt " -> {
                    val start = bb.position()
                    format = bb.getShort().toInt(); channels = bb.getShort().toInt(); rate = bb.getInt(); bb.getInt(); bb.getShort(); bits = bb.getShort().toInt()
                    bb.position(start + chunkLen)
                }
                "data" -> { data = ByteArray(chunkLen); bb.get(data); }
                else -> bb.position(bb.position() + chunkLen)
            }
            if (chunkLen % 2 == 1 && bb.remaining() > 0) bb.get()
            if (data != null) break
        }
        val d = data ?: throw VoiceException(ErrorCode.BAD_AUDIO, "WAV has no data chunk")
        if (format != 1 && format != 0xFFFE) throw VoiceException(ErrorCode.BAD_AUDIO, "Only PCM WAV is supported (format=$format)")
        val frames = d.size / (channels * bits / 8)
        val out = ShortArray(frames)
        val db = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) {
                acc += when (bits) {
                    16 -> db.getShort().toInt()
                    8 -> (db.get().toInt() and 0xFF) - 128 shl 8
                    24 -> { val b0 = db.get().toInt() and 0xFF; val b1 = db.get().toInt() and 0xFF; val b2 = db.get().toInt(); (b2 shl 16 or (b1 shl 8) or b0) shr 8 }
                    32 -> db.getInt() shr 16
                    else -> throw VoiceException(ErrorCode.BAD_AUDIO, "Unsupported bit depth $bits")
                }
            }
            out[f] = (acc / channels).coerceIn(-32768, 32767).toShort()
        }
        return PcmAudio(out, rate)
    }

    fun read(file: File): PcmAudio = decode(file.readBytes())

    fun read(stream: InputStream): PcmAudio {
        val bos = ByteArrayOutputStream(); stream.copyTo(bos); return decode(bos.toByteArray())
    }
}
