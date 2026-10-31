package dev.indicvoice.jvm

import dev.indicvoice.core.DetectedLanguage
import dev.indicvoice.core.Feature
import dev.indicvoice.core.IndicVoice
import dev.indicvoice.core.Json
import dev.indicvoice.core.Language
import dev.indicvoice.core.LiveSttListener
import dev.indicvoice.core.Logger
import dev.indicvoice.core.Metrics
import dev.indicvoice.core.PcmAudio
import dev.indicvoice.core.Segmenter
import dev.indicvoice.core.SttOptions
import dev.indicvoice.core.Tier
import dev.indicvoice.core.TranscriptResult
import dev.indicvoice.core.TtsOptions
import dev.indicvoice.core.VoiceConfig
import dev.indicvoice.core.VoiceException
import dev.indicvoice.core.Wav
import dev.indicvoice.core.EnergyVad
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Desktop test CLI. Lets you exercise tiers 2 and 3 (and tier-1 TTS on macOS)
 * on a laptop before touching a phone:
 *
 *   indicvoice caps
 *   indicvoice packs list | install <id> | remove <id>
 *   indicvoice stt --file hello.wav [--lang hi] [--tier 2] [--pack dolphin-base-int8] [--backend http://host:8080]
 *   indicvoice stt --mic [--seconds 8]
 *   indicvoice live [--tier 2]
 *   indicvoice tts --text "नमस्ते" --lang hi [--tier 2] [--voice pack:piper-hi-priyamvada] [--out out.wav] [--play]
 *   indicvoice bench --file hello.wav [--ref "reference text"] [--tiers 1,2,3]
 *   indicvoice roundtrip --text "..." --lang hi
 *   indicvoice voices [--lang hi]
 */
object Main {
    private val usage = """
        |indicvoice <command> [options]
        |
        |Commands:
        |  caps                       show what each tier can do on this machine
        |  packs list|install|remove  manage tier-2 model packs
        |  stt                        transcribe --file x.wav or --mic (language + words)
        |  live                       continuous microphone recognition (Ctrl+C to stop)
        |  tts                        synthesize --text "..." --lang hi (--out file.wav, --play)
        |  bench                      run one recording through every tier side by side
        |  roundtrip                  TTS -> STT loop for a sentence; prints WER
        |  voices                     list voices across tiers
        |
        |Global options:
        |  --config <file>   voice-config.json (default: ./config/voice-config.json)
        |  --packs-dir <dir> where packs live (default: ~/.indicvoice/packs)
        |  --backend <url>   tier-3 backend base URL (e.g. http://localhost:8080)
        |  --token <t>       bearer token for the backend
        |  --lang <code>     language hint (hi, ta, ... or auto)
        |  --tier <1|2|3>    force a tier
        |  --pack <id>       force a tier-2 pack
        |  --json            machine-readable output
        |  --verbose         debug logging
        """.trimMargin()

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty() || args[0] == "-h" || args[0] == "--help") { println(usage); return }
        val cmd = args[0]
        val opts = parse(args.drop(1))
        val log = Logger.stdout(if (opts.flag("verbose")) "debug" else "warn")
        val root = System.getProperty("indicvoice.root")?.let(::File)
        val configFile = opts["config"]?.let(::File) ?: listOfNotNull(
            root?.let { File(it, "config/voice-config.json") }, File("config/voice-config.json"), File("../config/voice-config.json"),
            File(System.getProperty("user.home"), ".indicvoice/voice-config.json")).firstOrNull { it.exists() }
        val config = if (configFile != null && configFile.exists()) VoiceConfig.load(configFile) else bundledConfig()
        val packsDir = opts["packs-dir"]?.let(::File) ?: System.getenv("INDICVOICE_PACKS_DIR")?.let(::File) ?: File(System.getProperty("user.home"), ".indicvoice/packs")
        val platform = JvmPlatform(packsDir, log)
        val voice = IndicVoice(config, platform)
        voice.setBackend(opts["backend"] ?: System.getenv("INDICVOICE_BACKEND_URL"), opts["token"] ?: System.getenv("INDICVOICE_BACKEND_TOKEN"))
        val json = opts.flag("json")
        try {
            when (cmd) {
                "caps" -> caps(voice, opts, json)
                "packs" -> packs(voice, opts)
                "stt" -> stt(voice, opts, json)
                "live" -> live(voice, opts)
                "tts" -> tts(voice, opts, json)
                "bench" -> bench(voice, opts, json)
                "roundtrip" -> roundtrip(voice, opts, json)
                "voices" -> voices(voice, opts, json)
                else -> { System.err.println("Unknown command '$cmd'\n"); println(usage); exitProcess(2) }
            }
        } catch (e: VoiceException) {
            if (json) println(Json.stringify(mapOf("error" to e.toJsonValue()), true)) else System.err.println("ERROR ${e.code}: ${e.message}")
            exitProcess(1)
        } finally { voice.close() }
    }

    /** Falls back to the voice-config.json + catalog.json bundled inside the jar (scripts/build-jvm.sh copies them). */
    private fun bundledConfig(): VoiceConfig {
        val cfgText = Main::class.java.getResourceAsStream("/indicvoice/voice-config.json")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return VoiceConfig()
        val catText = Main::class.java.getResourceAsStream("/indicvoice/catalog.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
        val map = HashMap(Json.parseObject(cfgText))
        if (catText != null) {
            @Suppress("UNCHECKED_CAST") val packs = HashMap(map["packs"] as? Map<String, Any?> ?: emptyMap())
            packs["catalogInline"] = Json.parseObject(catText)
            map["packs"] = packs
        }
        return VoiceConfig.fromMap(map)
    }

    // ---------------------------------------------------------------- commands

    private fun caps(voice: IndicVoice, opts: Opts, json: Boolean) {
        val lang = Language.parse(opts["lang"])
        val caps = voice.capabilities(lang)
        if (json) { println(Json.stringify(caps, true)); return }
        println("Platform: ${caps["platform"]}   backend: ${(caps["backendUrl"] as String).ifBlank { "(none)" }}   language: ${caps["language"]}")
        for (kind in listOf("stt", "tts")) {
            @Suppress("UNCHECKED_CAST") val k = caps[kind] as Map<String, Any?>
            println("\n${kind.uppercase()}  policy=${k["policy"]}  order=${k["order"]}")
            @Suppress("UNCHECKED_CAST") val tiers = k["tiers"] as Map<String, Map<String, Any?>>
            for ((_, t) in tiers) {
                @Suppress("UNCHECKED_CAST") val av = t["availability"] as Map<String, Any?>
                val mark = if (av["available"] == true) "OK " else "-- "
                println("  $mark tier ${t["tier"]} (${t["label"]})  engine=${t["engine"] ?: "-"}  ${av["reason"] ?: ""}")
            }
        }
        println("\nPacks (${voice.packs.storageDir}):")
        for (p in voice.packs.listStatus()) {
            @Suppress("UNCHECKED_CAST") val spec = p["spec"] as Map<String, Any?>
            println("  ${if (p["installed"] == true) "[x]" else "[ ]"} ${p["id"]}  ${spec["kind"]}/${spec["engine"]}  ${spec["sizeMb"]} MB  langs=${(spec["languages"] as List<*>).joinToString(",")}")
        }
    }

    private fun packs(voice: IndicVoice, opts: Opts) {
        when (opts.positional.getOrNull(0) ?: "list") {
            "list" -> for (p in voice.packs.listStatus()) {
                @Suppress("UNCHECKED_CAST") val spec = p["spec"] as Map<String, Any?>
                println("${if (p["installed"] == true) "[x]" else "[ ]"} ${p["id"].toString().padEnd(26)} ${spec["kind"].toString().padEnd(4)} ${spec["engine"].toString().padEnd(16)} ${spec["sizeMb"].toString().padStart(5)} MB  ${spec["license"]}")
            }
            "install" -> {
                val id = opts.positional.getOrNull(1) ?: throw VoiceException(dev.indicvoice.core.ErrorCode.CONFIG_ERROR, "packs install <id>")
                var lastPct = -1
                voice.packs.install(id) { _, phase, b, t ->
                    val pct = if (t > 0) (b * 100 / t).toInt() else -1
                    if (pct != lastPct || phase == "done") { lastPct = pct; System.err.print("\r$phase ${if (pct >= 0) "$pct%" else "${b / 1048576} MB"}   ") }
                }
                System.err.println("\ninstalled $id -> ${voice.packs.dirFor(id)}")
            }
            "remove" -> { val id = opts.positional.getOrNull(1)!!; println(if (voice.packs.delete(id)) "removed $id" else "nothing to remove") }
            else -> println("packs list | install <id> | remove <id>")
        }
    }

    private fun sttOptions(opts: Opts) = SttOptions(
        language = Language.parse(opts["lang"]),
        tier = opts["tier"]?.toIntOrNull()?.let(Tier::of),
        packId = opts["pack"],
        keyterms = opts["keyterms"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
    )

    private fun stt(voice: IndicVoice, opts: Opts, json: Boolean) {
        val audio = loadOrRecord(voice, opts)
        val r = voice.transcribe(audio, sttOptions(opts))
        printTranscript(r, json)
    }

    private fun loadOrRecord(voice: IndicVoice, opts: Opts): PcmAudio {
        opts["file"]?.let { return Wav.read(File(it)) }
        if (!opts.flag("mic")) throw VoiceException(dev.indicvoice.core.ErrorCode.CONFIG_ERROR, "give --file <wav> or --mic")
        return recordUtterance(voice, opts["seconds"]?.toIntOrNull() ?: 10)
    }

    /** Records from the microphone until the VAD hears an end of speech (or maxSeconds). */
    private fun recordUtterance(voice: IndicVoice, maxSeconds: Int): PcmAudio {
        val mic = JvmMicrophone(16000)
        val latch = CountDownLatch(1)
        var segment: PcmAudio? = null
        val seg = Segmenter(EnergyVad(), 16000, voice.config.stt.live.endpointSilenceMs, maxSeconds * 1000, voice.config.stt.live.minSpeechMs,
            onSpeechStart = { System.err.println("… speech detected") },
            onSegment = { s -> if (segment == null) { segment = s; latch.countDown() } })
        System.err.println("Listening (speak now; stops after ${voice.config.stt.live.endpointSilenceMs} ms of silence, max ${maxSeconds}s)…")
        mic.start { frame -> seg.accept(frame) }
        latch.await((maxSeconds + 2).toLong(), TimeUnit.SECONDS)
        mic.stop(); seg.flush()
        val audio = segment ?: throw VoiceException(dev.indicvoice.core.ErrorCode.BAD_AUDIO, "No speech detected")
        File("last-recording.wav").also { audio.writeWav(it); System.err.println("saved ${it.absolutePath} (${audio.durationMs} ms)") }
        return audio
    }

    private fun printTranscript(r: TranscriptResult, json: Boolean) {
        if (json) { println(r.toJson(true)); return }
        val l = r.language
        println("language : ${l.language.englishName} (${l.language.code}, script ${l.script.iso15924}, confidence ${"%.2f".format(l.confidence)}, via ${l.source})")
        println("text     : ${r.text}")
        println("words    : ${r.words.joinToString(" | ") { it.text }}")
        println("tier     : ${r.tier.number} (${r.engine})   latency ${r.latencyMs} ms for ${r.audioMs} ms audio   RTF ${"%.3f".format(r.rtf)}")
    }

    private fun live(voice: IndicVoice, opts: Opts) {
        val latch = CountDownLatch(1)
        val session = voice.startLive(sttOptions(opts), object : LiveSttListener {
            override fun onReady() = System.err.println("Listening… (Ctrl+C to stop)")
            override fun onSpeechStart() = System.err.println("… speech")
            override fun onSpeechEnd() = System.err.println("… end, transcribing")
            override fun onPartial(text: String, language: DetectedLanguage?) = System.err.println("   partial: $text")
            override fun onFinal(result: TranscriptResult) { printTranscript(result, false); println() }
            override fun onTierChanged(tier: Tier, reason: String) = System.err.println("tier -> ${tier.number}: $reason")
            override fun onError(error: VoiceException) = System.err.println("error ${error.code}: ${error.message}")
            override fun onStopped() = latch.countDown()
        })
        Runtime.getRuntime().addShutdownHook(Thread { session.stop() })
        latch.await()
    }

    private fun tts(voice: IndicVoice, opts: Opts, json: Boolean) {
        val text = opts["text"] ?: throw VoiceException(dev.indicvoice.core.ErrorCode.CONFIG_ERROR, "--text is required")
        val lang = Language.parse(opts["lang"]) ?: voice.config.defaultLanguage
        val r = voice.synthesize(TtsOptions(language = lang, text = text, tier = opts["tier"]?.toIntOrNull()?.let(Tier::of), voiceId = opts["voice"], packId = opts["pack"], speed = opts["speed"]?.toDoubleOrNull() ?: 1.0))
        val out = File(opts["out"] ?: "tts-${lang.code}.wav")
        r.audio.writeWav(out)
        if (json) println(Json.stringify(r.toJsonValue().let { (it as Map<*, *>) + mapOf("file" to out.absolutePath) }, true))
        else println("tier ${r.tier.number} (${r.engine}) voice=${r.voiceId}  ${r.audio.durationMs} ms of audio in ${r.latencyMs} ms  -> ${out.absolutePath}")
        if (opts.flag("play")) { val done = CountDownLatch(1); JvmSpeaker().play(r.audio) { done.countDown() }; done.await() }
    }

    private fun bench(voice: IndicVoice, opts: Opts, json: Boolean) {
        val audio = loadOrRecord(voice, opts)
        val tiers = opts["tiers"]?.split(',')?.mapNotNull { it.trim().toIntOrNull()?.let(Tier::of) } ?: Tier.entries.toList()
        val report = voice.benchmark(audio, sttOptions(opts), tiers, opts["ref"])
        if (json) { println(Json.stringify(report, true)); return }
        println("Audio: ${report["audioMs"]} ms @ ${report["sampleRate"]} Hz, level ${report["rmsDb"]} dBFS" + (opts["ref"]?.let { "   reference: $it" } ?: ""))
        @Suppress("UNCHECKED_CAST") val rows = report["tiers"] as List<Map<String, Any?>>
        for (row in rows) {
            val head = "tier ${row["tier"]} ${row["label"]}".padEnd(24)
            if (row["ok"] != true) { println("$head  n/a: ${row["reason"] ?: (row["error"] as? Map<*, *>)?.get("message")}"); continue }
            @Suppress("UNCHECKED_CAST") val r = row["result"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST") val l = r["language"] as Map<String, Any?>
            val acc = if (row["wer"] != null) "  WER ${row["wer"]}  CER ${row["cer"]}" else ""
            println("$head  ${r["latencyMs"]} ms (RTF ${r["rtf"]})  lang=${l["code"]}/${l["script"]} (${l["source"]})$acc")
            println("".padEnd(26) + r["text"])
        }
    }

    private fun roundtrip(voice: IndicVoice, opts: Opts, json: Boolean) {
        val text = opts["text"] ?: "मुझे एक गिलास पानी चाहिए और कल सुबह दिल्ली जाना है"
        val lang = Language.parse(opts["lang"]) ?: Language.HINDI
        val tts = voice.synthesize(TtsOptions(language = lang, text = text, tier = opts["tts-tier"]?.toIntOrNull()?.let(Tier::of), voiceId = opts["voice"]))
        val sttTier = opts["stt-tier"]?.toIntOrNull()?.let(Tier::of) ?: opts["tier"]?.toIntOrNull()?.let(Tier::of)
        val stt = voice.transcribe(tts.audio, SttOptions(language = if (opts.flag("auto")) null else lang, tier = sttTier, packId = opts["pack"]))
        val wer = Metrics.wer(text, stt.text); val cer = Metrics.cer(text, stt.text)
        if (json) { println(Json.stringify(mapOf("input" to text, "tts" to tts.toJsonValue(), "stt" to stt.toJsonValue(), "wer" to wer, "cer" to cer), true)); return }
        println("input : $text")
        println("tts   : tier ${tts.tier.number} ${tts.engine} voice=${tts.voiceId}  ${tts.audio.durationMs} ms audio in ${tts.latencyMs} ms")
        println("stt   : tier ${stt.tier.number} ${stt.engine}  ${stt.latencyMs} ms  lang=${stt.language.language.code}/${stt.language.script.iso15924} (${stt.language.source})")
        println("output: ${stt.text}")
        println("WER ${"%.2f".format(wer)}   CER ${"%.2f".format(cer)}")
    }

    private fun voices(voice: IndicVoice, opts: Opts, json: Boolean) {
        val vs = voice.voices(Language.parse(opts["lang"]))
        if (json) { println(Json.stringify(vs.map { it.toJsonValue() }, true)); return }
        for (v in vs) println("tier ${v.tier.number}  ${v.id.padEnd(40)} ${v.language.code.padEnd(4)} ${v.name}${if (!v.installed) "  (not downloaded)" else ""}")
    }

    // ---------------------------------------------------------------- args

    class Opts(val map: Map<String, String>, val flags: Set<String>, val positional: List<String>) {
        operator fun get(k: String): String? = map[k]
        fun flag(k: String) = k in flags || map[k] == "true"
    }

    private fun parse(args: List<String>): Opts {
        val map = HashMap<String, String>(); val flags = HashSet<String>(); val pos = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--")) {
                val key = a.removePrefix("--")
                if (key.contains('=')) { map[key.substringBefore('=')] = key.substringAfter('=') }
                else if (i + 1 < args.size && !args[i + 1].startsWith("--")) { map[key] = args[i + 1]; i++ }
                else flags += key
            } else pos += a
            i++
        }
        return Opts(map, flags, pos)
    }
}
