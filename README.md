# Race Timer — Sailing Start-Sequence Timer

A precise, glanceable start-sequence timer for sailboat racing that runs **standalone on a Wear OS watch** (no phone required on the water).

## Features

### Timing Sequences
- **US Sailing 5-4-1-Go** (RRS 26) — 5-min sequence with Warning, Preparatory, One-minute, and Start signals
- **Scholastic / ICSA** — 3-min sequence with dense horn-blast cues (3L, 2L, 1L+3S, 1L, 3S, 2S, 1S × 6, 1L-Start)
- **Club 3-2-1-Go** — simple 3-min club racing sequence
- **Custom** — arbitrary duration with configurable intermediate cues

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
- **Keep-screen-on** — display stays on for the full sequence; clears the moment the sequence ends
- **State persistence** — gun time saved to `SharedPreferences`; restored if the process is killed mid-race

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
        │   ├── TimerService.kt         — foreground service, tick loop, haptics
        │   ├── HapticManager.kt        — signal → VibrationEffect patterns
        │   ├── RaceTimerApplication.kt — notification channel creation
        │   └── ui/
        │       ├── Theme.kt            — Wear Compose MaterialTheme
        │       ├── TimerScreen.kt      — main countdown face
        │       └── SequencePickerScreen.kt
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
| State | `SharedPreferences` (gun wall-clock) |
| Min SDK | 30 (Wear OS 3.5 / Android 11) |
| Target SDK | 34 |

## Roadmap

| Phase | Features |
|-------|---------|
| **MVP (current)** | Standalone Wear OS app — all 4 sequences, Sync, haptics, foreground service, keep-screen-on |
| V1.1 | Named custom presets, round-down sync toggle, mute/haptics settings, Wear Tile + complication |
| V1.2 | Android phone companion app (sequence picker, config, countdown mirror) |
| Later | Rolling/chained starts, mic airhorn auto-sync, OCS/recall handling |
