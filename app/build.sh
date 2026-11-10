#!/bin/bash
# Builds and signs BudsBatteryFix.apk with the Android SDK in ~/tools/android-sdk and the Kotlin
# compiler in ~/tools/kotlin/kotlinc. Set ANDROID_SDK or KOTLIN_HOME to use them from somewhere else.
# Set KS_PASS to your own keystore password; a new keystore.jks is created with it the first time.
# The supervisor script in ../device/ is bundled into the app, so the app can install it on the device.
# A fresh build directory is used each time; nothing is ever deleted.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_SDK:-$HOME/tools/android-sdk}"
KOTLIN="${KOTLIN_HOME:-$HOME/tools/kotlin/kotlinc}"
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
KS=keystore.jks
KS_PASS="${KS_PASS:-change-me}"
VERSION_NAME=1.0
VERSION_CODE=$(( $(date +%s) / 60 ))   # epoch minutes: always increases, never a downgrade

B=build/$(date +%Y%m%d-%H%M%S)
mkdir -p "$B/classes" "$B/gen" "$B/assets"
cp ../device/budsfix.sh "$B/assets/"

"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml -A "$B/assets" \
    --min-sdk-version 34 --target-sdk-version 35 --version-code $VERSION_CODE --version-name $VERSION_NAME \
    --java "$B/gen" "$B/res.zip"
javac -Xlint:-options -source 11 -target 11 -cp "$JAR" -d "$B/classes" "$B/gen/local/budsfix/R.java"
"$KOTLIN/bin/kotlinc" -jvm-target 11 -no-reflect -cp "$JAR:$B/classes" -d "$B/classes" \
    $(find src/kotlin -name '*.kt') 2>&1 | grep -vE "^warning:|^w: " || true
test -f "$B/classes/local/budsfix/MainActivity.class" || { echo "Kotlin compilation failed"; exit 1; }
"$BT/d8" --min-api 34 --lib "$JAR" --output "$B" $(find "$B/classes" -name '*.class') "$KOTLIN/lib/kotlin-stdlib.jar"
python3 -I -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "a").write(sys.argv[2], "classes.dex")' \
    "$B/unsigned.apk" "$B/classes.dex"
"$BT/zipalign" -p -f 4 "$B/unsigned.apk" "$B/aligned.apk"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias budsfix \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Buds Battery Fix (local build)"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --out BudsBatteryFix.apk "$B/aligned.apk"
"$BT/apksigner" verify BudsBatteryFix.apk
echo "Built $(pwd)/BudsBatteryFix.apk (version $VERSION_NAME, code $VERSION_CODE)"
