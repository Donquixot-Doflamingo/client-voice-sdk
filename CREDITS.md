# Credits and licences

This SDK is glue, routing and UX around other people's open models and libraries. **None of the model
weights are stored in this repository** — they are downloaded on demand from the URLs in
[`config/catalog.json`](config/catalog.json) — but anyone shipping the SDK ships those models too, so
their licences apply to your app. Please keep this file with the code and show the relevant credits in
your app's "open-source licences" screen.

> ⚠️ **Two items need a licence review before a commercial release** (marked ⚠️ below): the Piper Hindi
> voice (non-commercial training data) and espeak-ng (GPL-3.0, bundled inside the TTS packs).

## Speech-to-text models

| Pack id | Model | Authors | Licence | Links |
|---|---|---|---|---|
| `dolphin-base-int8`, `dolphin-small-int8` | Dolphin (CTC, multilingual Eastern languages) | DataoceanAI & Tsinghua University | Apache-2.0 | [github.com/DataoceanAI/Dolphin](https://github.com/DataoceanAI/Dolphin) · [model card](https://huggingface.co/DataoceanAI/dolphin-base) · [paper](https://arxiv.org/abs/2503.20212) |
| `omnilingual-300m-int8` | Omnilingual ASR, 300M CTC (1,600+ languages) | Meta FAIR | Apache-2.0 | [github.com/facebookresearch/omnilingual-asr](https://github.com/facebookresearch/omnilingual-asr) |
| `whisper-base-int8`, `whisper-base-en-int8`, `whisper-small-int8`, `whisper-turbo-int8` | Whisper (base, base.en, small, large-v3-turbo) — also used for spoken-language identification | OpenAI | MIT | [github.com/openai/whisper](https://github.com/openai/whisper) · [paper](https://arxiv.org/abs/2212.04356) |
| `silero-vad` | Silero VAD (voice activity detection) | Silero Team | MIT | [github.com/snakers4/silero-vad](https://github.com/snakers4/silero-vad) |
| `indicconformer-hi-int8` *(planned, not yet downloadable)* | IndicConformer | AI4Bharat, IIT Madras | MIT | [github.com/AI4Bharat/IndicConformerASR](https://github.com/AI4Bharat/IndicConformerASR) · [model](https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual) |

All STT packs are the ONNX exports published by the sherpa-onnx project
([asr-models release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)).

## Text-to-speech voices

| Pack id | Voice | Authors | Licence | Links |
|---|---|---|---|---|
| `kokoro-int8-multilang` | Kokoro-82M v1.0 (Hindi voices `hf_alpha`, `hf_beta`, `hm_omega`, `hm_psi`; English `af_*`, `am_*`, `bf_*`, `bm_*`) | hexgrad; ONNX export by thewh1teagle | Apache-2.0 (model), MIT (ONNX export) | [huggingface.co/hexgrad/Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) · [github.com/thewh1teagle/kokoro-onnx](https://github.com/thewh1teagle/kokoro-onnx) |
| `piper-hi-priyamvada` ⚠️ | Piper `hi_IN-priyamvada-medium` | Piper by Michael Hansen / Rhasspy; voice trained by PravalX | Piper: MIT. **Training data: CC BY-NC-SA 4.0 (non-commercial)** — testing only | [github.com/rhasspy/piper](https://github.com/rhasspy/piper) · [voice files](https://huggingface.co/rhasspy/piper-voices/tree/main/hi/hi_IN/priyamvada/medium) |
| `piper-ml-meera` | Piper `ml_IN-meera-medium` | Rhasspy Piper community | Piper: MIT; training data: IIT Madras Indic TTS Malayalam corpus (check its terms) | [voice files](https://huggingface.co/rhasspy/piper-voices/tree/main/ml/ml_IN/meera/medium) |
| `mimic3-gu-cmu-indic` | Mimic 3 `gu_IN/cmu-indic_low` | Mycroft AI; data: CMU INDIC databases | see the voice repository | [github.com/MycroftAI/mimic3-voices](https://github.com/MycroftAI/mimic3-voices/tree/master/voices/gu_IN/cmu-indic_low) · [CMU INDIC](http://festvox.org/cmu_indic/) |

## Software

| Component | Used for | Licence | Link |
|---|---|---|---|
| sherpa-onnx 1.13.8 (k2-fsa / Next-gen Kaldi) | runs every on-device model (JNI, Android AAR, iOS xcframework, Kotlin/Swift bindings in `third_party/`) | Apache-2.0 | [github.com/k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) |
| ONNX Runtime | inference engine inside sherpa-onnx | MIT | [github.com/microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) |
| espeak-ng ⚠️ | phonemiser used by Piper / Kokoro / Mimic voices (`espeak-ng-data` inside the TTS packs) | **GPL-3.0** | [github.com/espeak-ng/espeak-ng](https://github.com/espeak-ng/espeak-ng) |
| Kotlin | SDK language | Apache-2.0 | [kotlinlang.org](https://kotlinlang.org) |
| Eclipse Temurin JDK | downloaded by `scripts/setup.sh` into `.tools/` only when the machine has no Java 17+ (not in git, not shipped) | GPL-2.0 with Classpath Exception | [adoptium.net](https://adoptium.net) |
| Kotlin compiler | downloaded by `scripts/setup.sh` into `.tools/` when missing (build tool, not shipped) | Apache-2.0 | [github.com/JetBrains/kotlin](https://github.com/JetBrains/kotlin) |

## Services (tier 3, optional)

| Service | Used for | Terms |
|---|---|---|
| Sarvam AI — Saaras (STT), Bulbul (TTS) | an option for *your* tier-3 backend (the SDK itself never calls it) | commercial API — [sarvam.ai](https://www.sarvam.ai) |
| Google / Apple speech | tier 1 = the operating system's or browser's own engine (Android `SpeechRecognizer`/`TextToSpeech`, Apple Speech/`AVSpeechSynthesizer`) | governed by the OS / browser terms |

## How to cite the main models

* Dolphin — Meng et al., *Dolphin: A Large-Scale Automatic Speech Recognition Model for Eastern Languages*, 2025. arXiv:2503.20212
* Whisper — Radford et al., *Robust Speech Recognition via Large-Scale Weak Supervision*, 2022. arXiv:2212.04356
* Omnilingual ASR — Omnilingual ASR team (Meta FAIR), *Omnilingual ASR: Open-Source Multilingual Speech Recognition for 1600+ Languages*, 2025
* Kokoro — hexgrad, *Kokoro-82M*, 2025

If you add a pack to `config/catalog.json`, add a row here in the same change.
