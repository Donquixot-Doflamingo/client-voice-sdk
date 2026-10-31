# iOS (Swift) — implementation notes

`Sources/` is the Swift implementation of the SDK (same behaviour and JSON as the Kotlin SDK). The internal test kit
wraps it in an Expo module (`IndicVoiceModule.swift`, `IndicVoice.podspec` live there, not in this repo); the notes
below were written for that first integration and still apply to any app that uses these sources.
It implements the same contract as the Kotlin module
(`src/IndicVoice.types.ts`, `src/IndicVoiceModule.ts`): same method names, argument shapes, result keys and events.
The code was written without Xcode available, so it has not been compiled yet; the list under "Verify in Xcode"
is the checklist for the first build.

## Files

| File | Role |
|------|------|
| `IndicVoice.podspec` | Pod definition: ExpoModulesCore dependency, vendored sherpa-onnx + onnxruntime xcframeworks, `bz2`/`c++` libs, bundled `voice-config.json` + `catalog.json` resources. |
| `SherpaOnnx.swift` | **Verbatim copy** of `sherpa-onnx/swift-api-examples/SherpaOnnx.swift` (1.13.8, Apache-2.0, header kept). Do not edit; re-copy when the runtime is bumped. |
| `BZip2Helper.h/.m` | `IVBZip2.decompressFile(at:to:)` — streaming bzip2 (64 KB chunks, multi-stream aware) over the system `libbz2`. The C loop was unit-tested on Linux against single/multi-stream, zero-padded, truncated and garbage inputs. |
| `IndicVoiceCore.swift` | Port of `sdk-core`: 23 languages, script + stopword language detection, `TranscriptResult`/`TtsResult`/`VoiceInfo`/`Availability` JSON, WER/CER, PCM + WAV, config + catalog parsing, pack manager (download, SHA-256, tar/tar.bz2), energy VAD + segmenter, hosted (tier 3) STT/TTS clients. |
| `IndicVoiceEngine.swift` | Apple Speech (tier 1 STT, live + file), Apple TTS (tier 1, `AVSpeechSynthesizer.write`), sherpa-onnx STT/TTS/VAD (tier 2), microphone capture, playback, and `VoiceSdk` (port of `IndicVoice.kt`: tier policy, capabilities, transcribe/benchmark, live sessions with fallback, synthesize). |
| `IndicVoiceModule.swift` | The Expo module. Every function takes a `Promise` and runs on the module's own concurrent queue so long operations (pack download, benchmark) never block Expo's shared async queue. |
| `Frameworks/` | Target of `apps/VoiceTestApp/scripts/fetch-sherpa.js`: `sherpa-onnx.xcframework` (module `SherpaOnnxC`) and `onnxruntime.xcframework`. Only `.gitkeep` is committed. |

## How the pieces fit

* **Config**: `voice-config.json` and `catalog.json` are referenced by the podspec straight from the repository's
  `config/` directory (five levels up from `ios/`) and copied into the app bundle by CocoaPods' resources phase.
  `ResourceLocator` looks in `Bundle(for: IndicVoiceModule.self)`, then `Bundle.main`, then an `IndicVoice.bundle`
  inside either, so both the default static-library build and `use_frameworks!` work. `packs.catalogInline:
  "./catalog.json"` resolves to the bundled `catalog.json`. `configure({ configJson })` replaces the whole config in
  memory (not persisted, unlike Android which writes a copy to `filesDir`).
* **Tier 1 STT** (`AppleSpeechEngine`): `SFSpeechRecognizer(locale:)` with the language's BCP-47 tag, falling back
  to any region of the same language (en-IN → en-US ...). Of the SDK's 23 languages Apple only has Hindi and English;
  the others report `available: false, reason: "Apple Speech has no …"`. Live sessions use an `AVAudioEngine` tap
  → `SFSpeechAudioBufferRecognitionRequest`; `requiresOnDeviceRecognition` is set when the recognizer supports it
  and `stt.tier1.preferOnDevice` is true (or `allowOnlineOsEngine` is false); `addsPunctuation` on iOS 16+;
  `keyterms` become `contextualStrings`. Apple never ends an utterance by itself, so the session ends after
  `stt.live.endpointSilenceMs` without a *changed* partial, after `maxUtteranceMs` of speech, or on `stopListening()`;
  it then calls `endAudio()` and waits up to 3 s for the final result, else promotes the last partial to a final
  (`raw.synthesizedFinal = true`). Like Android, a tier-1 session delivers one final and then `onStopped`.
  `latencyMs` of a live final is measured from `endAudio()` to the final (processing latency, comparable with the
  other tiers); `audioMs` is speech start → end. File transcription (benchmark / `transcribeFile`) writes a 16 kHz
  WAV to `tmp` and uses `SFSpeechURLRecognitionRequest`.
* **Tier 1 TTS** (`AppleTtsEngine`): `AVSpeechSynthesisVoice.speechVoices()` filtered by language prefix (exact
  locale first, then premium > enhanced > default). Synthesis uses `AVSpeechSynthesizer.write(_:toBufferCallback:)`
  issued on the main thread; buffers (Float32 or Int16, interleaved or not) are collected; completion = empty buffer
  after data, delegate `didFinish`, or 1.5 s without new buffers (defensive). Output is resampled to
  `tts.sampleRate` (22050) when the voice's rate differs. `voiceId` is `os:<voice identifier>`; `speed` scales
  `AVSpeechUtteranceDefaultSpeechRate`.
* **Tier 2** (`SherpaSttEngine`, `SherpaTtsEngine`, `SherpaVad`): built on the wrapper functions of
  `SherpaOnnx.swift` (`sherpaOnnxOfflineModelConfig(... dolphin:/omnilingual:/nemoCtc:/whisper:/...)`,
  `SherpaOnnxOfflineRecognizer.decode(samples:sampleRate:)`, `sherpaOnnxOfflineTtsVitsModelConfig`,
  `sherpaOnnxOfflineTtsKokoroModelConfig`, `SherpaOnnxOfflineTtsWrapper.generateWithConfig`,
  `SherpaOnnxVoiceActivityDetectorWrapper`). Whisper runs with `language: ""` (auto-detect) and switches language
  in place with `setConfig` when a hint is given; `result.lang` feeds the detector as `engineLang`. Tokens are read
  from the C result (`tokens_arr`) to build word timings like the Kotlin engine.
  **Kokoro Hindi**: the 1.13.8 Swift wrapper exposes `SherpaOnnxGenerationConfigSwift.extra` (JSON); for
  non-English text the engine passes `extra = {"lang": "<espeak voice>"}` (hi, bn, ta, ...), which the 1.13.8 C++
  Kokoro implementation reads (`GetExtraString("lang")`) and routes through espeak-ng. English uses the lexicon.
  Speaker ids come from the catalog's `voices` map (`pack:kokoro-int8-multilang#hf_alpha` → sid 31).
* **Tier 3**: same HTTP contract as Kotlin (`POST {backendUrl}{sttPath}` with `audio/wav` 16 kHz mono, headers
  `X-Voice-Language`, `X-Voice-Keyterms`, `X-Voice-Allowed-Languages`, `Authorization: Bearer <token>`;
  `POST {ttsPath}` JSON → `audio/wav` or `{audioBase64,...}`; `GET /v1/health` with a 4 s timeout cached for 60 s;
  `GET /v1/voices`). `URLSession` + `DispatchSemaphore`, no async/await.
* **Live tiers 2/3**: `MicrophoneSource` (AVAudioEngine tap, `AVAudioConverter` → 16 kHz Int16, manual linear
  resampling as fallback) → `Segmenter` (pre-roll 200 ms, `endpointSilenceMs`, `maxUtteranceMs`, `minSpeechMs`) →
  `engine.transcribe` per segment on a serial worker queue. The VAD is Silero (`silero-vad` pack, window 512) when
  installed, else the energy VAD (frame 480). Silero is driven through the C API's "currently in speech" state since
  1.13.8 has no per-frame probability call.
* **Fallback**: like `IndicVoice.kt`, a tier-1 live error falls through to the next tier (`onTierChanged` then the
  next tier's `onReady`), except for `PERMISSION_DENIED`, `CANCELLED` and — deviating from Android — `BAD_AUDIO`
  ("no speech"), so a silent user does not silently switch tiers. `stopListening()` before any speech ends the
  session with `onStopped` only (no `BAD_AUDIO` error).
* **Audio session**: `.playAndRecord` + `.voiceChat` + `.defaultToSpeaker` while any microphone session is open,
  `.playback` otherwise (`AudioSessionManager`); activation errors surface as `ENGINE_FAILED`.
* **Packs**: `Library/Application Support/indicvoice/packs/<id>/` (excluded from backup). Download via a
  delegate-based `URLSession` with progress (`onPackProgress` phases `download`, `extract`, `done`, percent −1 while
  extracting, like Android), SHA-256 via CryptoKit, `.tar.bz2` → bz2 (ObjC helper) → streaming ustar/GNU/pax reader
  in Swift (regular files only, `stripTopDir`, path-traversal checks). Formats `zip` and `tar.gz` are **not**
  implemented on iOS (the catalog does not use them).
* **Errors**: `VoiceError(code, message, tier)` → `Exception(name: code, description: message, code: code)`;
  JS receives `error.code` ∈ NO_ENGINE, FEATURE_DISABLED, LANGUAGE_UNSUPPORTED, PERMISSION_DENIED, PACK_MISSING,
  PACK_CORRUPT, NETWORK, BACKEND_ERROR, TIMEOUT, ENGINE_FAILED, BAD_AUDIO, CONFIG_ERROR (plus CANCELLED internally).
  Expo wraps the message as "Calling the 'x' function has failed → Caused by: <message>", as on Android.
* **Files written**: `Caches/indicvoice/rec-<ms>.wav` (recordUtterance), `Caches/indicvoice/tts-<ms>.wav` (speak;
  returned as `path`).

## Verify in Xcode (first build checklist)

1. `node scripts/fetch-sherpa.js` must have populated `ios/Frameworks/sherpa-onnx.xcframework` and
   `ios/Frameworks/onnxruntime.xcframework` before `pod install`. Check the onnxruntime zip really unpacks to a
   top-level `onnxruntime.xcframework` (the fetch script assumes it); adjust `vendored_frameworks` if the name differs.
2. `import SherpaOnnxC` (used by `IndicVoiceEngine.swift` and, under `#if SWIFT_PACKAGE`, by the verbatim
   `SherpaOnnx.swift`). The podspec adds `-DSWIFT_PACKAGE` to `OTHER_SWIFT_FLAGS` so the verbatim file's guarded
   import is active. If the module is not found, the framework search path for the xcframework slice is missing —
   CocoaPods ≥ 1.10 normally adds it for `vendored_frameworks`.
3. Mixed Swift/ObjC in one pod: Swift sees `IVBZip2` through the generated umbrella header (`DEFINES_MODULE = YES`).
   If `IVBZip2` is unresolved, make sure the app's Podfile does not disable modular headers for this pod.
4. Expo SDK 58 / `ExpoModulesCore` requires iOS 16.4 while the podspec and `app.json` say 15.1; raise the
   deployment target in `app.json` (`expo-build-properties` → `ios.deploymentTarget`) if `pod install` complains.
5. API points written from memory, worth a glance at the first compile:
   `AVAudioConverter.convert(to:error:withInputFrom:)` input-block pattern; `AVSpeechSynthesizer.write(_:toBufferCallback:)`;
   `SFSpeechRecognizer.supportedLocales()` identifiers (normalised with `_` → `-` and lowercased);
   `AVSpeechSynthesisVoiceQuality.premium` (iOS 16 guard); `AVAudioSession.recordPermission` /
   `requestRecordPermission` (deprecated on iOS 17 in favour of `AVAudioApplication`, still available);
   `Exception(name:description:code:)` and `AsyncFunction` closures with a trailing `Promise` (expo-modules-core 58).
6. `SherpaOnnx.swift`'s wrappers call `fatalError` when sherpa-onnx returns NULL (bad config). The engines validate
   that every model file exists before constructing a recognizer/TTS/VAD, which covers the common causes; a corrupt
   ONNX file would still abort the process (same as the Kotlin runtime, which throws a JNI exception).
7. `Info.plist` needs `NSMicrophoneUsageDescription` and `NSSpeechRecognitionUsageDescription` (present in `app.json`).

## Known limitations / deliberate differences from Android

* Apple Speech covers only Hindi (`hi-IN`) and English (`en-IN`, falling back to any `en-*`) of the 23 languages,
  and cannot auto-detect the spoken language: with `language: "auto"` tier 1 listens in the config's
  `defaultLanguage` (hi) and the detector's script/stopword pass decides what to report.
* Language detection is script + stopword based (same tables as Kotlin); tokenisation splits on Unicode punctuation
  and symbols (a superset of Kotlin's ASCII `\p{Punct}` + danda), which only affects exotic punctuation.
* `catalogInline` only supports the bundled file and inline objects; a remote `catalogUrl` is merged at runtime
  (never written to the bundle). Remote catalog refresh failures are logged, not reported.
* Pack archives: `tar.bz2`, `tar` and single `file` only.
* Audio-session interruptions (phone call, Siri) and route changes are not handled during a live session; the
  session ends with whatever Apple/AVAudioEngine reports.
* The tier-1 TTS `write` API has had iOS-version quirks (empty first buffer, missing end marker); the capture
  tolerates both via the idle timeout but may add ~1.5 s to `latencyMs` on affected versions.
* No resumable pack downloads (a failed download restarts from zero after up to three attempts).
* `AVAudioSession.requestRecordPermission` is used instead of `AVAudioApplication` to keep iOS 15.1 compatibility.
* `recordUtterance` always uses the energy VAD (as the Android module does); live tiers 2/3 use Silero when the
  `silero-vad` pack is installed.
