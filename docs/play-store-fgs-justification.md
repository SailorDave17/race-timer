# Play Console — `FOREGROUND_SERVICE_SPECIAL_USE` justification

Text for the foreground-service declaration in Play Console's **App content** section, for
`com.racetimer.wear` (Race Timer for Wear OS). Tracked as issue #74.

`specialUse` is the type Play scrutinises hardest, because it is the escape hatch. Reviewers push
back when they believe a standard type would have served, so the argument below spends most of its
length on the negative case rather than on describing the feature.

---

## Declaration text (paste into Play Console)

### What the foreground service does

Race Timer is a start-sequence timer for sailboat racing, worn on the wrist. A race start is a fixed
countdown — commonly five minutes — during which the app must produce audible tones and haptic
signals at exact, predetermined offsets. The final signal is the starting gun. A sailor crosses the
line on that signal, and a boat that crosses early is penalised.

The foreground service exists to run that countdown and dispatch those signals. It is started by an
explicit user action (tapping **Start**) and stops when the sequence ends, or when the user taps
**Stop** or **End Race**.

### Why it must run in the foreground

Two reasons, both about accuracy rather than convenience:

1. **The screen is off for most of a race start.** The sailor is watching the race committee boat and
   handling the boat, not looking at their wrist. The watch display sleeps within seconds. If the app
   is suspended when the display sleeps, the cues stop and the sailor misses the start.

2. **The timing tolerance is tight.** Cues are scheduled against a monotonic clock rather than polled
   for, and are dispatched within a few tens of milliseconds of their scheduled offsets — a tolerance
   we measure on real hardware. A background-restricted or frozen process cannot hold that, and a gun
   signal that arrives a second late is a wrong race result. Sub-second accuracy is the entire product,
   not a nice-to-have.

The service holds a `PARTIAL_WAKE_LOCK` sized to the remaining race time plus a small margin, and
releases it as soon as the sequence ends. It does not hold a wake lock when no race is running.

### Why no standard foreground service type applies

Each standard type was considered and does not fit:

- **`mediaPlayback`** — the app produces short alert tones, not media. There is no media session, no
  playback queue, no transport controls, and nothing a user would recognise as playing content.
  Declaring `mediaPlayback` would be a misdeclaration of what the app does, and would expose media
  controls that have no meaning here.

- **`dataSync`** — nothing is synchronised. The app has no network access at all; it does not declare
  the `INTERNET` permission and contains no networking or third-party SDK code.

- **`location`** — no location is used or requested. The app declares no location permission.

- **`health`** — no body sensors, heart rate, or fitness data are read. The app declares no health or
  body-sensor permission. Although it is a sports app, it measures time, not the athlete.

- **`shortService`** — the type is capped well below the length of a race start. A standard sequence
  runs five minutes, custom sequences have no upper limit, and the app's race-committee mode continues
  as an elapsed-time race clock after the gun for the full duration of a race, which can be an hour or
  more.

- **`microphone`, `camera`, `phoneCall`, `connectedDevice`, `remoteMessaging`, `mediaProjection`** —
  none of these describe any part of the app's behaviour, and none of the corresponding permissions
  are declared.

- **`systemExempted`** — not available to a third-party application.

The app's function is a precise, user-initiated, time-bounded countdown with alert output. There is no
standard type for that, which is precisely the case `specialUse` exists to cover.

### Scope and user control

- The service **only** runs while a race sequence is running or a race clock is counting up after the
  gun. It starts on an explicit tap and never starts on its own.
- It does **not** start at boot and declares no boot-completed receiver.
- It returns `START_NOT_STICKY`, so the system does not restart it on its own after a process death.
  A race interrupted by a process kill is recovered only when the user next opens the app and chooses
  to resume it.
- It posts an ongoing-activity notification for the entire time it runs, so the user can always see
  that a race is running and return to it.
- It has **no network access**, so it cannot transmit anything. This removes the entire class of
  abuse that scrutiny of `specialUse` is designed to catch.

### Manifest subtype value

```
Racing timer — keeps the start-sequence running while the screen is off
```

---

## Maintainer notes — not for Play Console

Every claim above was checked against the tree on 2026-08-01:

- `wear/src/main/AndroidManifest.xml` declares exactly `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `VIBRATE`. No `INTERNET`, no
  location, no body sensors, no boot-completed receiver.
- `TimerService.kt` returns `START_NOT_STICKY` (see the comment there and the *Foreground service*
  section of `CLAUDE.md` for why — a sticky restart arrives with a null intent, matches no branch,
  and `startForeground()` would never run).
- The wake lock is `PARTIAL_WAKE_LOCK`, acquired with `remainingMs + WAKE_LOCK_MARGIN_MS` and released
  in `releaseWakeLock()` on teardown.
- `OngoingActivity` is built and posted for the life of the service.
- The timing claim is deliberately phrased as "a few tens of milliseconds" rather than a hard number.
  The measured figure is **±13 ms** for cue dispatch (hardware-measured, recorded in the bmad repo at
  `memory/projects/race-timer-cue-audio-timing-2026-08-01.md`, down from ±200 ms when cues were polled
  for every 50 ms). But **#62 is open**: the *first* cue of a race currently fires ~170 ms after the
  countdown anchor, and **#61** tracks tone overshoot. Quoting ±13 ms to Play would be stating as
  shipped behaviour something an open bug contradicts. Understate it; the argument does not need the
  precision, only the fact that sub-second accuracy is the point of the app.

Two notes on strategy:

- **Internal testing does not get this level of review.** This text is written now because the design
  reasoning is fresh, and because it gates the later production path. It is not blocking the internal
  upload (#79).
- If a reviewer does push back, the strongest single fact is **no `INTERNET` permission**. Lead with
  it in any appeal. The second strongest is that the service cannot start without a user tap.

If the app ever gains network access, a boot receiver, or a health-sensor read, this document is wrong
and the declaration has to be rewritten before that version ships.
