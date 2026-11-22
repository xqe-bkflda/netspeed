#!/usr/bin/env bash
# build.sh <project-dir>  → 输出 build/<name>.apk
set -euo pipefail
. ./env.sh
PROJ=${1:?用法: build.sh <项目目录>}
cd "$PROJ"
NAME=$(basename "$PROJ")
B=build
ASSETS="$(pwd)/assets"
rm -rf "$B"; mkdir -p "$B/gen" "$B/classes" "$B/dex"

echo "[1/6] aapt2 compile"
[ -d res ] && "$ANDROID_AAPT2" compile --dir res -o "$B/res.zip" || : > "$B/empty"

echo "[2/6] aapt2 link"
LINK_ARGS=(-o "$B/base.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml
  --java "$B/gen" --auto-add-overlay
  --min-sdk-version "$ANDROID_MIN_SDK" --target-sdk-version "$ANDROID_TARGET_SDK")
[ -f "$B/res.zip" ] && LINK_ARGS+=("$B/res.zip")
"$ANDROID_AAPT2" link "${LINK_ARGS[@]}"

echo "[3/6] javac"
SRC=$(find src "$B/gen" -name '*.java' 2>/dev/null || true)
[ -z "$SRC" ] && { echo "没有 java 源文件"; exit 1; }
javac -source 8 -target 8 -nowarn -bootclasspath "$ANDROID_JAR" -d "$B/classes" $SRC

echo "[4/6] d8"
java -cp "$ANDROID_D8" com.android.tools.r8.D8 --lib "$ANDROID_JAR" \
  --min-api "$ANDROID_MIN_SDK" --output "$B/dex" $(find "$B/classes" -name '*.class')

echo "[5/6] package+zipalign"
python3 - "$B/base.apk" "$B/dex/classes.dex" "$B/unsigned.apk" "${ASSETS:-}" <<'PY'
import sys, zipfile, shutil, os
base, dex, out = sys.argv[1:4]
assets = sys.argv[4] if len(sys.argv) > 4 else ""
shutil.copy(base, out)
with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
    if assets and os.path.isdir(assets):
        for root, dirs, files in os.walk(assets):
            for f in files:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, os.path.dirname(assets.rstrip("/")))
                z.write(full, rel.replace(os.sep, "/"))
PY
"$ANDROID_ZIPALIGN" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

echo "[6/6] sign"
"$ANDROID_APKSIGNER" sign --ks "$ANDROID_KS" --ks-pass pass:android --key-pass pass:android \
  --out "$B/$NAME.apk" "$B/aligned.apk"
"$ANDROID_APKSIGNER" verify "$B/$NAME.apk" && echo "签名 OK"
echo "产物: $(pwd)/$B/$NAME.apk  ($(du -h "$B/$NAME.apk" | cut -f1))"
