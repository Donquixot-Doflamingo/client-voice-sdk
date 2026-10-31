#!/usr/bin/env bash
# One command after `git clone`:   ./scripts/setup.sh
#   1. Java 17+ and the Kotlin compiler (downloaded into .tools/ if this machine has none)
#   2. the sherpa-onnx native library for this machine
#   3. builds build/client-voice-sdk.jar
#   4. runs the self-test (no models needed)
#   5. end-to-end smoke test: downloads 3 small model packs (~140 MB, once) and does Hindi text → speech → text
# Options:  --no-models   skip step 5 (no model downloads)
set -euo pipefail
source "$(dirname "$0")/tools.sh"
cd "$SDK_ROOT"
MODELS=1; for a in "$@"; do case "$a" in --no-models) MODELS=0 ;; -h|--help) sed -n 2,9p "$0"; exit 0 ;; *) die "unknown option $a" ;; esac; done

say "1/5 tools";   ensure_java; ensure_kotlinc
echo "   java:    $(java -version 2>&1 | grep -m1 " version ")"; echo "   kotlinc: $("$KOTLINC" -version 2>&1 | grep -o 'kotlinc-jvm [0-9.]*')"
say "2/5 native library"; ensure_natives; echo "   $NATIVE_JAR"
say "3/5 build";   scripts/build.sh
STDLIB="$(kotlin_stdlib)"; CP="build/client-voice-sdk.jar:$STDLIB:$NATIVE_JAR"
export LC_ALL="${LC_ALL:-$(locale -a 2>/dev/null | grep -qiE '^en_US\.utf-?8$' && echo en_US.UTF-8 || echo C.UTF-8)}"
JOPTS=(-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8)
# Behind an office proxy? Java ignores HTTPS_PROXY by default; pass it on for the model downloads.
PROXY="${HTTPS_PROXY:-${https_proxy:-}}"
if [ -n "$PROXY" ]; then hp="${PROXY#*://}"; hp="${hp##*@}"; hp="${hp%%/*}"; JOPTS+=(-Dhttps.proxyHost="${hp%%:*}" -Dhttps.proxyPort="${hp##*:}"); fi

say "4/5 self-test (JSON, languages, language detection, transcript format, audio, VAD segmenter, WER, config, bzip2)"
rm -rf build/tests
"$KOTLINC" -nowarn -jvm-target 17 -cp "$CP" -d build/tests sdk-core/src/test/kotlin sdk-jvm/src/test/kotlin || die "test compile failed"
java "${JOPTS[@]}" -cp "build/tests:$CP" dev.indicvoice.core.SelfTest

if [ "$MODELS" = 1 ]; then
  say "5/5 smoke test: Hindi text → speech (Piper) → text (Dolphin), models in ${INDICVOICE_PACKS_DIR:-$HOME/.indicvoice/packs}"
  java "${JOPTS[@]}" -cp "build/tests:$CP" dev.indicvoice.jvm.SmokeTestKt config/voice-config.json
else
  say "5/5 smoke test skipped (--no-models)"
fi
echo
say "All done. The SDK is built and working on this machine. Next: SETUP.md → 'Use it in your app'."
