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

Every claim above was checked against the tree on 2026-08-01, **re-checked on 2026-08-09** after PRs
#113, #116 and #132 had merged, and **re-checked again on 2026-08-11** after #126, #13 and #72 moved
`TimerService.kt`:

- `wear/src/main/AndroidManifest.xml` declares exactly `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `VIBRATE`. No `INTERNET`, no
  location, no body sensors, no boot-completed receiver. The only manifest addition since the first
  check is `uses-feature android.hardware.audio.output required="false"` (from #95/#132) — a feature,
  not a permission, so no claim above is affected.
- `TimerService.kt` returns `START_NOT_STICKY` (`:620`; see the comment there and the *Foreground
  service* section of `CLAUDE.md` for why — a sticky restart arrives with a null intent, matches no
  branch, and `startForeground()` would never run).
- The wake lock is `PARTIAL_WAKE_LOCK` (`:814`), acquired with `remainingMs + WAKE_LOCK_MARGIN_MS`
  (`:812`, margin `30_000L` at `:1079`) and released in `releaseWakeLock()` (`:819`) on teardown.
  Since #126 it is re-acquired on `ACTION_SYNC` as well as `ACTION_START` (`:583`, `:554`): a snap
  re-anchors the gun, and a lock sized at Start protects the race the sailor started rather than the
  one they now have. Neither claim in the declaration moves — the lock is still sized to the race
  actually left to run, and still released at teardown.
- `OngoingActivity` is built and posted for the life of the service (`:769`).
- The timing claim is deliberately phrased as "a few tens of milliseconds" rather than a hard number.
  The measured figure is **±13 ms** for cue dispatch (hardware-measured, recorded in the cairn repo at
  `memory/projects/race-timer-cue-audio-timing-2026-08-01.md`, down from ±200 ms when cues were polled
  for every 50 ms), and mid-race cues measure 3–58 ms against their own deadlines. The **first cue of
  every race** used to miss by **138–297 ms**, because the track was paused and flushed after each cue
  and `play()` then re-paid `startOutput`. That was **#114**, and it **closed 2026-08-11**: the first
  cue now measures **0–2 ms** across four full races, with **#98** closing behind it the same day.

  **The hedge stays anyway, and the reason has changed.** It is no longer that an open bug contradicts
  the number — it is that ±13 ms is a *median-shaped* figure and a Play declaration is a **bound**.
  The same #114 run recorded one cue at `lateMs=66` and documented a `queuedMs=10` ceiling for a cue
  written while a heartbeat chunk is draining. A worst case in the tens of milliseconds is exactly
  what "a few tens of milliseconds" already says, so the current wording is accurate as written;
  replacing it with ±13 ms would make it false.

  *Tightening this claim is still **#82**'s job, not this document's* — and #82 is now **startable**,
  which it was not on 2026-08-09. It is worded "once #61 and #62 close"; both closed long ago, but the
  hedge outlived them because #114 replaced their reason rather than removing it. #114 has now closed
  too, so nothing blocks #82 but its own measurement. Note its ACs demand the **worst case** measured
  on hardware from the **audible cue** — the 66 ms figure above is the candidate bound, not the 0–2 ms
  headline.

Two notes on strategy:

- **Internal testing does not get this level of review.** This text is written now because the design
  reasoning is fresh, and because it gates the later production path. It is not blocking the internal
  upload (#79).
- If a reviewer does push back, the strongest single fact is **no `INTERNET` permission**. Lead with
  it in any appeal. The second strongest is that the service cannot start without a user tap.

If the app ever gains network access, a boot receiver, or a health-sensor read, this document is wrong
and the declaration has to be rewritten before that version ships.
