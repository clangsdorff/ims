#!/bin/bash
# Usage: module/build.sh <signed privapp apk> <output zip>
set -euo pipefail

APK=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
OUT=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
HERE=$(cd "$(dirname "$0")" && pwd)

SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar

BADGING=$("$BT"/aapt2 dump badging "$APK")
MANIFEST=$("$BT"/aapt2 dump xmltree --file AndroidManifest.xml "$APK")
[[ $BADGING == *"name='com.langsdorff.flossims'"* ]] || { echo "not a com.langsdorff.flossims apk" >&2; exit 1; }
[[ $MANIFEST != *sharedUserId* ]] || { echo "use the privapp flavor, this apk shares the system uid" >&2; exit 1; }
"$BT"/apksigner verify "$APK" >/dev/null

W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT

"$BT"/aapt2 compile --dir "$HERE/overlay-framework/res" -o "$W/res.zip"
"$BT"/aapt2 link -I "$JAR" --manifest "$HERE/overlay-framework/AndroidManifest.xml" -o "$W/rro.apk" "$W/res.zip"
"$BT"/zipalign -f -p 4 "$W/rro.apk" "$W/rro-aligned.apk"
# the overlay carries no privileges, so the public debug key is enough
"$BT"/apksigner sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey \
    --out "$W/FlossImsFrameworkOverlay.apk" "$W/rro-aligned.apk"

M=$W/module
mkdir -p "$M/system/priv-app/FlossIms" "$M/system/product/overlay"
cp -R "$HERE/META-INF" "$HERE/system" "$HERE/module.prop" "$HERE/service.sh" "$HERE/system.prop" "$M/"
cp "$APK" "$M/system/priv-app/FlossIms/FlossIms.apk"
cp "$W/FlossImsFrameworkOverlay.apk" "$M/system/product/overlay/"

rm -f "$OUT"
(cd "$M" && zip -qr -X "$OUT" .)
unzip -l "$OUT"
