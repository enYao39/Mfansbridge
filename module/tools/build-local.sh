#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_DIR" || ! -d "$SDK_DIR" ]]; then
    echo "Set ANDROID_HOME or ANDROID_SDK_ROOT to an installed Android SDK." >&2
    exit 2
fi

USER_HOME_DIR="${HOME:-}"
GRADLE_ROOT="${GRADLE_USER_HOME:-$USER_HOME_DIR/.gradle}"
API_AAR_DIR="$GRADLE_ROOT/caches/modules-2/files-2.1/io.github.libxposed/api/102.0.0"
API_AAR="$(find "$API_AAR_DIR" -name 'api-102.0.0.aar' -print -quit 2>/dev/null || true)"
if [[ -z "$API_AAR" ]]; then
    echo "Cached libxposed API 102.0.0 AAR not found: $API_AAR_DIR" >&2
    exit 2
fi

BUILD_TOOLS="$SDK_DIR/build-tools/36.0.0"
ANDROID_JAR="$SDK_DIR/platforms/android-36/android.jar"
KEYSTORE="${DEBUG_KEYSTORE:-$USER_HOME_DIR/.android/debug.keystore}"
if [[ ! -x "$BUILD_TOOLS/aapt2" || ! -x "$BUILD_TOOLS/d8" || ! -f "$ANDROID_JAR" ]]; then
    echo "Android SDK platform 36 and build-tools 36.0.0 are required." >&2
    exit 2
fi
if [[ ! -f "$KEYSTORE" ]]; then
    echo "Debug signing key not found: $KEYSTORE (set DEBUG_KEYSTORE)." >&2
    exit 2
fi

WORK="app/build/manual"
rm -rf "$WORK"
mkdir -p "$WORK/api" "$WORK/classes" "$WORK/dex" "$WORK/generated/local/piliplus/push"
python3 - "$API_AAR" "$WORK/api/libxposed-api-102.jar" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as aar:
    with open(sys.argv[2], "wb") as output:
        output.write(aar.read("classes.jar"))
PY

mapfile -t JAVA_SOURCES < <(find app/src/main/java -type f -name '*.java' -print | sort)
if [[ -n "${BILIPUSH_CREDENTIALS_JSON:-}" ]]; then
    python3 - "$BILIPUSH_CREDENTIALS_JSON" \
        "$WORK/generated/local/piliplus/push/GeneratedBilibiliCredentials.java" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    values = json.load(source)
app_id = values["BILI_XIAOMI_APPID"]
app_key = values["BILI_XIAOMI_APPKEY"]
if not isinstance(app_id, str) or not isinstance(app_key, str):
    raise SystemExit("Bilibili metadata values must be strings")
with open(sys.argv[2], "w", encoding="utf-8") as output:
    output.write("package local.piliplus.push;\n")
    output.write("public final class GeneratedBilibiliCredentials {\n")
    output.write("  public static final String APP_ID_METADATA = " + json.dumps(app_id) + ";\n")
    output.write("  public static final String APP_KEY_METADATA = " + json.dumps(app_key) + ";\n")
    output.write("  private GeneratedBilibiliCredentials() {}\n")
    output.write("}\n")
PY
    JAVA_SOURCES+=("$WORK/generated/local/piliplus/push/GeneratedBilibiliCredentials.java")

    python3 - "$BILIPUSH_CREDENTIALS_JSON" \
        "$WORK/generated/local/piliplus/push/GeneratedBilibiliPushApiCredentials.java" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    values = json.load(source)

fields = {
    "APP_ID": ("BILI_PUSH_APP_ID", ""),
    "APP_KEY": ("BILI_PUSH_APP_KEY", ""),
    "SECRET_KEY": ("BILI_PUSH_SECRET_KEY", ""),
    "BUVID": ("BILI_PUSH_BUVID", ""),
    "MOBI_APP": ("BILI_PUSH_MOBI_APP", "android"),
    "BUILD": ("BILI_PUSH_BUILD", "9150300"),
    "CHANNEL_JSON": ("BILI_PUSH_CHANNEL_JSON", "{}"),
    "EXTRA_JSON": ("BILI_PUSH_EXTRA_JSON", "{}"),
    "LINK_SETTINGS_APP_KEY": ("BILI_LINK_SETTINGS_APP_KEY", ""),
    "LINK_SETTINGS_APP_SECRET": ("BILI_LINK_SETTINGS_APP_SECRET", ""),
}

resolved = {}
for field, (key, default) in fields.items():
    value = values.get(key, default)
    if not isinstance(value, (str, int)):
        raise SystemExit(f"{key} must be a string or integer")
    resolved[field] = str(value)

with open(sys.argv[2], "w", encoding="utf-8") as output:
    output.write("package local.piliplus.push;\n")
    output.write("public final class GeneratedBilibiliPushApiCredentials {\n")
    for field, value in resolved.items():
        output.write(f"  public static final String {field} = {json.dumps(value)};\n")
    output.write("  private GeneratedBilibiliPushApiCredentials() {}\n")
    output.write("}\n")
PY
    JAVA_SOURCES+=("$WORK/generated/local/piliplus/push/GeneratedBilibiliPushApiCredentials.java")
fi
java --module jdk.compiler/com.sun.tools.javac.Main \
    -source 17 -target 17 \
    -classpath "$ANDROID_JAR:$WORK/api/libxposed-api-102.jar" \
    -d "$WORK/classes" \
    "${JAVA_SOURCES[@]}"

"$BUILD_TOOLS/aapt2" compile \
    --dir app/src/main/res \
    -o "$WORK/resources.zip"

"$BUILD_TOOLS/aapt2" link \
    -I "$ANDROID_JAR" \
    --manifest app/src/main/AndroidManifest.xml \
    --min-sdk-version 26 \
    --target-sdk-version 35 \
    --version-code 18 \
    --version-name 0.1.17 \
    --debug-mode \
    -o "$WORK/base.apk" \
    "$WORK/resources.zip"

mapfile -t CLASS_FILES < <(find "$WORK/classes" -type f -name '*.class' -print | sort)
"$BUILD_TOOLS/d8" \
    --min-api 26 \
    --lib "$ANDROID_JAR" \
    --lib "$WORK/api/libxposed-api-102.jar" \
    --output "$WORK/dex" \
    "${CLASS_FILES[@]}"

cp "$WORK/base.apk" "$WORK/unaligned.apk"
(cd "$WORK/dex" && zip -0 -q "$PROJECT_ROOT/$WORK/unaligned.apk" classes.dex)
(cd app/src/main/resources && zip -0 -q -r "$PROJECT_ROOT/$WORK/unaligned.apk" META-INF/xposed)
"$BUILD_TOOLS/zipalign" -f 4 "$WORK/unaligned.apk" "$WORK/aligned.apk"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "${DEBUG_KEY_ALIAS:-androiddebugkey}" \
    --ks-pass "pass:${DEBUG_KEYSTORE_PASSWORD:-android}" \
    --key-pass "pass:${DEBUG_KEY_PASSWORD:-android}" \
    --out "$WORK/PiliPlusPush-debug.apk" \
    "$WORK/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --verbose "$WORK/PiliPlusPush-debug.apk"

mkdir -p app/build/outputs/apk/debug
cp "$WORK/PiliPlusPush-debug.apk" app/build/outputs/apk/debug/PiliPlusPush-debug.apk
echo "Built: app/build/outputs/apk/debug/PiliPlusPush-debug.apk"
