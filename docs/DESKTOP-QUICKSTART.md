# Desktop quick start (macOS / Linux) — test the SDK without a phone

What you need: a Java runtime 17+ and ~200 MB of model downloads. Nothing else (no Gradle, no Android Studio, no npm).

## 1. Install Java (once)

macOS (Apple Silicon or Intel):
```bash
brew install openjdk@21          # or download Temurin 21 from https://adoptium.net
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk
java -version                    # should print 21.x
```
Linux: `sudo apt install openjdk-21-jre-headless` (plus `libasound2` for microphone/speaker).

## 2. Unzip and download two model packs

```bash
unzip indicvoice-desktop-*.zip && cd indicvoice-desktop
bin/indicvoice packs install silero-vad
bin/indicvoice packs install whisper-base-int8        # language ID for Auto mode, 198 MB
bin/indicvoice packs install dolphin-small-int8       # Hindi (+14 Indian languages) STT, 183 MB
bin/indicvoice packs install whisper-base-en-int8     # English STT, 199 MB
bin/indicvoice packs install kokoro-int8-multilang    # natural Hindi/English voices, 126 MB
# optional: omnilingual-300m-int8 (all 22 languages), whisper-turbo-int8 (most accurate, slow), piper-hi-priyamvada (fast voice, testing only)
bin/indicvoice caps                                   # shows what every tier can do on this machine
```
Packs go to `~/.indicvoice/packs`. macOS will ask for microphone permission for Terminal the first time you use `--mic`.

## 3. Try speech → text and text → speech

```bash
bin/indicvoice stt --mic --lang auto            # speak one sentence; prints language + words + timings
bin/indicvoice stt --mic --lang hi --json       # the exact JSON the app/backend returns
bin/indicvoice live --tier 2                    # continuous recognition, Ctrl+C to stop
bin/indicvoice tts --text "आपका स्वागत है" --lang hi --play
bin/indicvoice tts --text "Welcome" --lang en --tier 1 --play     # macOS: tier 1 = the built-in `say` voices
bin/indicvoice roundtrip --text "मुझे एक गिलास पानी चाहिए" --lang hi   # TTS → STT, prints WER
```

## 4. Tier 3: run the backend and compare all tiers

```bash
bin/indicvoice-proxy                             # http://localhost:8080 — free "local" provider; add SARVAM_API_KEY=... for Sarvam
# in another terminal
bin/indicvoice bench --mic --backend http://localhost:8080 --ref "what you are going to say"
open http://localhost:8080                       # browser console: speak, compare browser (tier 1) vs backend, hear TTS
```

For the phone app, point it at your laptop: `http://<your-LAN-IP>:8080` (same Wi‑Fi). `ifconfig | grep "inet "` shows the IP.

## 5. Change behaviour without code

* `config/voice-config.json` — tier policy, languages, packs per language, endpointing, TTS voice policy.
* `config/catalog.json` — model packs (add your CDN URLs here).
* `proxy/config.json` — which providers the backend uses, in which order.

`bin/indicvoice --help` for all commands. Problems: run with `--verbose`, and check that `java -version` is ≥ 17.

## Mobile test site (quickest way to try all three tiers on the laptop)

```bash
bin/start-msite.sh          # downloads the default packs on first run, starts the server, opens http://localhost:8080/m
```

The page has four engine tabs — **Tier 1** (browser/OS speech), **Tier 2** (the downloaded on-device packs, run by the
local server), **Tier 3** (any custom backend API: base URL + token + paths, implementing `proxy/CONTRACT.md`; replies in
other JSON shapes with `text`/`transcript` are accepted too) and **All** (benchmark) — a SPEAK button (mic → detected
language + words + timings + latency, WER against an optional reference) and a text box → speech from the chosen tier.
A phone on the same Wi‑Fi can open `http://<laptop-ip>:8080/m`, but browsers block the microphone on plain http, so on
the phone use the WAV upload or the Expo app for microphone tests.
