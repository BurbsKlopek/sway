#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_FILE="$ROOT_DIR/app/build.gradle.kts"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$ROOT_DIR"
chmod +x ./gradlew

if [ "${1:-}" = "--bump" ]; then
  python3 - "$GRADLE_FILE" << 'PY'
import re, sys

path = sys.argv[1]
txt = open(path, "r", encoding="utf-8").read()

code_m = re.search(r"versionCode\s*=\s*(\d+)", txt)
name_m = re.search(r'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"', txt)
if not code_m or not name_m:
    raise SystemExit("Could not find versionCode/versionName in build.gradle.kts")

next_code = int(code_m.group(1)) + 1
next_name = f"{name_m.group(1)}.{name_m.group(2)}.{int(name_m.group(3)) + 1}"

txt = re.sub(r"versionCode\s*=\s*\d+", f"versionCode = {next_code}", txt, count=1)
txt = re.sub(r'versionName\s*=\s*"\d+\.\d+\.\d+"', f'versionName = "{next_name}"', txt, count=1)
open(path, "w", encoding="utf-8").write(txt)
print(f"==> Bumped version to v{next_name} (versionCode {next_code})")
PY
fi

VERSION_NAME="$(python3 -c 'import re,sys; print(re.search(r"versionName\s*=\s*\"([^\"]+)\"", open(sys.argv[1]).read()).group(1))' "$GRADLE_FILE")"
VERSION_CODE="$(python3 -c 'import re,sys; print(re.search(r"versionCode\s*=\s*(\d+)", open(sys.argv[1]).read()).group(1))' "$GRADLE_FILE")"
echo "==> Building Sway v${VERSION_NAME} (versionCode ${VERSION_CODE})..."

echo "==> Running unit tests..."
./gradlew testDebugUnitTest

echo "==> Building signed Release APK..."
./gradlew assembleRelease

OUT_DIR="$HOME/Downloads/Android Development"
mkdir -p "$OUT_DIR"
OUT_APK="$OUT_DIR/Sway-v${VERSION_NAME}.apk"

rm -f "$OUT_DIR"/Sway-v*.apk "$OUT_DIR/FlickDeck-TV-v1.0.0.apk"
cp "$ROOT_DIR/app/build/outputs/apk/release/app-release.apk" "$OUT_APK"

APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
if [ -x "$APKSIGNER" ]; then
  echo "==> Verifying APK signature..."
  "$APKSIGNER" verify --verbose "$OUT_APK" | head -n 12
fi

echo "==> Done! Release APK copied to:"
ls -lh "$OUT_APK"
