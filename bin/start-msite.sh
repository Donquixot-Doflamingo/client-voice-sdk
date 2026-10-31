#!/usr/bin/env bash
# One-command laptop test: downloads the default model packs if missing, (re)starts the local server
# with proxy/config.laptop.json, and opens the mobile test site.
# Usage: bin/start-msite.sh        (macOS / Linux; needs Java 17+, e.g. `brew install openjdk@21`)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-}"
if ! command -v java >/dev/null 2>&1; then
  echo "Java not found. Install it with:  brew install openjdk@21   (or use START.command, which can use a bundled jre/)"; exit 1
fi
[ -f build/indicvoice-proxy.jar ] || { echo "== building the jars (scripts/build-jvm.sh)"; scripts/build-jvm.sh; }
PACKS_DIR="${INDICVOICE_PACKS_DIR:-$HOME/.indicvoice/packs}"
# Laptop defaults: VAD, language ID (Whisper base), Hindi STT (Dolphin small), English STT (Whisper base.en), voices (Kokoro).
for p in ${INDICVOICE_PACKS:-silero-vad whisper-base-int8 dolphin-small-int8 whisper-base-en-int8 kokoro-int8-multilang}; do
  [ -d "$PACKS_DIR/$p" ] || { echo "== downloading model pack $p"; bin/indicvoice packs install "$p"; }
done
PORT="${PORT:-8080}"
CONFIG="${PROXY_CONFIG:-$ROOT/proxy/config.laptop.json}"
if pkill -f indicvoice-proxy.jar 2>/dev/null; then echo "== stopped the previous server"; sleep 2; fi
echo "== starting local server on :$PORT with $(basename "$CONFIG") (log: build/proxy.log)"; mkdir -p build
(nohup bin/indicvoice-proxy --config "$CONFIG" > build/proxy.log 2>&1 &)
for i in $(seq 1 90); do curl -s -m 2 "localhost:$PORT/v1/health" >/dev/null 2>&1 && break; sleep 1; done
IP=$( (ipconfig getifaddr en0 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}') || true)
echo
echo "   Laptop:  http://localhost:$PORT/m        (test site; desktop console at /)"
[ -n "${IP:-}" ] && echo "   Phone:   http://$IP:$PORT/m   (same Wi-Fi; browsers block the mic on plain http — use WAV upload or the Expo app there)"
echo "   Stop:    STOP.command  or  pkill -f indicvoice-proxy.jar"
echo
case "$(uname -s)" in Darwin) open "http://localhost:$PORT/m" ;; Linux) xdg-open "http://localhost:$PORT/m" >/dev/null 2>&1 || true ;; esac
