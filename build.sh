#!/usr/bin/env bash
# builds vrfrida.apk (the Quest app) with no gradle: aapt2 + javac + d8 + apksigner from ~/Android/Sdk
#   ./build.sh            build
#   ./build.sh install    build and adb install -r
set -euo pipefail
cd "$(dirname "$0")"
MODS=..
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-34/android.jar
OUT=build
INJECT=dl/frida-inject           # official frida-inject android-arm64 (see README)
GADGET=dl/frida-gadget.so        # official frida-gadget android-arm64, put into games by the no-root mode
GADGET_VERSION=17.27.0
TOOLKIT_BRIDGE=$(ls ~/.local/share/pipx/venvs/il2cpp-toolkit/lib/python3*/site-packages/il2cpp_toolkit/assets/frida-il2cpp-bridge.js | head -1)

[ -f "$INJECT" ] || { echo "missing $INJECT"; exit 1; }
if [ ! -f "$GADGET" ]; then
    echo "== downloading frida-gadget $GADGET_VERSION"
    curl -fL "https://github.com/frida/frida/releases/download/$GADGET_VERSION/frida-gadget-$GADGET_VERSION-android-arm64.so.xz" -o "$GADGET.xz"
    xz -d "$GADGET.xz"
fi
# key the no-root mode signs patched games with. Keep it: repatching a game with the same key installs as an update
if [ ! -f patchkey/key.pk8 ]; then
    mkdir -p patchkey
    openssl req -x509 -newkey rsa:2048 -nodes -days 10000 -subj "/CN=vrfrida patch" -keyout patchkey/key.pem -out patchkey/cert.pem 2>/dev/null
    openssl pkcs8 -topk8 -nocrypt -in patchkey/key.pem -outform DER -out patchkey/key.pk8
    openssl x509 -in patchkey/cert.pem -outform DER -out patchkey/cert.der
fi
rm -rf "$OUT"; mkdir -p "$OUT"/{assets/seed/plugins,assets/seed/patterns,assets/seed/sounds,res,classes,apk/lib/arm64-v8a}

echo "== menu + bridge template"
(cd "$MODS" && frida-compile UniversalMenu.ts -S -B iife -T none -o "$OLDPWD/$OUT/assets/menu.js")
cp "$TOOLKIT_BRIDGE" "$OUT/assets/frida-il2cpp-bridge.js"
cp assets/appsafety_spoof.js "$OUT/assets/appsafety_spoof.js"
cp "$MODS/store/scan_rules.json" "$OUT/assets/scan_rules.json"   # store safety scan rules (the app also downloads newer ones)
# patchkey/ is NOT bundled: the app makes / loads a per-device key (ApkPatch.keyDir)

echo "== menu themes + backgrounds (runs the menu's own theme / background code in frida)"
FRIDA_PY=$(head -1 "$(command -v frida)" | sed 's/^#!//; s/ .*//')
"$FRIDA_PY" gen_menu_assets.py "$MODS/UniversalMenu.ts" "$OUT/assets/menu"

echo "== seed files (unpacked to /sdcard/vrfrida on first run)"
# LEAN=1 (release.sh): public download, so no plugins / sounds inside; the headset gets them from vrfrida.py --sync
cp "$MODS"/patterns/*.png "$OUT/assets/seed/patterns/" 2>/dev/null || true
if [ -z "${LEAN:-}" ]; then
    find "$MODS/imgui_plugins" -maxdepth 1 -type f \( -name '*.js' -o -name '*.txt' -o -name '*.png' \) -exec cp {} "$OUT/assets/seed/plugins/" \;
    find "$MODS/sounds" -maxdepth 1 -type f -exec cp {} "$OUT/assets/seed/sounds/" \;
fi

echo "== resources"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -A "$OUT/assets" -o "$OUT/base.apk" \
    "$OUT/res.zip" --java "$OUT/gen" --min-sdk-version 29 --target-sdk-version 34 \
    --version-code "$(cat version.txt 2>/dev/null || echo 1)" --version-name "$(cat version.txt 2>/dev/null || echo 1)" --replace-version -0 wav -0 mp3

echo "== java"
# version + update repo: version.txt (bumped by release.sh), update_repo.txt (owner/repo, empty = no default)
VERSION=$(cat version.txt 2>/dev/null || echo 1)
UPDATE_REPO=$(cat update_repo.txt 2>/dev/null | tr -d '[:space:]' || true)
# plugin store: store_repo.txt (owner/repo of universalmenu-store), uploads go to the chat_server Worker (CHAT_URL in the menu)
STORE_REPO=$(cat store_repo.txt 2>/dev/null | tr -d '[:space:]' || true)
STORE_UPLOAD=$(grep -oE '^const CHAT_URL *= *"[^"]*"' "$MODS/UniversalMenu.ts" | sed -E 's/.*"([^"]*)"/\1/')
mkdir -p "$OUT/gen/dev/vrfrida"
cat > "$OUT/gen/dev/vrfrida/BuildInfo.java" <<JAVA
package dev.vrfrida;
final class BuildInfo { static final String UPDATE_REPO = "$UPDATE_REPO"; static final int VERSION = $VERSION;
    static final String STORE_REPO = "$STORE_REPO"; static final String STORE_UPLOAD = "$STORE_UPLOAD"; }
JAVA
# apksig (Google's apksigner library) signs patched games on the headset
mkdir -p "$OUT/apksig" && (cd "$OUT/apksig" && unzip -qo "$BT/lib/apksigner.jar" 'com/android/apksig/*' && zip -qr ../apksig.jar com)
javac -source 11 -target 11 -Xlint:-options -encoding UTF-8 -classpath "$JAR:$OUT/apksig.jar" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')
"$BT/d8" --release --lib "$JAR" --min-api 29 --output "$OUT" $(find "$OUT/classes" -name '*.class') "$OUT/apksig.jar"

echo "== package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cp "$INJECT" "$OUT/apk/lib/arm64-v8a/libfrida-inject.so"
cp "$GADGET" "$OUT/apk/lib/arm64-v8a/libfrida-gadget.so"
(cd "$OUT" && zip -q unsigned.apk classes.dex)
# NOLIB=1 ./build.sh  -> test build without frida-inject (installs on an x86_64 emulator, for looking at the ui)
[ -n "${NOLIB:-}" ] || (cd "$OUT/apk" && zip -q -r ../unsigned.apk lib)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KS=vrfrida.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass vrfrida -keypass vrfrida -alias vrfrida \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=vrfrida" >/dev/null 2>&1
APK=vrfrida.apk; [ -n "${NOLIB:-}" ] && APK=vrfrida-emu.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:vrfrida --key-pass pass:vrfrida --out "$APK" "$OUT/aligned.apk"
ls -la "$APK"

if [ "${1:-}" = install ]; then adb install -r "$APK"; fi
