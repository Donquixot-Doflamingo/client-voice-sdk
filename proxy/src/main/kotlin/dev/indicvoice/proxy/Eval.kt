package dev.indicvoice.proxy

import dev.indicvoice.core.Json
import dev.indicvoice.core.Language
import dev.indicvoice.core.Logger
import dev.indicvoice.core.Metrics
import dev.indicvoice.core.PcmAudio
import dev.indicvoice.core.Wav
import java.io.File

/**
 * "Accuracy test with your own voice": the test site records prompted sentences
 * (clip + reference text), this class stores them and scores every installed STT
 * pack — and Auto mode — on them. Real speech from the real microphone is the only
 * fair way to compare models; synthetic TTS audio flatters all of them.
 */
class Evaluator(private val dir: File, private val local: LocalProvider, private val log: Logger) {

    data class Clip(val id: String, val language: Language, val reference: String, val audio: PcmAudio)

    fun save(audio: PcmAudio, language: Language, reference: String): Map<String, Any?> {
        dir.mkdirs()
        val id = String.format("%03d", (dir.listFiles { f -> f.name.endsWith(".wav") }?.size ?: 0) + 1) + "-" + language.code
        File(dir, "$id.wav").writeBytes(audio.toWavBytes())
        File(dir, "$id.txt").writeText(reference.trim())
        return mapOf("saved" to id, "count" to clips().size)
    }

    fun clips(): List<Clip> = dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }?.mapNotNull { wav ->
        val txt = File(dir, wav.name.removeSuffix(".wav") + ".txt").takeIf { it.exists() } ?: return@mapNotNull null
        val lang = Language.parse(wav.name.removeSuffix(".wav").substringAfter('-')) ?: return@mapNotNull null
        Clip(wav.name.removeSuffix(".wav"), lang, txt.readText().trim(), Wav.read(wav).resampled(16000))
    }.orEmpty()

    fun clear(): Map<String, Any?> {
        var n = 0
        dir.listFiles()?.forEach { if (it.name.endsWith(".wav") || it.name.endsWith(".txt")) { it.delete(); n++ } }
        return mapOf("deleted" to n)
    }

    /** Scores Auto mode and every installed pack that supports each clip's language. */
    fun run(onlyPacks: Set<String> = emptySet()): Map<String, Any?> {
        val clips = clips()
        val systems = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        // One system at a time over all clips, so each model is loaded once (they are big).
        val packs = clips.flatMap { local.installedSttPacks(it.language) }.distinct().filter { onlyPacks.isEmpty() || it in onlyPacks }
        for (pack in listOf<String?>(null) + packs) {
            val name = pack ?: "auto (language ID → routed pack)"
            for (c in clips) {
                if (pack != null && pack !in local.installedSttPacks(c.language)) continue
                val row = linkedMapOf<String, Any?>("clip" to c.id, "language" to c.language.code, "reference" to c.reference)
                try {
                    val t0 = System.nanoTime()
                    val r = local.transcribeWith(c.audio, if (pack == null) null else c.language, pack)
                    row["hypothesis"] = r.text
                    row["detected"] = r.language.language.code
                    row["engine"] = r.engine
                    row["wer"] = Metrics.wer(c.reference, r.text)
                    row["cer"] = Metrics.cer(c.reference, r.text)
                    row["ms"] = (System.nanoTime() - t0) / 1_000_000
                    row["audioMs"] = c.audio.durationMs
                } catch (e: Exception) { row["error"] = e.message ?: e.toString(); row["wer"] = 1.0; row["cer"] = 1.0 }
                systems.getOrPut(name) { ArrayList() } += row
                log.info("eval ${c.id} $name: wer=${row["wer"]} ${row["hypothesis"] ?: row["error"]}")
            }
        }
        val summary = systems.map { (name, rows) ->
            val byLang = rows.groupBy { it["language"] as String }.mapValues { (_, rs) -> agg(rs) }
            linkedMapOf("system" to name) + agg(rows) + mapOf("byLanguage" to byLang)
        }.sortedBy { (it["wer"] as Double) }
        return linkedMapOf("clips" to clips.size, "summary" to summary, "rows" to systems)
    }

    private fun agg(rows: List<Map<String, Any?>>): Map<String, Any?> {
        fun avg(k: String) = rows.mapNotNull { it[k] as? Double }.average().let { Math.round(it * 1000) / 1000.0 }
        val audio = rows.sumOf { (it["audioMs"] as? Long) ?: 0L }.coerceAtLeast(1)
        val ms = rows.sumOf { (it["ms"] as? Long) ?: 0L }
        val langOk = rows.count { it["detected"] == it["language"] }
        return linkedMapOf("n" to rows.size, "wer" to avg("wer"), "cer" to avg("cer"), "accuracy" to Math.round((1 - avg("wer")).coerceAtLeast(0.0) * 1000) / 10.0,
            "languageCorrect" to "$langOk/${rows.size}", "rtf" to Math.round(ms.toDouble() / audio * 1000) / 1000.0)
    }

    fun toJson(m: Map<String, Any?>) = Json.stringify(m)
}
