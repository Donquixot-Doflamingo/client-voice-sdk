# Environment variables — what you need to provide

Rule of thumb: **the SDK itself reads no environment variables.** Its behaviour comes from
`config/voice-config.json` + `config/catalog.json` (edit, redeploy or host remotely — no code change).
ENV is only used by the two things that sit *around* the SDK: the React Native test app and the backend proxy.

## React Native test app (`apps/VoiceTestApp/.env`)

Copy `apps/VoiceTestApp/.env.example` to `.env`. Expo inlines `EXPO_PUBLIC_*` values at bundle time.
**All optional** — with no `.env` the app runs tiers 1 + 2 and the backend URL can be typed in the UI.

| Variable | Example | Meaning |
|---|---|---|
| `EXPO_PUBLIC_VOICE_BACKEND_URL` | `http://192.168.1.10:8080` | Tier‑3 backend base URL (your proxy). Pre‑fills the UI field. Use your laptop's LAN IP when testing on a phone. |
| `EXPO_PUBLIC_VOICE_BACKEND_TOKEN` | `s3cret` | Bearer token, only if the proxy runs with `PROXY_TOKEN`. |
| `EXPO_PUBLIC_VOICE_CATALOG_URL` | `http://192.168.1.10:8080/v1/catalog.json` | Remote pack catalog; default = the `catalog.json` bundled in the app. |
| `EXPO_PUBLIC_VOICE_DEFAULT_LANGUAGE` | `hi` | Language chip selected at start (`auto` allowed). |

Builds: `eas build` reads the same `.env` (or EAS secrets); GitHub Actions needs them as repository
*variables* only if you want a default backend baked in — otherwise leave them unset.

## Backend proxy (`bin/indicvoice-proxy`)

| Variable | Required | Meaning |
|---|---|---|
| `SARVAM_API_KEY` | no | Enables the `sarvam` provider (Saaras v4 STT ₹0.49/min PAYG, Bulbul v3 TTS). Without it only the free `local` provider runs. Get a key at dashboard.sarvam.ai (₹100 free credit). |
| `PROXY_TOKEN` | no | If set, clients must send `Authorization: Bearer <PROXY_TOKEN>`. |
| `PORT` | no | Listen port (default 8080, also in `proxy/config.json`). |
| `INDICVOICE_PACKS_DIR` | no | Where the `local` provider finds model packs (default `~/.indicvoice/packs`, also in `proxy/config.json`). |

Everything else about the proxy (provider order, models, speaker, packs per language) lives in `proxy/config.json`.

## Desktop CLI (`bin/indicvoice`)

No ENV needed. Optional shortcuts: `INDICVOICE_BACKEND_URL`, `INDICVOICE_BACKEND_TOKEN`, `INDICVOICE_PACKS_DIR`,
`INDICVOICE_NATIVE_DIR` (pre‑extracted sherpa‑onnx libraries). The same values can be passed as `--backend`, `--token`, `--packs-dir`.

## Build‑time helpers

| Variable | Used by | Meaning |
|---|---|---|
| `SHERPA_FETCH_PLATFORM` | `apps/VoiceTestApp/scripts/fetch-sherpa.js` | `android`, `ios` or `all` (default) — which sherpa‑onnx binaries to download on `npm install`. |
| `SKIP_SHERPA_FETCH=1` | same | Skip the download (type‑check‑only CI). |
| `ANDROID_HOME` | Gradle | Standard Android SDK location; enables the `:sdk-android` Gradle module. |
