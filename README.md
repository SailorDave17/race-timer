# Race Timer — Sailing Start-Sequence Timer

A precise, glanceable start-sequence timer for sailboat racing that runs **standalone on a Wear OS watch** (no phone required on the water).

## Features

### Timing Sequences
- **US Sailing 5-4-1-Go** (RRS 26) — 5-min sequence with Warning, Preparatory, One-minute, and Start signals
- **Scholastic / ICSA** — 3-min sequence with dense horn-blast cues (3L, 2L, 1L+3S, 1L, 3S, 2S, 1S × 6, 1L-Start)
- **Scholastic — Race Manager** — the same Scholastic/ICSA opening (3L, 2L, 1L+3S, 1L) for the race committee rather than a sailor, but its own cadence below the minute: 3S/2S/1S at 0:30/0:20/0:10, then single ticks at 0:05 through 0:01 (no 0:50/0:40 warnings, nothing between 0:09 and 0:06, and the final five aren't doubled the way a sailor's countdown doubles them). Once the gun fires it doesn't reset: the watch keeps running as an elapsed-time race clock (up to `H:MM:SS`), the screen is free to sleep, and a foreground service keeps timing in the background until **End Race** is tapped.
- **Club 3-2-1-Go** — simple 3-min club racing sequence
- **Custom** — any whole number of minutes, set on the watch (minimum 1:00, no maximum). One long blast on every whole minute from the top down to and including 1:00, then the Scholastic/ICSA cadence below the minute (0:50/0:40 ticks, 3S/2S/1S at 0:30/0:20/0:10, single ticks 0:10–0:06, doubled final five) and the same sustained gun — so an unfamiliar duration still ends in a countdown you already race to

### Sync Button
Tap **Sync** at any point during the countdown to snap to the nearest whole minute — absorbs your reaction-time lag when watching the Race Committee's flag. Round-to-nearest by default; round-down available as a toggle.

### Haptics-First Watch UI
- Big high-contrast MM:SS readout readable in bright sun
- Color-state background: navy → amber (last minute) → red flash (final 10 s) → green (gun)
- Distinct haptic patterns per signal: long buzz for long blasts, quick tap for short blasts, triple-buzz for the gun
- Large **Sync** and **Stop** buttons; one swipe for sequence selection

### Reliability
- **Monotonic clock** — anchored to `elapsedRealtimeNanos()`, immune to NTP/wall-clock changes
- **Foreground service + Ongoing Activity** — countdown survives screen-off and app backgrounding
- **Keep-screen-on** — display stays on for the full countdown; clears the moment the sequence ends, or the moment the gun fires for **Scholastic — Race Manager**, so its elapsed-time count-up can run with the screen asleep
- **State persistence** — the running gun time is snapshotted to `SharedPreferences`. A killed process is restored **exactly** (the monotonic anchor survives, immune to NTP). After a device restart the timer is recovered best-effort from wall-clock and prompts you to tap **Sync** to confirm against the flag. The snapshot names the sequence as well as the time, so a **Custom** race comes back at its own duration rather than as a built-in. Reopening after a kill shows that race's own clock, still counting, and offers **Resume** or **Start over** instead of deciding for you — for every sequence, including the race-manager count-up.

## Project Structure

```
race-timer/
├── shared/           # Pure Kotlin timer engine (no UI dependency)
│   └── src/
│       ├── main/kotlin/com/racetimer/shared/
│       │   ├── RaceSequence.kt   — sequence & cue data models, built-in sequences
│       │   └── TimerEngine.kt    — monotonic engine, sync, state persistence
│       └── test/                 — unit tests (JVM, no device needed)
└── wear/             # Wear OS standalone app
    └── src/main/
        ├── kotlin/com/racetimer/wear/
        │   ├── MainActivity.kt         — Compose UI, service binding, screen-on
        │   ├── TimerService.kt         — foreground service, tick loop, cue feedback
        │   ├── HapticManager.kt        — signal → VibrationEffect patterns
        │   ├── ToneManager.kt          — audible alert beep paired with each haptic
        │   ├── RaceTimerApplication.kt — notification channel creation
        │   └── ui/
        │       ├── Theme.kt            — Wear Compose MaterialTheme
        │       ├── TimerScreen.kt      — main countdown face
        │       ├── SequencePickerScreen.kt
        │       └── CustomDurationScreen.kt — whole-minute stepper for the Custom sequence
        └── res/
```

## Build

### Requirements
- Android Studio Hedgehog (2023.1) or newer, **or** VS Code with the Gradle for Java extension
- Android SDK with:
  - Wear OS emulator image (API 30 / Wear OS 3.5+)
  - Build-tools 34

### Commands
```bash
# Build the Wear OS debug APK
./gradlew :wear:assembleDebug

# Run shared module unit tests (no device needed)
./gradlew :shared:test

# Install on a connected Wear OS device / emulator
./gradlew :wear:installDebug
```

> **VS Code users**: install the *Gradle for Java* and *Kotlin* extensions. Use the Gradle sidebar to run tasks, or the terminal with `./gradlew`. For the emulator, launch Android Studio's AVD Manager once to create a Wear OS virtual device.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 1.9 |
| Build | Gradle 8.4 / AGP 8.2 |
| Watch UI | Jetpack Compose for Wear OS 1.3 |
| Navigation | Wear Compose Navigation |
| Timing | `SystemClock.elapsedRealtime()` (monotonic) |
| Background | Android `ForegroundService` + Wear `OngoingActivity` |
| State | `SharedPreferences` (boot-anchored gun snapshot) |
| Min SDK | 30 (Wear OS 3.5 / Android 11) |
| Target SDK | 34 |

## Roadmap

| Phase | Features |
|-------|---------|
| **MVP (current)** | Standalone Wear OS app — 4 built-in sequences (US Sailing, Scholastic, Scholastic — Race Manager, Club), Sync, haptics, foreground service, keep-screen-on |
| V1.1 | Named custom presets, round-down sync toggle, mute/haptics settings, Wear Tile + complication |
| V1.2 | Android phone companion app (sequence picker, config, countdown mirror) |
| Later | Rolling/chained starts, mic airhorn auto-sync, OCS/recall handling |
