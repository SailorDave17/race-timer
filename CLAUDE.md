# CLAUDE.md

Race Timer — a standalone Wear OS start-sequence timer for sailboat racing. See `README.md` for
architecture, build commands, and roadmap.

## Wear OS constraints

**Never set `android:screenOrientation` on the Wear activity** in `wear/src/main/AndroidManifest.xml`.

Wrist and button placement on Wear OS are a system 180° rotation. `fullSensor` makes the activity take
its rotation from the accelerometer instead, so the app ignores that system value and renders 180° off
from the rest of the watch UI. It looks exactly like the watch reverting to default wrist placement,
but nothing system-side changes — the app cannot write those settings at all (they are
`Settings.Global`, behind `WRITE_SECURE_SETTINGS`, which is `signature|privileged`).

Added in `82c8283`, reverted in `133f5bf`. **That revert's stated rationale is wrong** — it claims
fullSensor was "inert, empirically verified on the Wear emulator," but the emulator has no real
accelerometer and cannot reproduce the bug. Do not re-add the attribute on the strength of an emulator
test.

Verify any orientation change on the physical watch, and only with a **non-zero** wrist setting: at
factory default (`0`) the buggy and fixed builds look identical.

## Foreground service

`TimerService.onStartCommand` returns `START_NOT_STICKY` deliberately. A sticky restart arrives with a
null intent, which matches no branch in the `when`, so `startForeground()` would never run and
Android 12+ kills the process with `ForegroundServiceDidNotStartInTimeException`. There is nothing for
a restart to do regardless — a race is recovered from the persisted snapshot when the sailor next taps
Start, via the `ACTION_START` restore path.

## Testing

- `./gradlew :shared:test` — pure-JVM engine tests, no device needed. Keep timer logic in `shared/` so
  it stays testable this way.
- `./gradlew :wear:installDebug` — deploy to the watch.
- To confirm what actually landed, compare hashes rather than trusting the build log:
  `adb shell pm path com.racetimer.wear`, pull it, sha256 against
  `wear/build/outputs/apk/debug/wear-debug.apk`.

Watch pairing details and the adb pair-port gotcha are recorded in the bmad repo at
`memory/reference/wear-os-adb-pairing-2026-07-30.md`.
