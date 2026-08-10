# Timing Accuracy — Doze, Screen-Off, and What Actually Keeps the Countdown Running

What happens to cue timing when the watch is left alone: which mechanism holds the CPU awake, what
each one does *not* do, and where the countdown is allowed to sleep on purpose.

Answers [#126](https://github.com/SailorDave17/race-timer/issues/126), the residual of
[#10](https://github.com/SailorDave17/race-timer/issues/10) — whose Definition of Done asked for this
file and never got it. The measurement methodology is here rather than in the issue because a
limitation that is not written down gets rediscovered.

**Status:** the mechanism section is read off the source and is current as of this file's commit. The
measurements are from one SM-R925U (Wear OS 6, SDK 36) and are a sample, not a guarantee — see
*Limits of these measurements* at the end, which says plainly what this instrument cannot see.

## The short answer

**`OngoingActivity` does nothing for doze. The foreground service does not prevent it either. The
only thing holding the CPU awake is a `PARTIAL_WAKE_LOCK`, and cues use no wake-up strategy at all.**

#10 asked this as an either/or — *does the OngoingActivity + foreground service prevent doze entirely,
or do we rely on wake-up strategies for individual cues?* Neither limb is what the app does:

| Mechanism | What it actually does | Effect on doze |
|---|---|---|
| `OngoingActivity` | Presents the countdown on the watch face and in system UI | **None.** It is a notification-presentation API with no power-management behaviour whatsoever. |
| Foreground service | Keeps the process alive and exempt from background *execution* limits, so the app is not killed or frozen while backgrounded | **None directly.** It answers "may this app run?", not "is the CPU awake?" — a foreground service holds no wake lock, and a suspended device runs nothing regardless of what it is permitted to run. |
| `PARTIAL_WAKE_LOCK` | Holds the CPU out of suspend | **This is the whole of it.** Acquired in `TimerService.acquireWakeLock()`. |
| `FLAG_KEEP_SCREEN_ON` | Holds the *display* on while the activity is foreground, per `shared/ScreenPolicy.kt` | Incidental. A device with its screen on is not suspended, so this masks the question rather than answering it — see *How to measure this*. |
| `AlarmManager` / exact alarms | — | **Not used.** There is no wake-up strategy for individual cues. |

So the honest form of the answer is: **individual cues do not have their own wake-ups, and they do
not need one only for as long as the wake lock holds.** Everything below follows from that single
load-bearing assumption.

## Why the countdown is doze-proof but cue *dispatch* is not

These are two different things and the distinction is the reason the whole class of bug here is hard
to see.

**The countdown cannot drift.** `TimerEngine` anchors the gun to `elapsedRealtimeNanos()` via
`SystemMonotonicClock`, and every remaining-time read is `gunTimeMs - clock.elapsedMs()` computed
fresh. That clock advances through suspend. A watch that sleeps for four seconds and wakes shows the
correct time immediately, with no accumulated error, because nothing is accumulated — the time is
recomputed, never integrated.

**Dispatch can slip.** Both things that fire a cue run on the *uptime* clock, which stops during
suspend:

- `TimerService.scheduleNextCue()` — `handler.postAtTime(cueRunnable, SystemClock.uptimeMillis() + dueInMs)`
- `TimerService.tickRunnable` — `handler.postDelayed(this, TICK_INTERVAL_MS)`

If the CPU suspends with a cue pending, the scheduled wake-up does not arrive on time; it arrives when
the device next wakes for some other reason. The cue then fires late by roughly however long the
device slept, and the poll loop — the backstop — is asleep too, so it cannot recover it any sooner.

**And nothing looks wrong while this happens.** The displayed countdown stays exact throughout,
because it is recomputed from the monotonic anchor. This is the same signature as
[#58](https://github.com/SailorDave17/race-timer/issues/58), where cues were ±200 ms out for weeks
while the number on screen was never wrong: *the cue's intended time is exact, and only the moment of
noticing it is late.*

## The gap this file was written to find

The wake lock is acquired **once**, in `ACTION_START`, with a timeout sized from the countdown as it
stood at that instant:

```kotlin
val timeoutMs = engine.remainingMs.coerceAtLeast(0L) + WAKE_LOCK_MARGIN_MS  // margin = 30 s
```

**`ACTION_SYNC` moves the gun later and, until #126, did not re-arm it.** `TimerEngine.sync()` snaps
the remaining time to the nearest whole minute and re-anchors: `gunTimeMs = now + snapped`. Nearest
rounds *up* about half the time, by as much as 30 s. Sync is a button the race manager can tap
repeatedly, guarded only against a double-tap within one second, and nothing caps the total.

So two round-up syncs spend the entire 30 s margin, and the lock then expires with cues still pending.
A timed `PARTIAL_WAKE_LOCK` expires silently — no callback, no log, and `releaseWakeLock()` correctly
does nothing afterwards because `isHeld` is already false. From that moment the app is relying on the
watch happening not to sleep.

**Fixed in #126** by re-arming on sync:

```kotlin
if (engine.currentState == TimerState.RUNNING) acquireWakeLock()
```

`acquireWakeLock()` already releases before it acquires and re-sizes from the current `remainingMs`,
so the one line is the whole fix. It is unconditional within `RUNNING` rather than conditional on the
sync being accepted, because `sync()` refuses silently in two cases (during a lead-in, and under the
double-tap guard) and re-arming after a refused sync costs one binder call while missing an accepted
one is the bug.

Two engine tests in `TimerEngineTest` pin the premise — that a sync extends the race, and that
repeated syncs extend it without bound. Before them, **a mutation removing sync's ability to extend a
race at all left every one of the eleven existing sync tests green**, because they all exercise
round-*down* or exactly-on-the-minute cases.

## Where the app sleeps on purpose

`COUNTING_UP` — the race-committee elapsed-time mode after the gun — **deliberately releases the wake
lock**, in `TimerService.engineListener.onGun()`. This is correct, and #126's fourth criterion is about
confirming it is still correct:

- **There is nothing left to miss.** Every cue in every built-in sequence sits at `offsetMs >= 0` with
  the gun at 0, and cues fire in descending offset order — so by the time the engine reaches
  `COUNTING_UP` the queue is empty. There is no cue for a sleeping CPU to defer.

  **That is checked, not assumed.** `RaceSequenceTest` carries two guards added with this document:
  no built-in sequence has a cue at a negative offset, and every `countUpAfterFinish` sequence ends on
  its gun. Without them the paragraph above is a claim about data that anyone could invalidate by
  adding one cue, in a file that has no reason to mention doze — and the failure would be silent,
  because a cue scheduled into the count-up window is deferred rather than dropped.
- **The elapsed time cannot drift.** `remainingMs` stays on the live `gunTimeMs - clock.elapsedMs()`
  formula in `COUNTING_UP` (it is `RACE_ENDED` that freezes it), so elapsed race time is `-remainingMs`
  recomputed from the monotonic anchor. Sleeping for a minute costs nothing.
- **What it does cost is display freshness.** `tickRunnable` posts on the uptime clock, so while the
  device is suspended the ongoing notification stops updating. The value shown is stale, not wrong,
  and it corrects itself the moment anything wakes the watch — including the wearer looking at it.

`ScreenPolicy.keepsScreenOn` excludes `COUNTING_UP` for the matching reason recorded in
[#59](https://github.com/SailorDave17/race-timer/issues/59): a committee count-up is unbounded, an
hour is ordinary, and holding an OLED awake for that long is a battery cost with nothing to buy.

**This is a deliberate limitation, and it is the one to know:** after the gun in a race-manager
sequence, the watch is allowed to sleep and the displayed elapsed time may be seconds stale until you
look at it. Nothing is lost — the underlying time is exact — but a race committee reading the
notification without waking the watch is reading a value that stopped updating when the watch dozed.

## How to measure this

`setprop log.tag.TimerService DEBUG` turns on one line per cue:

```
cue offsetMs=300000 label=1 long errorMs=12 sleptMs=0 wakeLock=true screenOn=false
```

| Field | What it is | Why it is there |
|---|---|---|
| `errorMs` | How late the cue fired against the boundary the *sequence* put it on | The number #126's third criterion asks for. Positive is late. |
| `sleptMs` | Deep sleep since this race started | The control. Computed from the divergence of `elapsedRealtime()` and `uptimeMillis()`, which is the one signal here the app cannot influence by being wrong. |
| `wakeLock` | Whether the lock was still held when the cue fired | A timed lock expires silently, so without this "the lock worked" and "the lock was gone and the watch happened not to sleep" are the same observation. |
| `screenOn` | `PowerManager.isInteractive` | A screen-on run proves nothing about doze. This is what stops one being reported as if it did. |

`errorMs` and `sleptMs` are the pair that matters: a large `errorMs` with `sleptMs=0` is a scheduling
fault, and the same `errorMs` with `sleptMs` to match is doze. They want opposite fixes, and `errorMs`
alone cannot tell them apart.

**`sleptMs` has a ±1 ms noise floor — `0` and `-1` both mean "did not sleep".** The two clocks are
millisecond-truncated from independent sources and read by two separate calls, so their difference
jitters by a millisecond either way. Observed on hardware, mixed in among the zeroes of a run where the
CPU demonstrably never suspended. `deepSleepSinceMs` deliberately does not clamp at zero: a genuinely
negative reading of any size means the baseline came from a different boot, and clamping would report
that as the same clean `0` a perfectly held wake lock produces. Read the *magnitude* — single-digit is
noise, and doze arrives in hundreds or thousands.

### The trap: this is not `ToneManager`'s `cue lateMs=`

`ToneManager` logs a line that also starts with `cue ` and also contains a lateness in milliseconds.
**It is a different measurement and it cannot answer a doze question.** `ToneManager.playCue` captures
its baseline with `SystemClock.uptimeMillis()` *at the moment it is called* — that is, from inside
`onCue`, after the engine has already dispatched the cue. Its `lateMs` measures the tone thread waking
against that baseline, so everything this document is about has already happened before its stopwatch
starts. A cue deferred four seconds by a suspended CPU still reports a single-digit `lateMs` there.

Read `TimerService`'s line for timing-against-the-race, and `ToneManager`'s for timing-inside-a-cue.

### Getting the screen actually off

`ScreenPolicy.keepsScreenOn(RUNNING)` is `true`, so while the activity is in the foreground the
display stays on and the device cannot suspend — which means **a race run with the app on screen
cannot answer this question at all**. To reproduce the real case, background the app after tapping
Start (crown / `input keyevent KEYCODE_HOME`) and let the display time out naturally. That is also the
realistic scenario: a race manager starts the sequence and drops their wrist.

## Measurements

All on an SM-R925U, Wear OS 6 (SDK 36), on charger, `log.tag.TimerService DEBUG`, build
`wear-debug.apk` verified on-device by sha256 against the local build output.

### Run 0 — negative control: can this watch sleep at all while adb is attached?

**Run this first or the screen-off results below are unreadable.** An attached adb-over-Wi-Fi session
can hold a device awake, and if it does then `sleptMs=0` during a race means "adb prevented doze", not
"the wake lock worked" — a false pass that looks exactly like a real one.

Method: stop everything, screen off, issue **no** adb commands for the window, then compare the two
clocks `dumpsys batterystats` reports. Suspend time is `Δrealtime − Δuptime`.

```
17:44:24   Total run time: 1h 24m 11s 237ms realtime, 29m 25s 663ms uptime
17:46:40   Total run time: 1h 26m 25s 183ms realtime, 31m 26s 74ms uptime
```

| | Δ |
|---|---|
| realtime | 133.946 s |
| uptime | 120.411 s |
| **suspended** | **13.535 s** |

So the watch does suspend with adb connected, and `sleptMs` can report a non-zero value in this
setup. That is all this control needs to establish.

It is a **lower bound**, and the same command shows why: over the whole battery session the watch read
`Time on battery screen off: 1h 0m 52s realtime, 6m 6s uptime` — 90 % suspended when nobody was
talking to it, against 10 % here. Each adb command wakes the device, so an instrumented run
under-reports doze compared with a watch on someone's wrist. **The bias runs toward a clean result**,
which is the direction that should make you suspicious rather than satisfied.

### Run 1 — control: screen on, app in the foreground

Not a doze test. It exists to prove the instrument reports something real before five minutes are
spent on a run that depends on it — the numbers below are what "working correctly" looks like, and
they are the baseline every screen-off `errorMs` is read against.

US Sailing 5-4-1-Go, first 75 s:

| Cue | `offsetMs` | `errorMs` | `sleptMs` | `wakeLock` | `screenOn` |
|---|---|---|---|---|---|
| Warning — class flag up | 300000 | **4** | 0 | false | true |
| Sync — prep in 5 | 245000 | **2** | 0 | true | true |
| Sync — prep in 4 | 244000 | **1** | 0 | true | true |
| Sync — prep in 3 | 243000 | **3** | 0 | true | true |
| Sync — prep in 2 | 242000 | **2** | 0 | true | true |
| Sync — prep in 1 | 241000 | **2** | 0 | true | true |
| Preparatory — P/I/Z/U flag up | 240000 | **2** | 0 | true | true |

1–4 ms, consistent with the ±13 ms #58 established for the scheduled-cue path.

**`wakeLock=false` on the first cue is expected, not a defect.** `onStartCommand` calls
`engine.tick()` — which dispatches the first cue synchronously, because every sequence's first cue is
due at the instant the gun is anchored — *before* `acquireWakeLock()`. That ordering is deliberate and
was the fix for [#62](https://github.com/SailorDave17/race-timer/issues/62): putting the cue behind
persist + wake lock + `startForeground` measured ~170 ms of lateness. The device is unarguably awake
at that moment, since the sailor's finger is still on the Start button.


### Run 2 — the real one: full 5:00 sequence, backgrounded, screen off, untouched

US Sailing 5-4-1-Go, all 30 cues. Started 17:54:22, backgrounded and display off one second later,
no adb traffic and no interaction until after the gun at 17:59:21.

| | Result |
|---|---|
| Cues dispatched | **30 of 30**, none missed |
| `screenOn=false` | **29 of 30** (the exception is the first cue, fired while the finger is still on Start) |
| `wakeLock=true` | **29 of 30** (same exception, and expected — see Run 1) |
| `errorMs` | min **1**, median **2**, max **4**, mean 1.9 |
| `sleptMs` | **0 or 1 throughout** — never above the ±1 ms noise floor |
| End-to-end | first cue 17:54:21.321, gun 17:59:21.321 → **300.000 s against 300.000 s intended** |

`errorMs` distribution across the 30 cues: eleven at 1 ms, twelve at 2 ms, six at 3 ms, one at 4 ms.

**Against #10's own thresholds** — drift < 500 ms end-to-end, and every cue dispatched within ±250 ms
screen-off — the measured figures are **0 ms** and **≤ 4 ms**. Two orders of magnitude inside
tolerance, and the screen-off numbers are indistinguishable from the screen-on control in Run 1.

#### Corroborated independently

`sleptMs` is computed inside the app, so on its own it is the app marking its own homework.
`dumpsys batterystats` across the same window is an outside measure:

```
17:54:22   Total run time: 1h 34m 4s 328ms realtime, 38m 56s 997ms uptime
17:59:53   Total run time: 1h 39m 38s 892ms realtime, 44m 29s 948ms uptime
```

Δrealtime 334.564 s, Δuptime 332.951 s → **1.613 s suspended**. The two agree, and the residual
is placed rather than hand-waved: no cue in the race saw `sleptMs` above 1 ms, and the window extends
~32 s past the gun — during which the service tore down and released the wake lock. **The 1.6 s of
suspend is after the race, not during it.** That is the wake lock being released working correctly,
measured from outside the app.

#### What this run does and does not prove

It shows the wake lock doing exactly its job over a full sequence with the display off. It does
**not** show what happens when that lock is gone — Run 0 establishes the watch will suspend within
seconds once nothing holds it, which is why the `ACTION_SYNC` gap above mattered and why the field is
logged at all.

One honesty note on method: the display was put out with `KEYCODE_SLEEP` immediately after
backgrounding, rather than waiting out the system timeout. Backgrounding is what actually drops
`FLAG_KEEP_SCREEN_ON`; the keyevent only skips the wait that would have followed. The criterion's "no
interaction after start" holds — nothing touched the app, and nothing touched the watch between Start
and the gun.

### Run 3 — the sync fix, on hardware

`dumpsys power` prints each held wake lock with the age of its acquisition and its object handle, so
a re-arm is directly observable. US Sailing 5:00, synced 12 s after Start (remaining 4:48 → nearest
minute 5:00, so the gun moves ~12 s later — a round-up, the case that matters):

```
BEFORE sync   PARTIAL_WAKE_LOCK 'RaceTimer:TimerWakeLock' ACQ=-12s404ms  ... lock=520e6a7
AFTER  sync   PARTIAL_WAKE_LOCK 'RaceTimer:TimerWakeLock' ACQ=-2s420ms   ... lock=a0635f9
```

Two things, and the second is the one worth having:

- **The acquisition age reset**, 12.4 s → 2.4 s. The lock is now sized to the race as it stands after
  the sync rather than as it stood at Start.
- **The lock handle changed** (`520e6a7` → `a0635f9`) **and only one is listed.** So this is a genuine
  release-then-acquire, not a second lock stacked on the first. That distinction is not cosmetic:
  `acquireWakeLock()` releases first precisely because overwriting the field once orphaned the previous
  lock, leaving it held until its own timeout expired, and a second entry here is exactly what that
  regression would look like.

### Run 4 — `COUNTING_UP`: the state that is allowed to sleep

Scholastic - Race Manager (3:00), backgrounded and screen off from Start, then left in count-up for
two minutes with no interaction.

The countdown itself behaved as Run 2 did — the gun landed at `errorMs=2`, `screenOn=false`,
`wakeLock=true`. What this run is about is what happens *after* it:

| Check | Result |
|---|---|
| Wake lock 18 s after the gun | **released** — no `RaceTimer:TimerWakeLock` held |
| Wake lock 140 s after the gun | **still released** — it is not re-taken |
| Device suspended during count-up | **1.785 s** over 121.8 s (Δrealtime 121.825 s, Δuptime 120.040 s) |
| Elapsed time on waking, 18:09:29 | **3:09 displayed**, 3:08.3 expected from the gun at 18:06:20.742 — correct |

So all three halves of the design hold on hardware: the lock really is dropped at the gun, the watch
really does suspend afterwards, and **the elapsed time is exactly right when you look at it again**,
because it is recomputed from the monotonic anchor rather than accumulated.

One number is worth not over-reading. 1.785 s of suspend in two minutes (1.5 %) is far less than
Run 0's idle watch managed (13.5 s in 134 s, 10 %) — because `tickRunnable` is still running at 50 ms
and the ongoing notification still re-posts once a second, so the count-up state keeps waking the
device even without a lock. **That is a battery observation, not a correctness one**, and it is the
opposite of a reason to hold a lock here: the state is already cheap on timing and already unbounded
in length, which is exactly why #59 let it sleep.

## Limits of these measurements

- **One device, one OS version.** SM-R925U on Wear OS 6 (SDK 36). Doze aggressiveness is
  vendor-tunable; another watch may suspend sooner or harder.
- **An attached adb-over-Wi-Fi session can itself suppress deep sleep.** This is the instrument
  limitation that matters most, because it fails in the reassuring direction: it would produce
  `sleptMs=0` and clean `errorMs` on a build that would doze badly in the field. The negative control
  below exists to test exactly that, and no screen-off result here should be read without it.
- **`sleptMs` is a whole-device measure, not a per-cue one.** It reports suspend accumulated since the
  race started, so a cue's own deferral has to be read from the *increase* between consecutive cues.
- **Battery state changes the answer.** These runs were on a watch on its charger, which is the
  condition under which a watch dozes least. A low battery is the harsher case and is not covered.
