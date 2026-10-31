# Third-party: sherpa-onnx 1.13.8 (Apache-2.0)

* `kotlin-api/` — the Kotlin JNI bindings from https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8/sherpa-onnx/kotlin-api
  (identical classes ship inside the Android AAR; these sources are only compiled for the JVM build).
* `jvm-stubs/` — tiny stand-ins for `android.content.res.AssetManager` and `android.util.Log` so the bindings compile on a plain JVM.
* `libs/` — native runtime jars (`sherpa-onnx-native-lib-<os>-<arch>-1.13.8.jar`) for desktop use.
  `scripts/fetch-sherpa.sh` downloads the AAR (Android) and other platforms from the GitHub release.

Why 1.13.8 and not 2.x: sherpa-onnx 2.0 removed the built-in espeak-ng text frontend, which the Piper/Kokoro
voices need for Hindi and other Indic languages. Stay on 1.13.x until the on-device TTS packs are re-exported
with an external phonemizer.

Local patch: in `kotlin-api/` every `System.loadLibrary("sherpa-onnx-jni")` is replaced by
`dev.indicvoice.sherpa.SherpaRuntime.loadNative()`, which loads the libraries by absolute path
(extracted from the native-lib jar). `java.library.path` is therefore not needed on the JVM.
