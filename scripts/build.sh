#!/usr/bin/env bash
# Builds the SDK for the desktop JVM:  build/client-voice-sdk.jar  (+ build/classes)
# Android apps compile the module sources with Gradle instead (see SETUP.md).
set -euo pipefail
source "$(dirname "$0")/tools.sh"
cd "$SDK_ROOT"
ensure_java; ensure_kotlinc
say "compiling sdk-core + sdk-sherpa + sdk-jvm (kotlinc $("$KOTLINC" -version 2>&1 | grep -o 'kotlinc-jvm [0-9.]*' | cut -d' ' -f2))"
rm -rf build/classes build/tmp; mkdir -p build/classes build/tmp
"$KOTLINC" -nowarn -jvm-target 17 -d build/classes \
  sdk-core/src/main/kotlin third_party/sherpa-onnx/kotlin-api third_party/sherpa-onnx/jvm-stubs \
  sdk-sherpa/src/main/kotlin sdk-jvm/src/main/kotlin
mkdir -p build/classes/indicvoice && cp config/voice-config.json config/catalog.json build/classes/indicvoice/
make_jar build/client-voice-sdk.jar build/classes
say "built build/client-voice-sdk.jar ($(du -h build/client-voice-sdk.jar | cut -f1)); it needs kotlin-stdlib on the classpath"
