#!/bin/sh
# One pair race for #223: the laptop mic, and both devices' pair and cue logs. Procedure, placement and
# the results: docs/pair-hardware.md. The analysis half is analyze.py beside this.
#
#   PHONE=<serial> WATCH=<serial> sh capture.sh <out-dir> <seconds>
#
# Start it, then hand over the tap: the mic records for <seconds> from the moment it starts. Arm the cue
# log on both devices first, or the gun's dispatch line is missing from the logs (the mic still hears it):
#   adb -s <serial> shell setprop log.tag.ToneManager DEBUG   (disarm with ASSERT; an empty value is refused)
set -u
OUT=$1
SECS=$2
: "${PHONE:?set PHONE to the phone's adb serial}"
: "${WATCH:?set WATCH to the watch's adb serial}"
MIC=${MIC:-Microphone (Shure MV7)}
mkdir -p "$OUT"

# Each device's wall clock minus the laptop's, bracketed by the adb round trip. It maps log lines onto
# the recording to within that bracket, which is enough to find a gun and never enough to time one:
# the skew comes from the mic alone.
clock() {
  for d in "$PHONE" "$WATCH"; do
    l0=$(date +%s%3N); v=$(adb -s "$d" shell date +%s%3N | tr -d '\r'); l1=$(date +%s%3N)
    echo "$1 serial=$d minus_local_ms=$(( v - (l0 + l1) / 2 )) rtt_ms=$(( l1 - l0 ))"
  done
}

clock before > "$OUT/clock.txt"
TAGS="RaceTimerPair:V RaceTimerPairStart:V ToneManager:D AndroidRuntime:E"
adb -s "$PHONE" logcat -v epoch -T 1 -s $TAGS PhoneTimerService:V RaceTimerPhoneApp:V > "$OUT/phone.log" 2>&1 &
LP=$!
adb -s "$WATCH" logcat -v epoch -T 1 -s $TAGS TimerService:V RaceTimerApplication:V > "$OUT/watch.log" 2>&1 &
LW=$!

echo "mic_start_local=$(date +%s%3N)" > "$OUT/mic.txt"
ffmpeg -hide_banner -loglevel error -y -f dshow -i "audio=$MIC" -ac 1 -ar 48000 -t "$SECS" "$OUT/mic.wav"
echo "mic_end_local=$(date +%s%3N)" >> "$OUT/mic.txt"

clock after >> "$OUT/clock.txt"
kill $LP $LW 2>/dev/null
echo "done $OUT"
