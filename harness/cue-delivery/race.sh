#!/bin/sh
# One instrumented race for the phone cue-delivery measurement (#210). Procedure, instrument and the
# measured baseline: docs/phone-cue-delivery.md — read that first; this is only the capture half.
#
#   ANDROID_SERIAL=<phone> TAP_Y=<start-button-y> sh race.sh <out-dir> [after-start-hook]
#
# Set the condition BEFORE running this. The race is started by tapping (540, TAP_Y) — screenshot the
# phone first and read where Start is: it sits lower on the GO! screen than on the idle 5:00 screen.
# The optional hook runs ~10 s after Start (the screen-off arm passes `adb shell input keyevent 223`).
#
# Captures, per race: the laptop mic (MIC, a dshow device name), the app's per-cue ToneManager log,
# a 1 s sampler of this app's player lines in `dumpsys audio`, the `dumpsys vibrator_manager` dump,
# and the device state before and after. Arm the cue log first:
#   adb shell setprop log.tag.ToneManager DEBUG   (disarm with ASSERT; an empty value is rejected)
set -u
OUT=$1
HOOK=${2:-}
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the phone's adb serial}"
: "${TAP_Y:?set TAP_Y to the Start button's y coordinate, read off a screenshot}"
MIC=${MIC:-Microphone (Shure MV7)}
PKG=io.github.sailordave17.racetimer
APPUID=$(adb shell dumpsys package $PKG 2>/dev/null | grep -m1 -oE 'appId=[0-9]+' | cut -d= -f2)
RACE_S=300
MIC_S=322
mkdir -p "$OUT"

state() {
  echo "at_local=$(date +%s%3N)"
  echo "zen_mode=$(adb shell settings get global zen_mode | tr -d '\r')"
  echo "mode_ringer=$(adb shell settings get global mode_ringer | tr -d '\r')"
  for s in 1 2 3 4 5 10; do
    echo "stream$s vol=$(adb shell cmd audio get-stream-volume $s | tr -d '\r' | sed 's/.*-> //') mute=$(adb shell cmd audio is-stream-mute $s | tr -d '\r')"
  done
  adb shell dumpsys power | grep -m1 -E 'mWakefulness=' | tr -d '\r'
  adb shell dumpsys battery | grep -m3 -E 'AC powered|USB powered|level' | tr -d '\r'
  adb shell dumpsys deviceidle | grep -m2 -E 'mState=|mLightState=' | tr -d '\r'
}

# Phone-minus-laptop clock offset, bracketed by the round trip.
l0=$(date +%s%3N); p=$(adb shell date +%s%3N | tr -d '\r'); l1=$(date +%s%3N)
echo "phone_minus_local_ms=$(( p - (l0 + l1) / 2 )) rtt_ms=$(( l1 - l0 ))" > "$OUT/clock.txt"
state > "$OUT/state-before.txt"

adb logcat -v epoch -T 1 -s ToneManager:D RaceTimerCueFault:V PhoneTimerService:V AndroidRuntime:E > "$OUT/logcat.txt" 2>&1 &
LC=$!

adb shell "while true; do echo \"=== \$(date +%s%3N)\"; dumpsys audio | grep -E 'u/pid:$APPUID/|piids' -A1 | grep -vE '^--\$'; sleep 1; done" > "$OUT/audio-samples.txt" 2>&1 &
SAMP=$!

echo "mic_start_local=$(date +%s%3N)" > "$OUT/mic.txt"
ffmpeg -hide_banner -loglevel error -y -f dshow -i "audio=$MIC" -ac 1 -ar 48000 -t $MIC_S "$OUT/mic.wav" &
MICPID=$!
sleep 4

echo "tap_local=$(date +%s%3N)" >> "$OUT/mic.txt"
adb shell input tap 540 "$TAP_Y"
sleep 3
adb exec-out screencap -p > "$OUT/started.png"

if [ -n "$HOOK" ]; then
  sleep 7
  echo "hook_local=$(date +%s%3N) hook=$HOOK" >> "$OUT/mic.txt"
  sh -c "$HOOK"
fi

# Wait out the race: 300 s from the tap, plus the 3 s gun and a margin.
while [ $(( $(date +%s%3N) - $(sed -n 's/^tap_local=//p' "$OUT/mic.txt") )) -lt $(( (RACE_S + 12) * 1000 )) ]; do
  sleep 2
done

adb exec-out screencap -p > "$OUT/ended.png"
state > "$OUT/state-after.txt"
adb shell dumpsys vibrator_manager > "$OUT/vibrator.txt"
adb shell dumpsys audio > "$OUT/audio-after.txt"
wait $MICPID
kill $SAMP $LC 2>/dev/null
echo "done $OUT"
