# VoiceTestApp — phone test console for the Indic Voice SDK

Expo (SDK 58) dev-client app with the local native module `modules/indic-voice` (Android: the Kotlin SDK compiled in; iOS: Swift implementation of the same contract).

## What it shows

* **Settings** — language chips (auto + 23), backend URL + token (optional; pre-filled from `.env`), STT/TTS per-user switches.
* **Speech → text tiers** — per tier: available or not and *why* (pack missing, no backend, no OS language…); download/remove tier-2 packs with progress.
* **Speech → text** — tier selector (Auto/1/2/3), SPEAK button with live level, partial text, final result: language badge (name, code, script, confidence, how detected), words with timings, tier/engine/latency/RTF.
* **Benchmark** — *Record & benchmark* records one utterance and runs it through every tier side by side (latency, RTF, language, text, WER/CER if you typed what you said).
* **Text → speech** — tier status, text box, tier selector, voice chips, Speak/Stop, *Benchmark TTS tiers*.
* **Log** — every event the module emits.

## Build

```bash
npm install                      # also downloads the sherpa-onnx AAR / xcframeworks (scripts/fetch-sherpa.js)
cp .env.example .env             # optional: backend URL etc.
npx expo prebuild                # generates android/ and ios/
npx expo run:android             # or: npx expo run:ios
```

No local toolchain? Use the GitHub Actions workflow at the repo root (`.github/workflows/android-apk.yml`) or `npx eas build -p android --profile preview`.

The SDK's behaviour (tiers, packs, languages) comes from `../../config/voice-config.json` and `catalog.json`, bundled as assets at build time — edit them, rebuild, no code change.
