#!/usr/bin/env bash
# Builds the two deliverable zips in dist/:
#   indic-voice-sdk-<date>.zip         the whole repository (sources, config, docs, pre-built jars, workflows)
#   indicvoice-desktop-<date>.zip      laptop test bundle: CLI + proxy + web console + macOS/Linux natives (needs only a JDK)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
DATE=${DATE:-$(date +%Y%m%d)}   # override: DATE=20261031 scripts/package.sh
mkdir -p dist
[ -f build/indicvoice-cli.jar ] || scripts/build-jvm.sh

# ---- desktop bundle
B=build/bundle/indicvoice-desktop; rm -rf "$B"; mkdir -p "$B"/{bin,build,config,proxy/web,third_party/sherpa-onnx/libs,docs}
cp build/indicvoice-cli.jar build/indicvoice-proxy.jar "$B/build/"
cp bin/indicvoice bin/indicvoice-proxy bin/start-msite.sh "$B/bin/"
cp config/voice-config.json config/catalog.json "$B/config/"
cp proxy/config.json "$B/proxy/"; cp proxy/web/index.html proxy/web/msite.html "$B/proxy/web/"; cp proxy/CONTRACT.md "$B/proxy/"
cp third_party/sherpa-onnx/libs/sherpa-onnx-native-lib-osx-aarch64-1.13.8.jar third_party/sherpa-onnx/libs/sherpa-onnx-native-lib-linux-x64-1.13.8.jar "$B/third_party/sherpa-onnx/libs/"
cp docs/DESKTOP-QUICKSTART.md "$B/README.md"; cp ENV.md "$B/docs/"
(cd build/bundle && rm -f "../../dist/indicvoice-desktop-$DATE.zip" && zip -qr "../../dist/indicvoice-desktop-$DATE.zip" indicvoice-desktop)

# ---- repository zip (everything except transient build output and downloaded binaries that scripts re-fetch)
rm -f "dist/indic-voice-sdk-$DATE.zip"
zip -qr "dist/indic-voice-sdk-$DATE.zip" . \
  -x 'dist/*' 'build/tmp/*' 'build/classes/*' 'build/android-check/*' 'build/expo-check/*' 'build/bundle/*' 'build/test/*' \
     '*/node_modules/*' 'apps/VoiceTestApp/android/*' 'apps/VoiceTestApp/ios/*' 'apps/VoiceTestApp/.expo/*' \
     'apps/VoiceTestApp/modules/indic-voice/ios/Frameworks/*.xcframework/*' 'apps/VoiceTestApp/modules/indic-voice/ios/Frameworks/*.zip' \
     'third_party/sherpa-onnx/libs/*.aar' '.git/*' '*.DS_Store' 'last-recording.wav' 'tts-*.wav'
ls -la dist/
