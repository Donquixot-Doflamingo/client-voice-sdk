# Tier‑3 backend contract (`/v1`)

The SDK never talks to a speech vendor directly. It talks to **your** backend
through this small, vendor‑neutral HTTP contract. Put any provider behind it (Sarvam, Google, Azure,
ElevenLabs, your own GPU box) and swap it without touching the app.

A reference implementation (Kotlin, zero dependencies) exists in the team's internal test kit, with two providers:
**`local`** (runs the same on‑device models as the app, on the server — free) and **`sarvam`** (Saaras v4 STT,
Bulbul v3 TTS; needs `SARVAM_API_KEY`). Any server that follows this document works.

All responses are UTF‑8 JSON unless stated. Enable CORS if browsers call it directly.

## Auth

Optional. If the server was started with `PROXY_TOKEN`, every request except `/v1/health` must carry
`Authorization: Bearer <token>`. (Header name/prefix are configurable in `voice-config.json → stt.tier3`.)
In production replace the static token with a short‑lived token minted by your own auth (the SDK sends
whatever the host app injects via `setBackend(url, token)`).

## `GET /v1/health`

```json
{ "ok": true, "service": "indicvoice-proxy", "version": "0.1",
  "providers": { "stt": ["sarvam", "local"], "tts": ["sarvam", "local"] },
  "auth": "none", "languages": ["as","bn","brx","doi","en","gu","hi", "..."] }
```

The SDK calls this (4 s timeout, cached 60 s) to show tier‑3 status in the UI. A backend without it still works.

## `POST /v1/stt`

Request — either form:

| Form | Content‑Type | Body |
|---|---|---|
| binary (default) | `audio/wav` | RIFF WAV, **16 kHz, mono, 16‑bit PCM**, one utterance (≤ 60 s) |
| JSON | `application/json` | `{ "audioBase64": "...", "encoding": "pcm16" \| "wav", "sampleRate": 16000, "language": "hi", "keyterms": ["UPI"] }` |

Headers (binary form): `X-Voice-Language: hi` (ISO code, or `auto`), `X-Voice-Keyterms: UPI,Paytm` (optional),
`X-Voice-Allowed-Languages: hi,en,ta` (optional).

Response `200` — the SDK's transcript format (identical to what tiers 1 and 2 produce):

```json
{
  "language": { "code": "hi", "bcp47": "hi-IN", "name": "Hindi", "nativeName": "हिन्दी",
                "script": "Deva", "confidence": 0.95, "source": "stopwords" },
  "text": "नमस्ते मेरा नाम मयंक है और मैं दिल्ली में रहता हूं।",
  "words": [ { "text": "नमस्ते", "startMs": 120, "endMs": 799 }, { "text": "मेरा", "startMs": 799, "endMs": 1199 } ],
  "wordCount": 11, "confidence": null,
  "tier": 3, "tierLabel": "tier3-hosted", "engine": "local/dolphin-base-int8",
  "latencyMs": 141, "audioMs": 3915, "rtf": 0.036, "isFinal": true
}
```

`language.source` tells the caller how the language was determined: `engine` (vendor LID), `script`
(unique script), `stopwords` (shared script resolved by function words), `hint` (caller's hint), `default`.

Errors: `400 {"error":{"code":"BAD_AUDIO",...}}`, `401 {"error":"unauthorized"}`,
`503 {"error":{"code":"NO_PROVIDER","message":"sarvam: ...; local: ..."}}`.

## `POST /v1/tts`

Request `application/json`:

```json
{ "text": "आपका स्वागत है", "language": "hi", "voiceId": "default", "speed": 1.0, "sampleRate": 22050, "format": "wav" }
```

`voiceId` may be `default`, a provider‑qualified id from `/v1/voices` (`sarvam:anushka`, `local:pack:piper-hi-priyamvada`),
or a bare name the first provider understands.

Response `200`:

* default: `audio/wav` bytes (mono 16‑bit at the requested rate) with headers
  `X-Voice-Id`, `X-Voice-Engine`, `X-Voice-Latency-Ms`;
* with `Accept: application/json` (or `"json": true` in the body):
  `{ "audioBase64": "...", "format": "wav", "sampleRate": 22050, "voiceId": "...", "engine": "...", "latencyMs": 127 }`.

## `GET /v1/voices?language=hi`

```json
{ "voices": [ { "id": "sarvam:anushka", "provider": "sarvam", "language": "hi", "name": "Sarvam anushka (bulbul:v3)", "gender": "female", "quality": "natural" } ] }
```

## `GET /v1/catalog.json`

Serves the tier‑2 pack catalog, so the backend can double as the catalog host
(`voice-config.json → packs.catalogUrl`).

## Implementing it elsewhere (Node/Python/Go)

1. Accept the WAV, resample to 16 kHz if needed, call your vendor.
2. Map the vendor's answer to the transcript JSON above. If the vendor does not return a language, run
   script detection (Unicode block of the text) and set `source: "script"`; if it does, `source: "engine"`.
3. Never return vendor‑specific fields the app has to understand — keep them under `raw` if you must.
4. For streaming (partials while the user speaks) the next step is a WebSocket `/v1/stt/stream` that
   accepts 20 ms PCM frames and emits `{type:"partial"|"final", ...}` with the same JSON; the SDK's live
   mode currently sends one request per VAD‑segmented utterance, which keeps latency ≈ model time + RTT.


## Optional: `POST /v1/lid` — spoken language identification

Body: `audio/wav` (16 kHz mono preferred). Reply:

```json
{ "available": true, "raw": "en", "language": "en", "allowed": true, "engine": "sherpa-lid:whisper-base-int8", "latencyMs": 120 }
```

`raw` is what the model heard (may be a language outside the 23, e.g. `ja`); `language` is `null` then.
Useful when the client has to pick a language before recognising (e.g. OS engines that need it up front).

## Optional request header: `X-Voice-Provider`

`X-Voice-Provider: local` (or `sarvam`) pins `/v1/stt` and `/v1/tts` to one provider instead of the configured order.
Useful for testing one provider at a time.
