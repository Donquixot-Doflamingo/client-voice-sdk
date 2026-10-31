#!/usr/bin/env bash
# Downloads the sherpa-onnx 1.13.8 runtime binaries that are not committed to the repo.
#   scripts/fetch-sherpa.sh android   -> third_party/sherpa-onnx/libs/sherpa-onnx-1.13.8.aar
#   scripts/fetch-sherpa.sh ios       -> third_party/sherpa-onnx/ios/sherpa-onnx.xcframework + onnxruntime.xcframework
#   scripts/fetch-sherpa.sh jvm       -> native jars for linux-x64, osx-aarch64, osx-x64, win-x64
#   scripts/fetch-sherpa.sh all
set -euo pipefail
V=1.13.8
ORT=1.28.2
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/third_party/sherpa-onnx/libs"; mkdir -p "$LIBS"
B="https://github.com/k2-fsa/sherpa-onnx/releases/download"
what="${1:-all}"
dl() { local url="$1" out="$2"; if [ -f "$out" ]; then echo "have $(basename "$out")"; else echo "fetching $(basename "$out")"; curl -fL --retry 3 -o "$out.part" "$url" && mv "$out.part" "$out"; fi; }
if [ "$what" = android ] || [ "$what" = all ]; then
  dl "$B/v$V/sherpa-onnx-$V.aar" "$LIBS/sherpa-onnx-$V.aar"
fi
if [ "$what" = jvm ] || [ "$what" = all ]; then
  for p in linux-x64 linux-aarch64 osx-aarch64 osx-x64 win-x64; do dl "$B/v$V/sherpa-onnx-native-lib-$p-$V.jar" "$LIBS/sherpa-onnx-native-lib-$p-$V.jar"; done
fi
if [ "$what" = ios ] || [ "$what" = all ]; then
  IOS="$ROOT/third_party/sherpa-onnx/ios"; mkdir -p "$IOS"
  dl "$B/xcframework/sherpa-onnx-v$V-ios-static.xcframework.zip" "$IOS/sherpa-onnx-v$V-ios-static.xcframework.zip"
  dl "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v$ORT/onnxruntime-ios-static-xcframework-$ORT.xcframework.zip" "$IOS/onnxruntime-ios-static-xcframework-$ORT.xcframework.zip"
  (cd "$IOS" && rm -rf sherpa-onnx.xcframework onnxruntime.xcframework && unzip -qo "sherpa-onnx-v$V-ios-static.xcframework.zip" && unzip -qo "onnxruntime-ios-static-xcframework-$ORT.xcframework.zip" && ls -d *.xcframework)
fi
echo done
