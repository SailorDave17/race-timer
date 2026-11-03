#!/bin/sh
# The companion link's device test (#294). What it proves and the measured runs: docs/companion-link.md.
#
#   ANDROID_SERIAL=<emulator> sh harness/companion-link/run.sh
#
# Run from the repo root, against an EMULATOR or a device you can wipe the companion from: it
# uninstalls whatever holds `com.procompanion.app`, because the stand-in takes that id. Never run it
# against a phone carrying the real companion and its log.
#
# Three states of the stand-in, each with the methods written for it, in this order:
#   TRUSTED   built here, debug-signed, and pinned by this build of race-timer (-PcompanionDebugCerts)
#   IMPOSTER  the same APK re-signed with a throwaway key, so only the certificate differs
#   ABSENT    not installed
# cueTimingHoldsWithTheLink runs in TRUSTED and ABSENT: criterion 2's "with and without".
#
# `am instrument` exits 0 when tests fail, so the verdict is read off its output, matched as a whole
# line (cairn `instrumented-tests-in-a-flutter-app`, section 4).
set -eu
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the emulator's adb serial}"

OUT=build/companion-link
mkdir -p "$OUT"
SDK=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties | sed 's/\\:/:/g; s/\\\\/\//g')}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
KEYTOOL=${JAVA_HOME:+$JAVA_HOME/bin/}keytool
# Functions, not strings: JAVA_HOME is under "Program Files" here, and a string splits on the space.
apksigner() { "$JAVA" -jar "$BT/lib/apksigner.jar" "$@"; }
# The SDK's own adb, not whatever PATH holds: on the machine this was written on PATH holds none,
# and prepending the SDK to PATH breaks on a drive letter's colon (cairn windows-shell-hazards 19).
ADB=${ADB:-$SDK/platform-tools/adb}
adb() { "$ADB" "$@"; }

STUB=harness/companion-link/stub/build/outputs/apk/debug/companion-stub-debug.apk
APP=phone/build/outputs/apk/debug/phone-debug.apk
TEST=phone/build/outputs/apk/androidTest/debug/phone-debug-androidTest.apk
RUNNER=io.github.sailordave17.racetimer.test/androidx.test.runner.AndroidJUnitRunner
CLASS=com.racetimer.phone.CompanionLinkDeviceTest

# 1. The stand-in first, so its certificate is the one this build of race-timer pins.
./gradlew :companion-stub:assembleDebug -q
DIGEST=$(apksigner verify --print-certs "$STUB" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ -n "$DIGEST" ] || { echo "no signer digest read from $STUB" >&2; exit 1; }
echo "stand-in signer: $DIGEST"

# 2. race-timer and its test APK, pinning that one extra key.
./gradlew :phone:assembleDebug :phone:assembleDebugAndroidTest -PcompanionDebugCerts="$DIGEST" -q

# 3. The imposter: the same APK, re-signed with a key made for it and kept out of the repo.
if [ ! -f "$OUT/imposter.jks" ]; then
  "$KEYTOOL" -genkeypair -keystore "$OUT/imposter.jks" -storepass imposter -keypass imposter \
    -alias imposter -keyalg RSA -keysize 2048 -validity 1 -dname "CN=imposter" >/dev/null 2>&1
fi
cp "$STUB" "$OUT/imposter.apk"
apksigner sign --ks "$OUT/imposter.jks" --ks-pass pass:imposter --key-pass pass:imposter "$OUT/imposter.apk"
IMPOSTER_DIGEST=$(apksigner verify --print-certs "$OUT/imposter.apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ "$IMPOSTER_DIGEST" != "$DIGEST" ] || { echo "the imposter carries the pinned key" >&2; exit 1; }
echo "imposter signer: $IMPOSTER_DIGEST"

adb install -r "$APP" >/dev/null
adb install -r "$TEST" >/dev/null

FAILED=0
run() { # <state> <method>
  log="$OUT/$1-$2.txt"
  # -e stand_in: which state this script installed, so a method that runs in two states cannot pass
  # by reading the other one (CompanionLinkDeviceTest.requireState).
  adb shell am instrument -w -r -e stand_in "$1" -e class "$CLASS#$2" "$RUNNER" > "$log" 2>&1 || true
  if tr -d '\r' < "$log" | grep -qx 'OK (1 test)'; then
    echo "PASS  $1  $2"
  else
    echo "FAIL  $1  $2  (see $log)"
    FAILED=$((FAILED + 1))
  fi
}

stand_in() { # <apk or nothing>
  adb uninstall com.procompanion.app >/dev/null 2>&1 || true
  [ -z "${1:-}" ] || adb install "$1" >/dev/null
}

adb logcat -c
stand_in "$STUB"
run TRUSTED trustedCompanionReceivesTheSequenceInOrder
run TRUSTED noCoreIsResentUnderTheSameId
run TRUSTED cueTimingHoldsWithTheLink

stand_in "$OUT/imposter.apk"
run IMPOSTER imposterIsSentNothing

stand_in ""
run ABSENT absentCompanionChangesNothing
run ABSENT cueTimingHoldsWithTheLink

adb logcat -d -s CompanionLinkTiming:I | tee "$OUT/timing.txt"
echo "failed: $FAILED"
[ "$FAILED" -eq 0 ]
