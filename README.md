# Mad Cow Race Timer — Sailing Start-Sequence Timer

A precise, glanceable start-sequence timer for sailboat racing that runs **standalone on a Wear OS
watch** (no phone required on the water).

Two audiences, one app: a **sailor** counting down to the gun, and a **race committee** sounding the
signals the fleet is counting down to. The race-manager sequences are not a re-skin — they are voiced
differently, they keep running after the gun as an elapsed-time race clock, and they can be started
in step with an external signal box.

📖 **[Full documentation is in the wiki](https://github.com/SailorDave17/race-timer/wiki)** — signal
tables, the race-manager modes, architecture, and troubleshooting.

## Features

### Timing sequences

| Sequence | Length | For | After the gun |
|---|---|---|---|
| **US Sailing 5-4-1-Go** (RRS 26) | 5:00 | Sailor | Resets |
| **US Sailing — Race Manager** | 5:00 | Committee | Counts up |
| **Scholastic (ICSA)** | 3:00 | Sailor | Resets |
| **Scholastic — Race Manager** | 3:00 | Committee | Counts up |
| **Club 3-2-1-Go** | 3:00 | Sailor | Resets |
| **Custom** | any whole minutes, min 1:00 | Sailor | Resets |

Full cue-by-cue tables are on the wiki: **[Race Sequences](https://github.com/SailorDave17/race-timer/wiki/Race-Sequences)**.
`shared/src/main/kotlin/com/racetimer/shared/RaceSequence.kt` is the authoritative source if anything
disagrees with the app.

- **US Sailing sounds long above the minute and short below it.** A long blast is a signal the
  committee is sounding; a short one is the wrist counting. The three marks that move a flag —
  warning at 5:00, prep up at 4:00, prep down at 1:00 — are doubled, so they stand apart from the
  plain minute reminders at 3:00 and 2:00. (It was short blasts throughout until #117, on the
  reasoning that long blasts are hard to count on the wrist; sailing the sequence on the water
  settled that the other way.)
- **Every sequence shares the last five seconds** — 0:05 to 0:01 doubled — so a sailor never has to
  remember which sequence is loaded to know what the final five mean.
- **Custom** encodes its duration in the sequence id (`custom_8m`), which is what lets a killed
  process come back at the right duration rather than as a built-in.

### Race-manager modes

**US Sailing — Race Manager** and **Scholastic — Race Manager** are the committee side of their
sailor sequences. They differ in three ways that matter:

- **Re-voiced for someone sounding the signals**, not counting them — 1 long at the marks the horn
  sounds, silence at the cross-check marks a sailor uses (3:00 and 2:00 on US Sailing; 0:50 and 0:40
  on Scholastic), and no doubled final five.
- **The gun is not the end.** The clock keeps running as an elapsed-time race clock (up to `H:MM:SS`),
  the screen is free to sleep, and a foreground service keeps timing until **End Race** is tapped —
  which freezes the final time on screen rather than resetting.
- **A signal-box lead-in.** See below.

### Signal-box lead-in

On the committee boat the watch is not the only thing making noise: the fleet hears the signal box,
the race manager hears their wrist. Whatever gap a thumb introduces between starting the two is a gap
the fleet races to for the whole sequence, and no mid-run Sync can close it.

So the watch waits — in **two** stages, because a box does not begin its sequence when you press it:

```
4:10  tap Start
  |   PREP — 10 s to get a hand to the box. Last five seconds tick.
4:00  PRESS THE BOX — a distinct stutter cue, the one cue that must be acted on
  |   ALERT WINDOW — the box sounds its own alert here; the watch stays silent
3:00  the sequence's own first signal, and confirmation the two agree
  |   ...the sequence exactly as it always was...
0:00  gun
```

The alert window is the **box's** setting, not a total — that is the number printed on the mode chart
on the back of the unit. Presets are **none / 15 s (iStart Dinghy) / 60 s (iStart Rule 26)**, or dial
any value from 5 s to 2:00. Reached through the **Lead-in** button, which appears next to Start on
race-manager sequences only: a sailor has no box to sync to, and a sailor who triggers it by accident
starts their race late.

Like Custom, the setting lives inside the sequence id (`scholastic_race_manager_alert60s`), so a
process death during the run-up comes back on the right clock.

### Sync button

Tap **Sync** during the countdown to snap to the nearest whole minute — this absorbs the
reaction-time lag between the Race Committee's flag reaching the top of the staff and your thumb
landing. Round-to-nearest by default.

Sync is deliberately **unavailable during a lead-in**: there is nothing to snap to yet, and snapping
4:07 to 4:00 on a 3:00 sequence would silently delete seven seconds of the very run-up the lead-in
exists to protect. The button is removed rather than disabled, because a control that takes the tap
and does nothing reads as broken.

### Three cue voices

A cue's tone and its vibration read the same pattern and land on the same blast boundaries, so a cue
sounds the shape it feels. What kind of thing a cue *is* rides on a separate axis:

| Voice | What it is | How it reads |
|---|---|---|
| **Blast** | A race signal — what the committee is sounding | 500 ms long / 150 ms short, full strength |
| **Sync** | A wrist-only tick counting *into* a signal | 60 ms, reduced amplitude — never mistakable for a signal |
| **Prompt** | An instruction to the *wearer* to act now | 5 × 40 ms at full strength — a stutter read as one event, not a count |

The prompt is a texture rather than a count on purpose: `3 short` is a real cue at 0:30 of the very
sequence a lead-in precedes, and a race manager who counts the prompt as a blast pattern has misread
an instruction as a signal.

### Haptics-first watch UI

- Big high-contrast MM:SS readout, driven to **maximum panel brightness** while a race is on screen
- Colour-state background: navy → amber (last minute) → red flash (final 10 s) → green (gun)
- Distinct haptic patterns per signal, per the voice table above
- Large **Sync** / **Stop** buttons, **End Race** in race-manager count-up; one swipe to the picker
- A transient banner below the readout for notices that need no action, and a persistent status line
  under the sequence name for ones that do — see [`docs/message-surface.md`](docs/message-surface.md)

### Reliability

- **Monotonic clock** — anchored to `elapsedRealtimeNanos()`, immune to NTP and wall-clock changes
- **Rendered cue audio** — each cue is rendered to PCM and written to a reused `AudioTrack` in one
  piece, so blast lengths are exactly what `CueTiming` states. The `ToneGenerator` path this replaced
  treated its duration as a *cap*: 500 ms delivered 512 ms five times and 520 ms once in the same race
- **Scheduled cues, not polled** — cues are scheduled against the anchor rather than sampled by a
  tick loop, which took cue accuracy from ±200 ms to ±13 ms on hardware. **±13 ms is a median, not a
  bound** — the #114 run recorded one cue at `lateMs=66`, with a documented `queuedMs=10` ceiling on
  top of it, so the defensible worst case is nearer **66 ms**. Anywhere the number is read as a
  *guarantee* rather than a description — a Play declaration, the store listing, anything a reviewer
  or a sailor sees — say **sub-second** instead of quoting this figure. That is the whole of
  [#82](https://github.com/SailorDave17/race-timer/issues/82), and the reasoning is worked through in
  [`docs/store/listing.md`](docs/store/listing.md)
- **Foreground service + Ongoing Activity** — the countdown survives screen-off and backgrounding
- **Screen policy is a table, not a habit** — keep-awake and max-brightness are two pure functions of
  timer state in `shared/ScreenPolicy.kt`, and they deliberately disagree on exactly one state so a
  test can assert the divergence
- **State persistence** — the gun time is snapshotted to `SharedPreferences`. A killed process is
  restored **exactly** (the monotonic anchor survives). After a device restart the timer is recovered
  best-effort from wall-clock and prompts you to tap **Sync** to confirm against the flag. Reopening
  after a kill offers **Resume** or **Start over** rather than deciding for you

## Project structure

```
race-timer/
├── shared/           # Pure Kotlin — no Android dependency, JVM-testable
│   └── src/
│       ├── main/kotlin/com/racetimer/shared/
│       │   ├── RaceSequence.kt   — cue/sequence models, the six built-in sequences
│       │   ├── TimerEngine.kt    — monotonic engine, sync, state machine, persistence
│       │   ├── LeadIn.kt         — the two-stage signal-box run-up and its id encoding
│       │   ├── CueTiming.kt      — blast/tick/prompt durations, shared by both channels
│       │   ├── CueWaveform.kt    — a cue rendered to PCM
│       │   ├── ScreenPolicy.kt   — keep-awake and max-brightness, per timer state
│       │   ├── RestorePlan.kt    — what a saved race may be restored to, and when
│       │   ├── CountdownFormat.kt— MM:SS and H:MM:SS rendering
│       │   └── BannerLayout.kt   — notice geometry inside a round screen
│       └── test/                 — JVM unit tests, no device needed
└── wear/             # Wear OS standalone app
    └── src/main/
        ├── kotlin/com/racetimer/wear/
        │   ├── MainActivity.kt         — Compose UI, service binding, screen policy
        │   ├── TimerService.kt         — foreground service, cue scheduling, feedback
        │   ├── HapticManager.kt        — cue → VibrationEffect waveform
        │   ├── ToneManager.kt          — cue → rendered AudioTrack buffer
        │   ├── SystemMonotonicClock.kt — the engine's clock, on Android
        │   ├── RaceTimerApplication.kt — notification channel creation
        │   └── ui/
        │       ├── Theme.kt
        │       ├── TimerScreen.kt            — countdown face
        │       ├── SequencePickerScreen.kt
        │       ├── CustomDurationScreen.kt   — whole-minute stepper for Custom
        │       ├── LeadInPickerScreen.kt     — box-alert presets
        │       └── LeadInDurationScreen.kt   — dialled box-alert value
        └── res/
```

The dependency direction is one-way and worth preserving: `wear` depends on `shared`, never the
reverse. Keeping `shared` free of Android types is what lets the whole timing core run on the JVM in
seconds with no emulator — and it is where the rules that would otherwise get written twice, and
drift, are made assertable.

## Build

### Requirements

- Android Studio Hedgehog (2023.1) or newer, **or** VS Code with the Gradle for Java extension
- JDK 17 (AGP 8.x refuses anything lower); `:shared` declares a JVM 8 toolchain, which
  `settings.gradle.kts` resolves via the Foojay plugin rather than requiring a local install
- Android SDK with a Wear OS emulator image (API 30 / Wear OS 3.5+) and Build-tools 34

### After cloning

```bash
git config core.hooksPath githooks   # then verify:
git config core.hooksPath            # must print: githooks
```

`core.hooksPath` lives in `.git/config`, which is **not tracked**, so a fresh clone runs none of the
push protection in [`githooks/`](githooks/) — the branch gate and the pre-push check list both. An
unset value produces no error and no output: every push simply succeeds, exactly as it would with the
hook working and the checks passing. **Verify by printing the config, never by pushing**, because a
successful push is what both states look like.

### Commands

```bash
# Run shared module unit tests (JVM only — no device needed). Fast feedback loop.
./gradlew :shared:test

# Build the Wear OS debug APK
./gradlew :wear:assembleDebug

# Build the release bundle — runs R8, so it catches shrinker breakage the debug build cannot
./gradlew :wear:bundleRelease

# Install on a connected Wear OS device / emulator
./gradlew :wear:installDebug
```

Those first three are exactly what CI enforces on every pull request — see
[`.github/workflows/ci.yml`](.github/workflows/ci.yml). `bundleRelease` joined the gate in #129 so
that R8 runs on every push rather than only when somebody remembered.

It behaves differently here than on CI, which matters when you run the gate locally: this machine has
a `keystore.properties` and therefore signs, while CI has none and builds unsigned — see
[`docs/release-signing.md`](docs/release-signing.md). A `bundleRelease` failure here is not
automatically a CI failure.

> **VS Code users**: install the *Gradle for Java* and *Kotlin* extensions and use the checked-in
> `race-timer.code-workspace`. For the emulator, launch Android Studio's AVD Manager once to create a
> Wear OS virtual device.

Deploying to a real watch — pairing over adb-over-Wi-Fi, and confirming which APK actually landed —
is in [`docs/watch-setup.md`](docs/watch-setup.md). Proving that a race killed mid-sequence comes back
— the force-stop procedure, the four scenarios it splits into, and what each run measured — is in
[`docs/process-kill-test.md`](docs/process-kill-test.md).

## Tech stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 1.9.22 |
| Build | Gradle 8.9 / AGP 8.6.1 |
| Watch UI | Jetpack Compose for Wear OS 1.3 |
| Navigation | Wear Compose Navigation |
| Timing | `SystemClock.elapsedRealtimeNanos()` (monotonic) |
| Cue audio | `AudioTrack`, PCM rendered per cue |
| Background | Android `ForegroundService` (`specialUse`) + Wear `OngoingActivity` |
| State | `SharedPreferences` (boot-anchored gun snapshot) |
| Min SDK | 30 (Wear OS 3.5 / Android 11) |
| Compile / Target SDK | 35 |

## Status and roadmap

The app is feature-complete for its own use and runs on hardware. The current push is **getting it
distributable** — [epic #66](https://github.com/SailorDave17/race-timer/issues/66), Google Play
internal testing.

| Milestone | Scope |
|---|---|
| **Shipped** | Six sequences including both race-manager modes, signal-box lead-in, Sync, rendered cue audio, scheduled cues, foreground service, screen policy, restore-after-kill |
| **Play internal testing** ([#66](https://github.com/SailorDave17/race-timer/issues/66)) | Developer account, upload keystore, icon set, store listing and screenshots, privacy policy, App content declarations, first internal build |
| **Shipped toward that** | `compileSdk`/`targetSdk` 35 ([#116](https://github.com/SailorDave17/race-timer/pull/116), closing [#69](https://github.com/SailorDave17/race-timer/issues/69)) on the AGP 8.6.1 / Gradle 8.9 toolchain ([#111](https://github.com/SailorDave17/race-timer/pull/111), closing [#68](https://github.com/SailorDave17/race-timer/issues/68)) — the 2026-08-31 Wear OS deadline is met |
| **Known open defects** | Under Do Not Disturb the watch loses **both** channels, so the gun never fires ([#144](https://github.com/SailorDave17/race-timer/issues/144)), with no pre-start warning that the cues will be silent ([#96](https://github.com/SailorDave17/race-timer/issues/96)); a cue dropped or truncated mid-race says nothing ([#161](https://github.com/SailorDave17/race-timer/issues/161)); the Settings remedy cannot clear the foreground-service block it raises ([#165](https://github.com/SailorDave17/race-timer/issues/165)). The display can still stick 180° off — a device fault, not the app: [#115](https://github.com/SailorDave17/race-timer/issues/115) is closed but **the remedy did not hold**, and [#147](https://github.com/SailorDave17/race-timer/issues/147) runs the control arm that would settle the cause |
| **Later** | Named custom presets, round-down sync toggle, Wear Tile + complication, phone companion, rolling/chained starts |

The Google Play account the app publishes under — and why publishing from a different one would create
a separate app — is in [`docs/play-store-account.md`](docs/play-store-account.md).

---

> **On-water disclaimer.** Race Timer is a training and convenience aid. Under the Racing Rules of
> Sailing the Race Committee's **visual** signals are definitive; sound signals are only for
> attention. Sail the flags, not the watch.
