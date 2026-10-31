# Client Voice SDK 🎙️ — speech ↔ text for India

Speech‑to‑text (STT) and text‑to‑speech (TTS) for apps in India: **22 scheduled Indian languages + Indian English**,
one Kotlin core (Java‑friendly) with a React Native wrapper, usable on Android, iOS, desktop and web.

The idea in one line: **use the free engine already on the phone, fall back to our own on‑device models, and only
then to a hosted service — through your backend, never with vendor keys in the app.**

| Tier | What answers | Cost | Where |
|---|---|---|---|
| **1 · OS** | Android `SpeechRecognizer` / `TextToSpeech`, Apple Speech / `AVSpeechSynthesizer`, the browser's Web Speech API | free | the device |
| **2 · On‑device** | open models we download on demand and run with [sherpa‑onnx](https://github.com/k2-fsa/sherpa-onnx) (Dolphin, Whisper, Omnilingual; Kokoro and Piper voices) | free | the device (or a laptop / server) |
| **3 · Hosted** | *your* backend behind a small neutral HTTP contract; the reference backend here can call Sarvam AI or run tier 2 server‑side | pay per use | your server |

Whatever tier answers, you get the same JSON: **the detected language, the words, the text, timings and which engine
was used** (see [Output](#5-output-format)).

> **Status (31 Oct 2026):** SDK, reference backend, laptop test kit and accuracy tooling work and are tested on desktop
> (`docs/TEST-REPORT.md`). The phone apps are written and compile‑checked but still need their first device build.
> On‑device Hindi accuracy on *real* voices is the open problem — read [`docs/STT-ACCURACY.md`](docs/STT-ACCURACY.md).

---

## Contents

1. [Try it on a laptop (5 minutes)](#1-try-it-on-a-laptop-5-minutes)
2. [Repository layout](#2-repository-layout)
3. [Phone test app](#3-phone-test-app-react-native--expo)
4. [Use the SDK in your app](#4-use-the-sdk-in-your-app)
5. [Output format](#5-output-format)
6. [Configuration — no code changes](#6-configuration--no-code-changes)
7. [Model packs](#7-model-packs)
8. [Testing](#8-testing)
9. [Known limits and next steps](#9-known-limits-and-next-steps)
10. [Credits and licences](#10-credits-and-licences)

---

## 1. Try it on a laptop (5 minutes)

**macOS, no setup:** double‑click **`START.command`**. It (re)starts the local server and opens the test site at
<http://localhost:8080/m>. It uses `./jre` and `./packs` if you have the prepared test kit; otherwise it needs Java 17+
(`brew install openjdk@21`) and downloads the default models (~710 MB) on first run. **`STOP.command`** stops the server.

**Any OS, from a terminal:**

```bash
scripts/fetch-sherpa.sh        # native sherpa-onnx libraries (once)
scripts/build-jvm.sh           # builds build/indicvoice-cli.jar + build/indicvoice-proxy.jar (needs kotlinc; CI also builds them)
bin/start-msite.sh             # downloads default model packs, starts the server, opens http://localhost:8080/m
```

What the test site (`/m`) gives you:

* **Engine tabs** — Tier 1 (browser/OS engine), Tier 2 (on‑device models on this laptop), Tier 3 (any backend URL you
  type in Settings, with optional token and paths), **All** (runs them side by side).
* **SPEAK** — speak and see the detected language, the text, every word with its timing, and the latency.
  In **Auto**, the language is identified from the audio itself (English → English model, Hindi → Hindi model, …).
* **Text → speech** — type, pick a voice (4 Hindi + 11 English Kokoro voices, the browser's own voices), listen.
* **Accuracy test — your voice** — read 15 prompted sentences; the server scores every installed model on your
  recordings (accuracy / WER / CER / language right / speed). This is how to compare models for real.

Command‑line tools (`bin/indicvoice --help`): `caps`, `packs list|install|remove`, `stt --file|--mic`, `live`, `tts`,
`bench` (one recording through every tier), `roundtrip` (TTS → STT with WER), `voices`.

```bash
bin/indicvoice packs install dolphin-small-int8
bin/indicvoice stt --mic --lang auto
bin/indicvoice tts --text "आपका स्वागत है" --lang hi --play
bin/indicvoice bench --file my.wav --backend http://localhost:8080 --ref "what I said"
```

## 2. Repository layout

```
client-voice-sdk/
├── START.command / STOP.command   macOS laptop test kit (double-click)
├── config/          voice-config.json (behaviour) + catalog.json (downloadable model packs)   ← edit, no code change
├── sdk-core/        Kotlin, zero dependencies: tier routing, audio language ID routing, language detection, packs,
│                    hosted client, benchmark, WAV/PCM, JSON
├── sdk-sherpa/      tier-2 engines on sherpa-onnx 1.13.8: STT (Dolphin, Omnilingual, Whisper, NeMo), TTS (Kokoro, Piper/VITS), VAD, LID
├── sdk-android/     tier-1 Android engines, microphone/speaker, IndicVoiceAndroid.create()
├── sdk-jvm/         desktop platform + CLI (bin/indicvoice)
├── proxy/           reference tier-3 backend (bin/indicvoice-proxy): /v1/stt, /v1/tts, /v1/voices, /v1/lid, /v1/eval,
│                    web console (/) and test site (/m); CONTRACT.md is the API your own backend implements
├── apps/VoiceTestApp/   Expo (React Native) test app + the `indic-voice` native module (Kotlin + Swift)
├── third_party/     sherpa-onnx Kotlin/Swift bindings (Apache-2.0); native libraries are downloaded by scripts
├── scripts/         build-jvm.sh, fetch-sherpa.sh, run-tests.sh, package.sh, browser tests
├── docs/            STT-ACCURACY.md, TEST-REPORT.md, REVIEW-NOTES.md, DESKTOP-QUICKSTART.md
├── CREDITS.md       every model / library / service we use, with authors, links and licences
└── .github/workflows/   builds the Android APK + desktop jars on every push
```

Not in git (see `.gitignore`): build output, downloaded models (`packs/`), the bundled Java runtime (`jre/`),
native libraries, recordings, logs and keys.

## 3. Phone test app (React Native / Expo)

`apps/VoiceTestApp` shows per‑tier status, lets you choose tier 1/2/3, download model packs, speak → see language + words,
type → hear speech, and benchmark one recording across all tiers. Ways to get it on a phone (no Android Studio needed for 1–2):

1. **GitHub Actions** — every push builds `app-debug.apk`; download it from the run's *Artifacts* (`VoiceTestApp-debug-apk`).
2. **EAS Build** — `cd apps/VoiceTestApp && npm install && npx eas build -p android --profile preview`.
3. **Locally** — `npm install && npx expo run:android` / `npx expo run:ios`.

The backend URL (tier 3) is optional: type it in the app, or set `EXPO_PUBLIC_VOICE_BACKEND_URL` in `.env` (see `ENV.md`).
Use your laptop's Wi‑Fi IP (`http://192.168.x.x:8080`) so the phone can reach the local server.

## 4. Use the SDK in your app

### Android (Kotlin)

```kotlin
val voice = IndicVoiceAndroid.create(context)                 // reads assets/voice-config.json + catalog.json
voice.setBackend("https://api.example.com/voice", token)     // optional tier 3; the token comes from YOUR auth
voice.setFeatureEnabled(Feature.TTS, user.wantsVoiceReplies)  // per-user switch

// Live microphone recognition — tier chosen by policy, automatic fallback
val session = voice.startLive(SttOptions(language = null /* auto */), object : LiveSttListener {
    override fun onPartial(text: String, language: DetectedLanguage?) { /* show while speaking */ }
    override fun onFinal(r: TranscriptResult) {
        // r.language.language.code = "hi", r.language.script = Deva, r.words, r.text, r.tier, r.latencyMs
        val reply = myBackend.answer(r.text)
        voice.speak(TtsOptions(language = r.language.language, text = reply))   // answer in the same language
    }
    override fun onError(e: VoiceException) { /* e.code: NO_ENGINE, PERMISSION_DENIED, PACK_MISSING, NETWORK, … */ }
})
session.stop()

// One recording, and the same recording through every tier
val r = voice.transcribe(Wav.read(file), SttOptions())
val report = voice.benchmark(audio, SttOptions(), listOf(Tier.OS, Tier.ON_DEVICE, Tier.HOSTED), reference = "…")
```

Packs: `voice.packs.listStatus()`, `voice.packs.install("dolphin-small-int8") { id, phase, bytes, total -> }`.
`voice.capabilities(Language.TAMIL)` tells you what each tier can do before you start.
Add `sdk-core`, `sdk-sherpa`, `sdk-android` (Gradle modules) plus the sherpa‑onnx AAR (`scripts/fetch-sherpa.sh`).
Permission: `RECORD_AUDIO`.

### React Native / Expo

Copy `apps/VoiceTestApp/modules/indic-voice` into your project's `modules/` (and `scripts/fetch-sherpa.js` into `postinstall`):

```ts
import IndicVoice from './modules/indic-voice';
await IndicVoice.configure({ backendUrl: process.env.EXPO_PUBLIC_VOICE_BACKEND_URL });
const sub = IndicVoice.addListener('onFinal', (r) => console.log(r.language.code, r.words.map(w => w.text)));
await IndicVoice.startListening({ language: 'auto' });          // or { tier: 2, packId: 'dolphin-small-int8' }
await IndicVoice.speak({ text: 'धन्यवाद', language: 'hi' });
```

Methods: `modules/indic-voice/src/IndicVoiceModule.ts`; types: `IndicVoice.types.ts`.

### iOS (Swift, without React Native)

`apps/VoiceTestApp/modules/indic-voice/ios/IndicVoiceCore.swift` + `IndicVoiceEngine.swift` implement the same contract
(Apple Speech, AVSpeechSynthesizer, sherpa‑onnx xcframework, hosted). Written without Xcode at hand —
`ios/IOS_NOTES.md` is the first‑build checklist.

### Your own backend (tier 3)

Implement [`proxy/CONTRACT.md`](proxy/CONTRACT.md): `POST /v1/stt` (WAV in → the JSON below), `POST /v1/tts` (JSON in → WAV),
`GET /v1/health`, `GET /v1/voices`; optional `POST /v1/lid`. Or run the reference backend:
`bin/indicvoice-proxy` (env: `SARVAM_API_KEY`, `PROXY_TOKEN`, `PORT` — see `ENV.md`).

## 5. Output format

```json
{
  "language": { "code": "hi", "bcp47": "hi-IN", "name": "Hindi", "nativeName": "हिन्दी", "script": "Deva", "confidence": 0.9, "source": "lid" },
  "text": "मेरा नाम मयंक है और मैं दिल्ली में रहता हूं।",
  "words": [ { "text": "मेरा", "startMs": 120, "endMs": 420 }, … ],
  "wordCount": 10, "tier": 2, "tierLabel": "tier2-on-device", "engine": "sherpa:dolphin-small-int8",
  "latencyMs": 164, "audioMs": 3157, "rtf": 0.05, "isFinal": true
}
```

`language.source` says how the language was decided: `lid` (identified from the audio), `engine` (the engine said so),
`script` (only one language uses that script), `stopwords` (function words, for shared scripts), `hint` (you passed it),
`none`. TTS returns 16‑bit PCM / WAV plus `voiceId`, `tier`, `engine`, `latencyMs`.

## 6. Configuration — no code changes

* **`config/voice-config.json`** — features on/off, default/allowed languages, tier policy (`DEVICE_FIRST`, `DEVICE_ONLY`,
  `HOSTED_FIRST`, `HOSTED_ONLY`, `ORDER`), live endpointing, **`stt.languageId`** (audio language ID for Auto),
  tier‑2 pack per language, tier‑3 paths/timeouts/auth header, TTS voice policy and preferred voices, logging.
  Ship it as an app asset, fetch it remotely, or pass a replacement at runtime (`configure({ configJson })`).
* **`config/catalog.json`** — downloadable model packs: id, engine, languages, sizes, licence, archive URL + sha256, files,
  voices. Point URLs at your CDN; add a pack by adding an entry (and a row in `CREDITS.md`).
* **`proxy/config.json`** (server) / **`proxy/config.laptop.json`** (laptop test kit: keeps more models loaded and saves
  the last 50 test recordings to `build/debug-audio` for debugging — don't use it on a shared server).
* Secrets never go in these files. The app injects backend URL/token (`setBackend`); the backend reads keys from env.

## 7. Model packs

Defaults: **phones** use `dolphin-base-int8` for Hindi (small and fast); **laptop/server** uses `dolphin-small-int8`.
English uses `whisper-base-en-int8`; other Indian languages use `omnilingual-300m-int8`; Auto identifies the language
with `whisper-base-int8` first. Download sizes are for the compressed archive.

| Pack | Kind | Languages | Download | Licence | Notes |
|---|---|---|---|---|---|
| `silero-vad` | VAD | – | 0.6 MB | MIT | end‑of‑speech detection |
| `dolphin-base-int8` | STT | hi + 14 Indian | 77 MB | Apache‑2.0 | phone default for Hindi; fast (RTF ≈ 0.05) |
| `dolphin-small-int8` | STT | hi + 14 Indian | 183 MB | Apache‑2.0 | laptop/server default for Hindi; more accurate |
| `omnilingual-300m-int8` | STT | all 22 + en | 279 MB | Apache‑2.0 | the only pack for sa, mai, kok, doi, brx, mni, sd |
| `whisper-base-en-int8` | STT | en | 199 MB | MIT | English |
| `whisper-base-int8` | STT / LID | 14 Indian + en | 198 MB | MIT | identifies the spoken language for Auto |
| `whisper-small-int8`, `whisper-turbo-int8` | STT | 14 Indian + en | 610 MB / 538 MB | MIT | most accurate Whisper; slow on CPU (server only) |
| `indicconformer-hi-int8` | STT | hi | – | MIT | AI4Bharat; planned — needs ONNX export (`scripts/export-indicconformer.md`) |
| `kokoro-int8-multilang` | TTS | hi, en | 126 MB | Apache‑2.0 | natural voices; laptop/server or flagship phones |
| `piper-hi-priyamvada` | TTS | hi | 64 MB | MIT + **CC BY‑NC‑SA data** | fast; testing only |
| `piper-ml-meera`, `mimic3-gu-cmu-indic` | TTS | ml, gu | 20 / 76 MB | see CREDITS.md | check dataset terms before commercial use |

Tier 1 covers what the packs don't: Android's Google TTS has offline voices for most Indian languages; Google/Apple
recognisers cover Hindi and Indian English (and more online).

## 8. Testing

* `scripts/run-tests.sh` → `docs/TEST-REPORT.md` — 39 desktop checks with literal outputs: core self‑test, TTS → STT round
  trips with WER, Auto language routing (English/Hindi/Malayalam/Gujarati), tier 2 vs tier 3, backend endpoints, live
  path, pack download + checksum, browser tests of the console and test site, Android + Expo module compile checks.
* **Accuracy on real voices:** the test site's *Accuracy test* card, or `POST /v1/eval` + `POST /v1/eval/run` — see
  [`docs/STT-ACCURACY.md`](docs/STT-ACCURACY.md) for method, results so far and the research plan.
* An independent code review (concurrency, fallback, lifecycle) is recorded in `docs/REVIEW-NOTES.md`.

## 9. Known limits and next steps

* **On‑device Hindi accuracy on real voices is not good enough yet.** Four causes were found and fixed (wrong routing,
  microphone resampling, two sherpa‑onnx Whisper bugs); the next candidate model is AI4Bharat IndicConformer.
  Until then, tier 1 (Google/Apple) and tier 3 (Sarvam) are the accurate options. Details: `docs/STT-ACCURACY.md`.
* Very short utterances (one word) are unreliable on every open model; pass the language when the app knows it.
* Tier‑3 live mode sends one request per utterance; a WebSocket path for partial results is next.
* Android tier‑1 batch transcription (benchmark button) needs Android 13+.
* sherpa‑onnx is pinned to 1.13.8 (2.x removed the espeak‑ng front end our TTS voices need); the Whisper work‑arounds go
  away when we can upgrade.
* Licence review needed before a commercial release: Piper Hindi voice data (non‑commercial) and espeak‑ng (GPL‑3.0).

## 10. Credits and licences

This project stands on open models and libraries — **Dolphin** (DataoceanAI), **Whisper** (OpenAI), **Omnilingual ASR**
(Meta FAIR), **Silero VAD**, **Kokoro‑82M** (hexgrad), **Piper** (Rhasspy) and **Mimic 3** voices, **sherpa‑onnx** (k2‑fsa),
**ONNX Runtime**, **espeak‑ng**, **React Native / Expo** — and optionally **Sarvam AI**'s hosted APIs.
[`CREDITS.md`](CREDITS.md) lists every one with authors, links, licences and citations; keep it up to date when you add a model.

The licence for this repository's own code has not been chosen yet — add a `LICENSE` file before sharing it outside the team.
