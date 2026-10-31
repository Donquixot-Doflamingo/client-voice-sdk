# Client Voice SDK 🎙️

Speech‑to‑text (STT) and text‑to‑speech (TTS) for apps in India: **22 scheduled Indian languages + Indian English**.
Kotlin core (works from Java), Android, desktop JVM, and a Swift implementation for iOS.

**Get started:** `./scripts/setup.sh` — builds and tests everything on your machine in one command. Full guide: **[SETUP.md](SETUP.md)**.

## How it works

The SDK answers every request from the cheapest engine that can do it, and falls back automatically:

| Tier | What answers | Cost | Where |
|---|---|---|---|
| **1 · OS** | Android `SpeechRecognizer` / `TextToSpeech`, Apple Speech / `AVSpeechSynthesizer` | free | the device |
| **2 · On‑device** | open models downloaded on demand and run with [sherpa‑onnx](https://github.com/k2-fsa/sherpa-onnx): Dolphin, Whisper, Omnilingual (STT); Kokoro, Piper (voices) | free | the device |
| **3 · Hosted** | **your** backend, through a small neutral HTTP contract ([docs/BACKEND-CONTRACT.md](docs/BACKEND-CONTRACT.md)) — no vendor keys or formats in the app | pay per use | your server |

Whatever tier answers, the result has the same shape — **the detected language, the words, the text, timings, and
which engine was used**:

```json
{
  "language": { "code": "hi", "bcp47": "hi-IN", "name": "Hindi", "script": "Deva", "confidence": 0.9, "source": "lid" },
  "text": "मेरा नाम मयंक है और मैं दिल्ली में रहता हूं।",
  "words": [ { "text": "मेरा", "startMs": 120, "endMs": 420 } ],
  "wordCount": 10, "tier": 2, "engine": "sherpa:dolphin-small-int8", "latencyMs": 164, "audioMs": 3157, "isFinal": true
}
```

`language.source`: `lid` = identified from the audio, `engine` = the engine reported it, `script` = only one language
uses that script, `stopwords` = function words (shared scripts), `hint` = you passed it.

**Auto language:** with no language given, the SDK first identifies the spoken language from the audio (multilingual
Whisper), then transcribes with the right model — English → Whisper base.en, Hindi/Urdu → Dolphin, other Indian
languages → Omnilingual — and rejects answers written in a script that doesn't fit.

## Repository layout

```
client-voice-sdk/
├── SETUP.md          ← start here
├── scripts/          setup.sh (one command: tools → build → self-test → smoke test), build.sh, fetch-sherpa.sh, tools.sh
├── config/           voice-config.json (behaviour) + catalog.json (downloadable model packs) — change without code
├── sdk-core/         Kotlin, zero dependencies: tier routing, audio language ID routing, language detection,
│                     model packs (download, sha256, extract), backend client, benchmark, WAV/PCM, JSON
├── sdk-sherpa/       tier-2 engines on sherpa-onnx 1.13.8: STT, TTS, VAD, language identification
├── sdk-android/      tier-1 Android engines, microphone/speaker, IndicVoiceAndroid.create()
├── sdk-jvm/          desktop platform (microphone, speaker, macOS voices) + self-test and smoke test
├── sdk-ios/          Swift implementation of the same SDK for iOS
├── third_party/      sherpa-onnx Kotlin bindings (Apache-2.0); native binaries are downloaded by the scripts
├── docs/             BACKEND-CONTRACT.md, STT-ACCURACY.md, REVIEW-NOTES.md, EXPORT-INDICCONFORMER.md
└── CREDITS.md        every model, library and service used — authors, links, licences
```

Not in git: build output, `.tools/` (private JDK/kotlinc), native binaries, model packs, recordings.

## API at a glance (Kotlin)

```kotlin
val voice = IndicVoiceAndroid.create(context)                     // Android; desktop: IndicVoice(config, JvmPlatform(dir))
voice.setBackend("https://api.example.com/voice", token)         // optional tier 3; token from YOUR auth

val session = voice.startLive(SttOptions(), object : LiveSttListener {   // microphone, language auto-detected
    override fun onFinal(r: TranscriptResult) { /* r.language, r.words, r.text, r.tier */ }
    override fun onError(e: VoiceException) { /* NO_ENGINE, PERMISSION_DENIED, PACK_MISSING, NETWORK, … */ }
})
session.stop()

val r = voice.transcribe(audio, SttOptions(language = Language.HINDI))           // one recording
voice.speak(TtsOptions(language = r.language.language, text = "धन्यवाद"))         // answer in the same language
voice.benchmark(audio, SttOptions(), listOf(Tier.OS, Tier.ON_DEVICE, Tier.HOSTED)) // same audio through every tier
voice.packs.install("dolphin-small-int8") { id, phase, bytes, total -> }          // model packs on demand
voice.capabilities(Language.TAMIL)                                                // what each tier can do, before you start
```

## Model packs

Phones use small, fast packs; laptops/servers can use bigger ones. Every pack has a sha256 and is credited in
[CREDITS.md](CREDITS.md).

| Pack | Kind | Languages | Download | Licence |
|---|---|---|---|---|
| `silero-vad` | voice activity | – | 0.6 MB | MIT |
| `whisper-base-int8` | language ID (+ STT) | 14 Indian + en | 198 MB | MIT |
| `dolphin-base-int8` / `dolphin-small-int8` | STT | Hindi + 14 Indian | 77 / 183 MB | Apache‑2.0 |
| `whisper-base-en-int8` | STT | English | 199 MB | MIT |
| `omnilingual-300m-int8` | STT | all 22 + English | 279 MB | Apache‑2.0 |
| `whisper-small-int8` / `whisper-turbo-int8` | STT | 14 Indian + en | 610 / 538 MB | MIT |
| `kokoro-int8-multilang` | voices (4 Hindi, 11 English) | hi, en | 126 MB | Apache‑2.0 |
| `piper-hi-priyamvada` | voice | hi | 64 MB | MIT + **non‑commercial data** (testing only) |
| `piper-ml-meera`, `mimic3-gu-cmu-indic` | voices | ml, gu | 20 / 76 MB | see CREDITS.md |

## Status and known limits

* Desktop: built and tested by `scripts/setup.sh` (self‑test + Hindi TTS→STT smoke test) on Linux; CI runs it on
  Linux and macOS. Android: the module compiles against API 35; a full in‑app Gradle build is pending. iOS: written,
  not yet compiled in Xcode (`sdk-ios/README.md`).
* **On‑device Hindi accuracy on real voices still needs work** — causes found so far, measurements and the research
  plan (AI4Bharat IndicConformer next) are in [docs/STT-ACCURACY.md](docs/STT-ACCURACY.md). Tier 1 (Google/Apple) and
  tier 3 are the accurate options today.
* sherpa‑onnx is pinned to 1.13.8 (2.x removed the espeak‑ng front end the voices need); two Whisper bugs in 1.13.8
  are worked around in `sdk-sherpa` (`WhisperBytes`, output padding).
* Licence review needed before a commercial release: the Piper Hindi voice (non‑commercial training data) and
  espeak‑ng (GPL‑3.0, inside the voice packs). Details in [CREDITS.md](CREDITS.md).
* The licence for this repository's own code has not been chosen yet — add a `LICENSE` file.

## Credits

Built on **Dolphin** (DataoceanAI), **Whisper** (OpenAI), **Omnilingual ASR** (Meta FAIR), **Silero VAD**,
**Kokoro‑82M** (hexgrad), **Piper** (Rhasspy), **Mimic 3** (Mycroft AI), **sherpa‑onnx** (k2‑fsa), **ONNX Runtime** and
**espeak‑ng**. See [CREDITS.md](CREDITS.md) for authors, links, licences and citations.
