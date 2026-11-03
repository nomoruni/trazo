#!/usr/bin/env bash
# Compila los APK de Trazo (debug y release) usando el toolchain local.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/android-tools/jdk-17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

GRADLE="$HOME/android-tools/gradle-8.9/bin/gradle"
if [ -x "$ROOT/gradlew" ]; then
  GRADLE="$ROOT/gradlew"
fi

cd "$ROOT"

echo "==> JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
echo "==> SDK: $ANDROID_HOME"

"$GRADLE" --console=plain assembleDebug

mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/trazo-debug.apk
echo
ls -lh dist/*.apk
echo "APK listo: $ROOT/dist/trazo-debug.apk"
