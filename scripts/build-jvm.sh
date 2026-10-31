#!/usr/bin/env bash
# Builds the desktop CLI + proxy with the plain Kotlin compiler (no Gradle, no Maven access needed).
#
#   scripts/build-jvm.sh            -> build/indicvoice-cli.jar, build/indicvoice-proxy.jar
#
# Requirements: JDK 17+, kotlinc 2.x on PATH (https://github.com/JetBrains/kotlin/releases → kotlin-compiler-<ver>.zip)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT=build
mkdir -p "$OUT/classes" "$OUT/tmp"

KOTLINC="${KOTLINC:-kotlinc}"
if ! command -v "$KOTLINC" >/dev/null 2>&1; then
  echo "kotlinc not found. Install the Kotlin compiler zip and put bin/ on PATH, or set KOTLINC=/path/to/kotlinc" >&2
  exit 1
fi
KOTLIN_HOME="$(cd "$(dirname "$(command -v "$KOTLINC")")/.." && pwd)"
STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"

echo "[1/3] compiling SDK core + sherpa bindings + JVM platform + CLI + proxy"
rm -rf "$OUT/classes"/*
"$KOTLINC" -nowarn -jvm-target 17 -d "$OUT/classes" \
  sdk-core/src/main/kotlin \
  third_party/sherpa-onnx/kotlin-api \
  third_party/sherpa-onnx/jvm-stubs \
  sdk-sherpa/src/main/kotlin \
  sdk-jvm/src/main/kotlin \
  proxy/src/main/kotlin

echo "[2/3] packaging jars"
# Fat jar: our classes + kotlin stdlib. Native libs stay in their own jars (picked per platform at runtime).
rm -rf "$OUT/tmp/fat" && mkdir -p "$OUT/tmp/fat"
(cd "$OUT/tmp/fat" && unzip -qo "$STDLIB" && rm -rf META-INF/versions META-INF/*.kotlin_module 2>/dev/null || true)
cp -r "$OUT/classes/." "$OUT/tmp/fat/"
mkdir -p "$OUT/tmp/fat/indicvoice"
cp config/voice-config.json config/catalog.json "$OUT/tmp/fat/indicvoice/"
mkdir -p "$OUT/tmp/fat/indicvoice/web" && cp proxy/web/index.html proxy/web/msite.html "$OUT/tmp/fat/indicvoice/web/"
printf 'Manifest-Version: 1.0\nMain-Class: dev.indicvoice.jvm.Main\n' > "$OUT/tmp/manifest-cli.txt"
printf 'Manifest-Version: 1.0\nMain-Class: dev.indicvoice.proxy.ProxyMain\n' > "$OUT/tmp/manifest-proxy.txt"
(cd "$OUT/tmp/fat" && jar cfm "../../indicvoice-cli.jar" ../manifest-cli.txt .)
(cd "$OUT/tmp/fat" && jar cfm "../../indicvoice-proxy.jar" ../manifest-proxy.txt .)

echo "[3/3] done"
ls -la "$OUT"/indicvoice-cli.jar "$OUT"/indicvoice-proxy.jar
echo
echo "Run:  bin/indicvoice caps        (uses third_party/sherpa-onnx/libs/*.jar for the native runtime)"
echo "      bin/indicvoice-proxy       (reference tier-3 backend on :8080)"
