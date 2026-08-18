# Play Console — `FOREGROUND_SERVICE_SPECIAL_USE` justification

Text for the foreground-service declaration in Play Console's **App content** section, for
`io.github.sailordave17.racetimer` (Race Timer for Wear OS). Tracked as issue #74.

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
   for, and are dispatched within 100 ms of their scheduled offsets, with the audible tone following
   within 150 ms — a tolerance measured across 150 cues in five full sequences on real hardware. A
   background-restricted or frozen process cannot hold that, and a gun signal that arrives a second
   late is a wrong race result. Sub-second accuracy is the entire product, not a nice-to-have.

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

**The 2026-08-18 pass (#82) re-measured the timing bullet only.** It did not re-verify the manifest,
wake-lock or `START_NOT_STICKY` bullets, and the `TimerService.kt` line numbers below still date from
2026-08-11 — #200 has moved the audio path to `:shared-android` since, so treat them as unverified
rather than current. Saying which bullet a dated pass covered is the point: a re-check date attached
to a whole document vouches for claims nobody looked at.

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
- The timing claim now states a measured bound on **two axes**, because the cue has two and they
  differ. `TimerService`'s `errorMs` is how late the cue *fired* against the boundary the sequence put
  it on; `ToneManager`'s `lateMs` is how late the **audible start** was against the moment the tone was
  due. Neither alone answers "how far from the mark did the sailor hear it", and the sum does.

  **Measured 2026-08-18 on build `f1f3bf1`** (SM-R925U, Wear OS 6 / SDK 36, `:shared-android` in place
  after #200), **150 cues across five full US Sailing 5-4-1-Go sequences** — cold and warm process,
  and both audio routes, since a silenced watch reroutes to `STREAM_MUSIC` under #95:

  | | median | p90 | worst |
  |---|---|---|---|
  | Cue dispatch (`errorMs`) | 2 ms | 15 ms | **61 ms** |
  | Audible start vs. its scheduled offset (`errorMs` + `lateMs`) | 11 ms | 35 ms | **61 ms** |
  | Tone onset, including the deliberate 40 ms `LEAD_IN_MS` | — | — | **101 ms** |

  All 150 cues dispatched; **no cue delivered fewer frames than were loaded**, and the gun delivered
  144000 frames = 3000 ms exactly in all five races. `writeMs` stayed 0–8 ms throughout, so #114's fix
  is holding and the residual lateness is tone-thread scheduling contention (`wakeMs`), not audio-server
  cost.

  **The declaration's numbers are deliberately looser than the measurement** — 100 ms and 150 ms against
  measured worsts of 61 ms and 101 ms. A bound quoted at the observed maximum is falsified by the next
  device, which is the failure this whole story existed to avoid; the headroom is the point, and the
  exact figures live here where a reviewer's own test can only confirm them.

  **`LEAD_IN_MS` is a design offset, not an error.** The tone is written to sound 40 ms after the cue
  fires so it lands *with* the haptic rather than answering it. It is counted into the third row anyway,
  because a sailor hears one event and the honest bound is measured from the mark.

  **What this still does not measure, and the hedge that survives because of it.** Nothing here times
  sound leaving the speaker — `ToneManager.logDispatch`'s own docblock says so: *"Neither times when it
  emerged from the speaker, which only an ear settles."* So the third row is an estimate of tone onset
  inside the app, not an acoustic measurement. The owner listened to a full sequence on the wrist on
  2026-08-18 against this build and reported buzz and blast arriving as **one event**, which is the only
  instrument that reaches that last gap and is why the numbers above may be stated at all.

  **The ±13 ms figure is retired as a claim about this app.** It was a median from #58's scheduled-cue
  work, correct when taken and measured on a code path that no longer exists — before #114, before #200
  moved the audio out of `wear/`. It is superseded by the table above rather than being wrong.

Two notes on strategy:

- **Internal testing does not get this level of review.** This text is written now because the design
  reasoning is fresh, and because it gates the later production path. It is not blocking the internal
  upload (#79).
- If a reviewer does push back, the strongest single fact is **no `INTERNET` permission**. Lead with
  it in any appeal. The second strongest is that the service cannot start without a user tap.

If the app ever gains network access, a boot receiver, or a health-sensor read, this document is wrong
and the declaration has to be rewritten before that version ships.

**Since #83 that sentence is enforced rather than trusted, and it is worth knowing exactly which half.**
The paragraph above was the only thing standing between a new permission and a Play declaration that
had stopped being true — a guard that fires only if the person adding the permission happens to open
this file. `docs/declared-surface.lock` now snapshots both apps' externally-visible surface and
`.github/scripts/declared-surface.py` checks it as the first Gradle step in CI, naming this document
as one of three to re-check before the lock may be regenerated.

Three of this document's claims are now covered by that check, and one deliberately is not:

- **Covered.** The permission list (`INTERNET`, location, body sensors), read from the **merged**
  release manifests rather than the source ones, so a permission injected by a dependency is caught
  too. The *"no boot-completed receiver"* claim, since every `<receiver>` and its exported state is
  locked — and the merged manifests already carry an androidx receiver the source files never
  mention, which is the case this claim was previously asserted against by inspection. And the
  `specialUse` type together with the **subtype string** quoted verbatim in the *Manifest subtype
  value* section above, so editing it in one place and not the other fails the build.
- **Covered, and worth stating separately because it is the appeal argument.** *"No network access,
  so it cannot transmit anything"* rests on the dependency graph as well as the manifest. The lock
  records release-runtime coordinates for all four modules, so adding a networking or analytics SDK
  fails CI — *measured 2026-08-18*, adding okhttp to `wear/build.gradle.kts` was refused and the
  failure named okio's two transitives as well.
- **NOT covered: the timing bullet.** The 100 ms and 150 ms bounds under *Why it must run in the
  foreground* are a measurement, not a declaration, and nothing in a manifest or a dependency list
  can falsify them. They are re-measured by a race on a wrist (#82) and by nothing else. A green
  declared-surface check says nothing about them, and reading it as though it did would be worse
  than having no check.
