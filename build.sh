#!/usr/bin/env bash
# 펀치리스트 APK 빌드 스크립트 (Android Gradle 없이 SDK 도구로 직접 빌드)
# 필요: JDK, aapt, zipalign, apksigner, dx(dalvik-exchange), android-sdk-platform-23
#   Ubuntu: sudo apt-get install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_TOOL_OPTIONS=""

SDK=${ANDROID_SDK:-/usr/lib/android-sdk}
ANDROID_JAR=${ANDROID_JAR:-$SDK/platforms/android-23/android.jar}
DX=${DX:-$SDK/build-tools/debian/dx}
KS=${KEYSTORE:-keystore/punchlist.jks}
KS_PASS=${KEYSTORE_PASS:?KEYSTORE_PASS 환경변수를 지정하세요}
OUT=build
VERSION=$(grep -o 'versionName="[^"]*"' app/AndroidManifest.xml | cut -d'"' -f2)

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex" dist

echo "[1/5] javac"
javac -nowarn -Xlint:-options -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" $(find app/src -name '*.java')

echo "[2/5] dex"
"$DX" --dex --min-sdk-version=24 --output="$OUT/dex/classes.dex" "$OUT/classes"

echo "[3/5] aapt package"
aapt package -f -M app/AndroidManifest.xml -S app/res -A app/assets -I "$ANDROID_JAR" \
  -F "$OUT/unsigned.apk" -0 arsc
(cd "$OUT/dex" && aapt add ../unsigned.apk classes.dex >/dev/null)

echo "[4/5] zipalign"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[5/5] sign"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias punchlist \
    -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=PunchList, O=PunchList, C=KR"
fi
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --ks-key-alias punchlist \
  --out "dist/PunchList-v$VERSION.apk" "$OUT/aligned.apk"
rm -f "dist/PunchList-v$VERSION.apk.idsig"
apksigner verify "dist/PunchList-v$VERSION.apk"
echo "완료: dist/PunchList-v$VERSION.apk ($(du -h "dist/PunchList-v$VERSION.apk" | cut -f1))"
