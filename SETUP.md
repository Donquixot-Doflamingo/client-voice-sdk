# Setup guide

From `git clone` to a working, tested SDK on your machine — one command — and then how to put it in an app.

## 1. One command

```bash
git clone git@github.com:Donquixot-Doflamingo/client-voice-sdk.git
cd client-voice-sdk
./scripts/setup.sh
```

That's it. It runs five steps and prints what it is doing:

| Step | What happens | First run | Later runs |
|---|---|---|---|
| 1. tools | Uses your Java 17+ and Kotlin compiler if you have them; otherwise downloads a **private** JDK 21 and `kotlinc` into `.tools/` (nothing is installed system‑wide) | ~270 MB download | cached |
| 2. native library | The sherpa‑onnx runtime for this machine (macOS arm64/x64, Linux x64/arm64) | ~10 MB | cached |
| 3. build | Compiles the SDK → `build/client-voice-sdk.jar` | ~1 min | ~1 min |
| 4. self‑test | 35+ checks: JSON, all 23 languages, language detection (15 languages + romanised Hindi), transcript format, audio, VAD segmenter, WER, config + catalog, bzip2 | seconds | seconds |
| 5. smoke test | Downloads 3 small model packs into `~/.indicvoice/packs`, speaks a Hindi sentence with the on‑device voice and transcribes it with the on‑device recogniser (expects WER ≤ 0.35) | ~140 MB | cached |

Expected ending:

```
   STT: "नमस्ते मेरा नाम मयंक है और मैं दिल्ली में रहता हूं।"  language=hi  205 ms (sherpa:dolphin-base-int8)
   WER: 0.00
SMOKE TEST PASSED
== All done. The SDK is built and working on this machine.
```

Options: `./scripts/setup.sh --no-models` skips step 5 (no model downloads; this is what CI runs).

**Requirements:** macOS or Linux (Windows: use WSL), `bash`, `curl`, `unzip`, `tar`, internet access to github.com,
~1 GB free disk. Nothing else.

## 2. Use it in your app

### Android (Kotlin or Java)

1. Get the sherpa‑onnx Android runtime: `./scripts/fetch-sherpa.sh android` (→ `third_party/sherpa-onnx/libs/sherpa-onnx-1.13.8.aar`).
2. Add the SDK modules to your app's `settings.gradle.kts` (path = where you cloned this repo):

   ```kotlin
   include(":sdk-android")
   project(":sdk-android").projectDir = file("../client-voice-sdk/sdk-android")
   ```

   `sdk-android` compiles `sdk-core` + `sdk-sherpa` into one library and ships `config/` as assets.
   Your root `build.gradle.kts` needs the Android Gradle plugin and Kotlin plugin (see this repo's `build.gradle.kts`).
3. `implementation(project(":sdk-android"))` in your app module, and `RECORD_AUDIO` permission in the manifest.
4. Code:

   ```kotlin
   val voice = IndicVoiceAndroid.create(context)            // reads assets voice-config.json + catalog.json
   voice.setBackend(backendUrl, token)                      // optional: tier 3 through YOUR backend
   val r = voice.transcribe(audio, SttOptions())            // or voice.startLive(...) for the microphone
   println(r.language.language.code + " " + r.text)
   voice.speak(TtsOptions(language = Language.HINDI, text = "धन्यवाद"))
   ```

> Status: the Android module compiles against `android.jar` (API 35) in our tests, but a full Gradle build inside an
> Android app has not been run yet — please report the first build's result.

### Desktop / server JVM (Kotlin or Java)

Classpath = `build/client-voice-sdk.jar` + `kotlin-stdlib` (2.x) + the native jar for the machine
(`third_party/sherpa-onnx/libs/sherpa-onnx-native-lib-<os>-<arch>-1.13.8.jar`). Then:

```kotlin
val voice = IndicVoice(VoiceConfig.load(File("config/voice-config.json")), JvmPlatform(File(System.getProperty("user.home"), ".indicvoice/packs")))
voice.packs.install("dolphin-base-int8")
val r = voice.transcribe(Wav.read(File("speech.wav")), SttOptions(language = null))   // null = detect the language
println(r.toJson(pretty = true))
```

`sdk-jvm/src/test/kotlin/dev/indicvoice/jvm/SmokeTest.kt` is a complete, runnable example.

### iOS (Swift)

`sdk-ios/Sources/` is the Swift implementation of the same SDK (Apple Speech + AVSpeechSynthesizer for tier 1,
sherpa‑onnx for tier 2, the backend contract for tier 3). Add the files to your target, run
`./scripts/fetch-sherpa.sh ios` and add the two xcframeworks, link `libbz2` and `libc++`, and bundle
`config/voice-config.json` + `config/catalog.json`. Written without Xcode at hand: `sdk-ios/README.md` is the
first‑build checklist.

### Your backend (tier 3)

The app never holds vendor keys. Your server implements [`docs/BACKEND-CONTRACT.md`](docs/BACKEND-CONTRACT.md)
(`POST /v1/stt` WAV → JSON, `POST /v1/tts` JSON → WAV, `GET /v1/health`, `GET /v1/voices`) and the app calls
`voice.setBackend(url, token)`.

## 3. Configure without code changes

* `config/voice-config.json` — languages, tier order/policy, Auto‑language settings (`stt.languageId`), which model
  pack per language, endpointing, backend paths/timeouts, voices, logging.
* `config/catalog.json` — the downloadable model packs (URL, sha256, size, licence, files). Host them on your own CDN
  by changing the URLs. Every pack is credited in [`CREDITS.md`](CREDITS.md).

## 4. Troubleshooting

| Symptom | Fix |
|---|---|
| `Could not resolve host` / downloads fail | Check internet; behind an office proxy set `HTTPS_PROXY=http://host:port` before running setup.sh |
| Hindi prints as `????` | Your terminal locale isn't UTF‑8: `export LC_ALL=en_US.UTF-8` |
| Want to use your own Java / Kotlin | `export JAVA_HOME=…` / `export KOTLINC=/path/to/kotlinc` before setup.sh |
| Start over | `rm -rf build .tools third_party/sherpa-onnx/libs` (models: `~/.indicvoice/packs`) |
| `SMOKE TEST FAILED` | Send the last 20 lines of output; the self‑test (step 4) tells you whether the code or the models are at fault |
