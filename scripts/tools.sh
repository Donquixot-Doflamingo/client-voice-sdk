#!/usr/bin/env bash
# Shared helpers, sourced by setup.sh / build.sh (and by the test kit).
# If this machine has no Java 17+ or no Kotlin compiler, a private copy is downloaded into .tools/
# (nothing is installed system-wide). Override with JAVA_HOME / KOTLINC if you have your own.
SDK_ROOT="${SDK_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
TOOLS="${CLIENT_VOICE_TOOLS:-$SDK_ROOT/.tools}"
JDK_TAG="jdk-21.0.9%2B10"; JDK_VER="21.0.9_10"
KOTLIN_VER="2.4.21"
SHERPA_VER="1.13.8"

say() { printf '\033[1;34m==\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

os_arch() {   # prints e.g. "mac aarch64" / "linux x64"
  local os arch
  case "$(uname -s)" in Darwin) os=mac ;; Linux) os=linux ;; *) die "unsupported OS $(uname -s) (use macOS or Linux; on Windows use WSL)";; esac
  case "$(uname -m)" in arm64|aarch64) arch=aarch64 ;; x86_64|amd64) arch=x64 ;; *) die "unsupported CPU $(uname -m)";; esac
  echo "$os $arch"
}

download() {  # download URL FILE
  curl -fL --retry 3 --progress-bar -o "$2.part" "$1" && mv "$2.part" "$2"
}

java_major() { "$1" -version 2>&1 | grep -m1 ' version "' | sed -E 's/.*version "([0-9]+).*/\1/'; }

ensure_java() {
  local j
  for j in "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java 2>/dev/null)" "$TOOLS/jdk/bin/java" "$TOOLS/jdk/Contents/Home/bin/java"; do
    [ -n "$j" ] && [ -x "$j" ] || continue
    if [ "$(java_major "$j" 2>/dev/null)" -ge 17 ] 2>/dev/null; then
      export JAVA_HOME="$(cd "$(dirname "$j")/.." && pwd)"; export PATH="$JAVA_HOME/bin:$PATH"; return 0
    fi
  done
  read -r os arch < <(os_arch)
  say "Java 17+ not found — downloading a private JDK 21 (Eclipse Temurin, ~190 MB) into .tools/jdk"
  mkdir -p "$TOOLS"; rm -rf "$TOOLS/jdk.tmp"; mkdir -p "$TOOLS/jdk.tmp"
  download "https://github.com/adoptium/temurin21-binaries/releases/download/$JDK_TAG/OpenJDK21U-jdk_${arch}_${os}_hotspot_${JDK_VER}.tar.gz" "$TOOLS/jdk.tar.gz"
  tar xzf "$TOOLS/jdk.tar.gz" -C "$TOOLS/jdk.tmp" --strip-components=1 && rm -f "$TOOLS/jdk.tar.gz" && rm -rf "$TOOLS/jdk" && mv "$TOOLS/jdk.tmp" "$TOOLS/jdk"
  if [ -x "$TOOLS/jdk/Contents/Home/bin/java" ]; then export JAVA_HOME="$TOOLS/jdk/Contents/Home"; else export JAVA_HOME="$TOOLS/jdk"; fi
  export PATH="$JAVA_HOME/bin:$PATH"
}

ensure_kotlinc() {
  if [ -n "${KOTLINC:-}" ] && [ -x "$KOTLINC" ]; then return 0; fi
  if [ -x "$TOOLS/kotlinc/bin/kotlinc" ]; then KOTLINC="$TOOLS/kotlinc/bin/kotlinc"; export KOTLINC; return 0; fi
  if command -v kotlinc >/dev/null 2>&1 && kotlinc -version 2>&1 | grep -q 'kotlinc-jvm 2\.'; then KOTLINC="$(command -v kotlinc)"; export KOTLINC; return 0; fi
  say "Kotlin compiler not found — downloading kotlinc $KOTLIN_VER (~80 MB) into .tools/kotlinc"
  mkdir -p "$TOOLS"; rm -rf "$TOOLS/kotlinc"
  download "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VER/kotlin-compiler-$KOTLIN_VER.zip" "$TOOLS/kotlinc.zip"
  (cd "$TOOLS" && unzip -q kotlinc.zip && rm -f kotlinc.zip)
  KOTLINC="$TOOLS/kotlinc/bin/kotlinc"; export KOTLINC
}

kotlin_stdlib() { echo "$(cd "$(dirname "$KOTLINC")/.." && pwd)/lib/kotlin-stdlib.jar"; }

# The sherpa-onnx native library jar for THIS machine (desktop JVM only; Android/iOS use the AAR / xcframework).
ensure_natives() {
  local dir="${1:-$SDK_ROOT/third_party/sherpa-onnx/libs}" os arch key
  read -r os arch < <(os_arch); key="$([ "$os" = mac ] && echo osx || echo linux)-$arch"
  NATIVE_JAR="$dir/sherpa-onnx-native-lib-$key-$SHERPA_VER.jar"; export NATIVE_JAR
  [ -f "$NATIVE_JAR" ] && return 0
  say "downloading sherpa-onnx $SHERPA_VER native library for $key (~10 MB)"
  mkdir -p "$dir"
  download "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VER/sherpa-onnx-native-lib-$key-$SHERPA_VER.jar" "$NATIVE_JAR"
}
