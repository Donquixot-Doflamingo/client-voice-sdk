#!/usr/bin/env bash
# Downloads the sherpa-onnx 1.13.8 runtime binaries (not committed):
#   scripts/fetch-sherpa.sh android   -> third_party/sherpa-onnx/libs/sherpa-onnx-1.13.8.aar          (Android apps)
#   scripts/fetch-sherpa.sh ios       -> third_party/sherpa-onnx/ios/{sherpa-onnx,onnxruntime}.xcframework  (iOS apps)
#   scripts/fetch-sherpa.sh jvm       -> native jars for every desktop platform (setup.sh fetches only this machine's)
set -euo pipefail
source "$(dirname "$0")/tools.sh"
V=$SHERPA_VER; ORT=1.28.2
LIBS="$SDK_ROOT/third_party/sherpa-onnx/libs"; mkdir -p "$LIBS"
B="https://github.com/k2-fsa/sherpa-onnx/releases/download"
dl() { if [ -f "$2" ]; then echo "have $(basename "$2")"; else say "fetching $(basename "$2")"; download "$1" "$2"; fi; }
case "${1:-}" in
  android) dl "$B/v$V/sherpa-onnx-$V.aar" "$LIBS/sherpa-onnx-$V.aar" ;;
  jvm) for p in linux-x64 linux-aarch64 osx-aarch64 osx-x64 win-x64; do dl "$B/v$V/sherpa-onnx-native-lib-$p-$V.jar" "$LIBS/sherpa-onnx-native-lib-$p-$V.jar"; done ;;
  ios)
    IOS="$SDK_ROOT/third_party/sherpa-onnx/ios"; mkdir -p "$IOS"
    dl "$B/xcframework/sherpa-onnx-v$V-ios-static.xcframework.zip" "$IOS/sherpa-onnx-v$V-ios-static.xcframework.zip"
    dl "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v$ORT/onnxruntime-ios-static-xcframework-$ORT.xcframework.zip" "$IOS/onnxruntime-ios-static-xcframework-$ORT.xcframework.zip"
    (cd "$IOS" && rm -rf sherpa-onnx.xcframework onnxruntime.xcframework && unzip -qo "sherpa-onnx-v$V-ios-static.xcframework.zip" && unzip -qo "onnxruntime-ios-static-xcframework-$ORT.xcframework.zip" && ls -d *.xcframework) ;;
  *) echo "usage: scripts/fetch-sherpa.sh android|ios|jvm"; exit 1 ;;
esac
