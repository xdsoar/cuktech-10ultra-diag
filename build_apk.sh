#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" || ! -d "$SDK_ROOT" ]]; then
  echo "Error: ANDROID_SDK_ROOT or ANDROID_HOME must point to a valid Android SDK directory." >&2
  exit 1
fi

# Automatically select latest available build-tools and android-34+ platform
BUILD_TOOLS_DIR="$(find "$SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
PLATFORM_DIR=""
if [[ -f "$SDK_ROOT/platforms/android-34/android.jar" ]]; then
  PLATFORM_DIR="$SDK_ROOT/platforms/android-34"
else
  PLATFORM_DIR="$(find "$SDK_ROOT/platforms" -mindepth 1 -maxdepth 1 -type d -name 'android-*' | sort -V | tail -n 1)"
fi

if [[ -z "$BUILD_TOOLS_DIR" || -z "$PLATFORM_DIR" ]]; then
  echo "Error: Could not find Android build-tools or platforms under $SDK_ROOT" >&2
  exit 1
fi

AAPT2="$BUILD_TOOLS_DIR/aapt2"
D8="$BUILD_TOOLS_DIR/d8"
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
ANDROID_JAR="$PLATFORM_DIR/android.jar"

WORK_DIR="${BUILD_WORK_DIR:-$APP_DIR/build}"
OUT_DIR="$APP_DIR/dist"
APK_NAME="cuktech-10ultra-diag-1.0.0.apk"

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/compiled_res" "$WORK_DIR/gen" "$WORK_DIR/classes" "$WORK_DIR/dex" "$WORK_DIR/test_classes" "$OUT_DIR"

echo "Using build-tools: $BUILD_TOOLS_DIR"
echo "Using platform:    $PLATFORM_DIR"

echo "[1/6] Running JVM unit tests (DiagProtocolTest)..."
javac --release 11 \
  -d "$WORK_DIR/test_classes" \
  "$APP_DIR/src/me/cuktech/ad1204u/diag/DiagProtocol.java" \
  "$APP_DIR/test/me/cuktech/ad1204u/diag/DiagProtocolTest.java"
java -cp "$WORK_DIR/test_classes" me.cuktech.ad1204u.diag.DiagProtocolTest

echo "[2/6] Compiling Android resources with aapt2..."
"$AAPT2" compile --dir "$APP_DIR/res" -o "$WORK_DIR/compiled_res/res.zip"
"$AAPT2" link \
  -I "$ANDROID_JAR" \
  --manifest "$APP_DIR/AndroidManifest.xml" \
  --java "$WORK_DIR/gen" \
  -o "$WORK_DIR/unaligned.apk" \
  "$WORK_DIR/compiled_res/res.zip"

echo "[3/6] Compiling Java sources..."
javac -source 8 -target 8 \
  -Xlint:deprecation -Xlint:unchecked \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -d "$WORK_DIR/classes" \
  "$WORK_DIR/gen/me/cuktech/ad1204u/diag/R.java" \
  "$APP_DIR/src/me/cuktech/ad1204u/diag/DiagProtocol.java" \
  "$APP_DIR/src/me/cuktech/ad1204u/diag/BleDiagClient.java" \
  "$APP_DIR/src/me/cuktech/ad1204u/diag/ReportImageRenderer.java" \
  "$APP_DIR/src/me/cuktech/ad1204u/diag/MainActivity.java"

echo "[4/6] Converting bytecode to DEX with d8 (min-api 23)..."
CLASS_FILES=()
while IFS= read -r -d '' f; do
  CLASS_FILES+=("$f")
done < <(find "$WORK_DIR/classes" -name "*.class" -print0)

"$D8" --release --min-api 23 --lib "$ANDROID_JAR" --output "$WORK_DIR/dex" "${CLASS_FILES[@]}"

cp "$WORK_DIR/unaligned.apk" "$WORK_DIR/unsigned.apk"
(cd "$WORK_DIR/dex" && zip -q "$WORK_DIR/unsigned.apk" classes.dex)

echo "[5/6] Zipaligning APK..."
"$ZIPALIGN" -f -p 4 "$WORK_DIR/unsigned.apk" "$WORK_DIR/aligned.apk"

KEYSTORE="${SIGN_KEYSTORE:-$WORK_DIR/ephemeral-release.jks}"
KEY_PASS="${SIGN_KEYPASS:-cuktech_diag_opensource_build}"
if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair -noprompt \
    -keystore "$KEYSTORE" \
    -storetype PKCS12 \
    -storepass "$KEY_PASS" \
    -keypass "$KEY_PASS" \
    -alias diag_release \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=CUKTECH 10 Ultra Offline Diag, OU=OpenSource, O=Community, C=CN"
fi

echo "[6/6] Signing APK (v1 + v2 + v3) and verifying..."
"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-pass "pass:$KEY_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$OUT_DIR/$APK_NAME" \
  "$WORK_DIR/aligned.apk"

"$APKSIGNER" verify --verbose "$OUT_DIR/$APK_NAME"
echo "=== Declared APK Permissions ==="
"$AAPT2" dump permissions "$OUT_DIR/$APK_NAME"
ls -lh "$OUT_DIR/$APK_NAME"
