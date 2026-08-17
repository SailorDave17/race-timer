# Process-Kill Test — Verifying Restore on a Real Watch

How to kill Race Timer mid-sequence on a real watch and prove the race comes back — or, where it must
not, prove it does not. Written so [#125](https://github.com/SailorDave17/race-timer/issues/125) has a
procedure a future session can follow instead of re-deriving one, and so the line between what the JVM
suite already settles and what only the watch can is on the record rather than assumed.

The reboot variant is a different test with a different mechanism and lives in
[#122](https://github.com/SailorDave17/race-timer/issues/122) — a reboot destroys the monotonic domain
and forces the wall-clock reconstruction, which is the whole of what that test is about. **This file is
the same-boot process-kill variant**, where the monotonic clock survives and the restore should be
exact.

## What the unit suite already settles, and what it cannot

The restore decisions are pure functions in `shared/`, and they are covered:

| Question | Where it is decided | Where it is tested |
|---|---|---|
| Do the persisted values amount to a race at all? | `RestorePlan.snapshotFrom` | `RestorePlanTest` |
| What does the pre-start screen open on, and is Resume offered? | `RestorePlan.launchPlan`, `TimerEngine.resumeOfferRemainingMs` | `RestorePlanTest`, `TimerEngineTest` |
| Does a tap on Start resume, or run from the top? | `RestorePlan.startPlan` | `RestorePlanTest` |
| What does resuming put on the clock, and which cues are still to come? | `TimerEngine.restore`, `gunTimeFromSnapshot` | `TimerEngineTest` |

Count them rather than trusting a number written here:

```sh
grep -c '@Test' shared/src/test/kotlin/com/racetimer/shared/RestorePlanTest.kt
grep -n '@Test fun `' shared/src/test/kotlin/com/racetimer/shared/TimerEngineTest.kt \
  | grep -iE 'restore|snapshot|resumeOffer|remainingFrom'
```

Both edges this document tests on hardware have a JVM counterpart already passing — `restore after gun
results in FINISHED`, `restore after the gun resumes COUNTING_UP for a count-up sequence, not EXPIRED`,
and `a restored custom race sounds only the cues it has not passed`. **That is exactly why the hardware
run is not redundant.** Those tests inject a clock and hand `restore` a snapshot; they assume the four
keys survived the kill, that the relaunch read them back, and that a queued cue reaches a speaker. Every
one of those is a platform fact the JVM suite is structurally incapable of checking, because it
constructs the state it then verifies. The watch is the only instrument that can say the persistence,
the process lifecycle and the audio path did their part.

So **a green suite is not evidence for anything below.** What it buys is that a hardware failure here
means the platform rather than the arithmetic — which is worth a great deal when something does go
wrong.

## Preconditions

- The target watch reachable over adb — see [`watch-setup.md`](watch-setup.md) for pairing, and cairn's
  `wear-os-adb-pairing` note for the two ways `adb mdns services` lies about a watch that is in fact on.
- **Know which build is installed before you start.** `watch-setup.md` has the sha256 check. An install
  that predates the change under test reads exactly like a change that did not work.
- Either build type works, for different reasons:
  - The per-cue log line is gated on `Log.isLoggable(TAG, DEBUG)` at runtime rather than on
    `BuildConfig.DEBUG`, and `wear/proguard-rules.pro` keeps `com.racetimer.**` whole, so `setprop`
    turns it on in a release build too.
  - **`run-as` needs a debuggable build.** Reading the persisted keys directly is the one step below
    that is debug-only; on a release build, infer the same fact from the screen instead.
- Put the watch on the charger. Every scenario here is a coordinated kill-and-relaunch against a
  countdown that does not wait for you.

```sh
S=<transport>                                       # adb devices -l; SM-R925U is the target watch
adb -s $S shell getprop ro.product.model            # confirm the device, never the IP
adb -s $S shell setprop log.tag.TimerService DEBUG
adb -s $S logcat -c
adb -s $S logcat -s TimerService:D ToneManager:D    # leave running in its own shell
```

## Force-stop, not swipe-away

**Dismissing the app is not a process kill and will verify nothing.** A running race holds a foreground
service, so swiping the activity away leaves `TimerService`, the engine and the countdown alive; coming
back finds the race still running and never touches the restore path at all. Only
`ActivityManager.forceStopPackage` ends the process, and it has two spellings:

- **Settings → Apps → Race Timer → Force stop** on the watch. This is the route #9 wrote the procedure
  around, and it is worth doing once because it is what the sailor's own device does to a backgrounded
  app.
- `adb shell am force-stop io.github.sailordave17.racetimer`. **The same call**, but issued at an instant you choose
  and can write down — which is the only reason scenarios C and D are reachable at all.

Relaunch with
`adb shell am start -n io.github.sailordave17.racetimer/com.racetimer.wear.MainActivity`, or from the
watch's app list. **The class must be spelled out.** `am start -n` resolves a leading-dot shorthand
against the *package* half, which is the `applicationId`; the activity lives in the `namespace`
package, and the two are deliberately different here, so `/.MainActivity` would look for a class that
does not exist.

## Instruments, and the discipline that makes them evidence

Learned the expensive way on #122, and recorded in cairn's `install-then-hand-off-device-tests`:

- **Pin the pre-action state by instrument before every kill.** `adb -s $S exec-out screencap -p >
  before.png`. A remembered "it was about four minutes" cannot be reconciled against anything
  afterwards, and #122 spent a criterion's worth of confidence on exactly that gap.
- **Read the owner's taps out of logcat**, not out of their recollection. Which button was tapped, and
  when, decides how every later reading is interpreted.
- **A checkpoint answer is a claim, not a result.** "Yes, it resumed" is the thing under test; reconcile
  it against a screenshot or a log line before recording it as a pass.
- Read the persisted race directly where the build allows it:

  ```sh
  adb -s $S shell "run-as io.github.sailordave17.racetimer \
    cat /data/data/io.github.sailordave17.racetimer/shared_prefs/race_timer_state.xml"
  ```

  The four keys written and cleared as a set are `sequence_id`, `gun_elapsed_ms`, `gun_wall_clock_ms`
  and `captured_elapsed_ms`. `picked_sequence_id` is **not** one of them and must survive everything
  here — a run that loses it has found a different bug (#88).

  **Quote the whole remote command, or export `MSYS_NO_PATHCONV=1`.** Under Git Bash the `/data/...`
  argument is rewritten to a Windows path before adb ever sees it, and the error that comes back —
  `cat: C:/Program: No such file or directory` — names a path that appears nowhere in what you typed.
  Observed on this machine; see cairn's `windows-shell-hazards`.

**The two clocks are not the same clock, and the gap is bigger than what you are measuring.**
`logcat` timestamps and `gun_wall_clock_ms` are the *watch's* clock; anything your shell stamps with
`date` is the *PC's*. Measured on this pair: the watch ran **1.16 s behind**, stable to ±6 ms over
three samples. That is ten times the cue error being judged, and it silently turns a passing run into
a failing one or the reverse. So:

```sh
adb -s $S shell date +%s%3N     # against python -c 'import time;print(time.time())' either side
```

Take the offset before the run, and prefer comparisons that never cross it: a cue's logcat timestamp
against `gun_wall_clock_ms` is watch-against-watch and exact. A screen reading against a PC timestamp
crosses it *and* carries the `uiautomator dump` latency on top, so treat screenshots as corroboration
and never as the primary timing evidence.

One more way a timestamp lies, and it produced a five-second phantom discrepancy on the first run:
**`echo "$(ts) $(dump)"` stamps the time before the dump runs, not when the screen was sampled.** Bash
evaluates the substitutions left to right and `uiautomator dump` can take several seconds, so the label
can precede the reading it labels by longer than the thing being measured. Stamp *after*, or use
`screencap`, which is fast enough that the gap does not matter — a screenshot is also the only one of
the two that a later reader can re-examine.

The cue line is the instrument for anything about a cue firing:

```
cue offsetMs=5000 label=2 short — final five errorMs=12 sleptMs=0 wakeLock=true screenOn=true
```

`errorMs` is lateness against the boundary the *sequence* put the cue on, so it measures against the
race rather than against the dispatch — see [`timing-accuracy.md`](timing-accuracy.md), including the
trap that `ToneManager`'s similar-looking `cue lateMs=` cannot answer a timing question about the race.

## Scenario A — the happy path (#9's original procedure)

Start a sequence, kill it mid-countdown, relaunch, confirm the countdown comes back where it should.

1. Pick **US Sailing 5-4-1-Go**. Screenshot. Tap Start.
2. Let it run past 4:00. Screenshot the running countdown, and note the wall-clock time of the
   screenshot — the two together pin the gun instant.
3. `adb -s $S shell am force-stop io.github.sailordave17.racetimer` — note the wall-clock instant.
4. Relaunch. The pre-start screen must offer **Resume / Start over**, and the number beside Resume must
   be the *live* remaining time: it keeps counting down while you look at it, and it must agree with
   `(gun instant) − (now)` from step 2's arithmetic rather than with the sequence's full 5:00.
5. Tap Resume. Expect the transient **"Resumed race in progress"**.
6. Screenshot again half a minute later and check the countdown is still continuous with the original
   gun anchor across the whole dead window.

**Pass** when the resumed countdown is continuous with the pre-kill anchor and no re-sync prompt
appears.

**The discriminator that catches a false pass:** a text line right after Resume is expected on *both*
the exact and the degraded path, and they mean opposite things. "Resumed race in progress" is transient
and clears itself after a few seconds; **"Recovered — tap Sync to confirm" is sustained and clears only
on a Sync tap.** A same-boot restore must be the first. Judging by "a banner appeared" cannot tell them
apart, and that ambiguity cost real time on #122.

## Scenario B — a kill after the gun must not resume a stale race

Two different mechanisms protect this, they fire in different windows, and only one of them is
interesting. Know which one you tested.

The gun teardown clears the four keys `lastCueDurationMs + GUN_LINGER_MS` after the gun — read both out
of `TimerService` rather than trusting a number here; for the sequences carrying the sustained gun it
comes to a few seconds. So:

- **B1 — kill inside that window**, the interesting one. The snapshot survives, spent. On relaunch the
  pre-start screen must show a **plain Start with no Resume offer**, because `resumeOfferRemainingMs`
  withholds a countdown whose gun has passed. Then tap Start: a plain Start is not a `freshStart`, so
  the service still enters the restore path, `TimerEngine.restore` returns `EXPIRED`, and the screen
  must show the transient **"Old race ended — starting fresh"** while a fresh race runs from the top.
  It must never come back on the old clock. **#122 recorded this EXPIRED banner as never having been
  exercised on device** — this is the run that closes that gap.
- **B2 — kill after the teardown has already run.** Nothing is persisted, so there is nothing to resume
  and the screen opens clean on the remembered sequence. A real observation, but it proves the teardown,
  not the spent-snapshot guard. Do not record B2 and call it B.

Confirm which one you got by reading `race_timer_state.xml` immediately after the kill: four keys
present is B1, absent is B2.

**The deliberate exception.** For a race-manager sequence (`us_sailing_race_manager`,
`scholastic_race_manager` — anything with `countUpAfterFinish`) a kill after the gun **must** come back
counting up on the same gun anchor. That is not a stale race: a committee's race is still running
whether or not the watch was. Do not file it as a bug.

## Scenario C — killed close to the gun, back before it

The cue queue is rebuilt on restore from whatever is still ahead —
`queueCues(seq) { it.offsetMs <= remaining }`. This is the run that proves the rebuild reaches a
speaker.

1. Run a sequence into the final minute. Screenshot; note the wall-clock instant and the remaining time,
   which together pin the gun.
2. Force-stop at a known remaining time, ideally 20–30 s. Note the instant.
3. Relaunch and tap **Resume** with time still on the clock.
4. Listen, and read the log.

**How close to the gun you can kill depends entirely on who is driving.** Measured on this watch, an
adb-driven force-stop → `am start` → tap-Resume cycle takes **about 6 s**, most of it the cold launch's
first paint. So a kill at 8–9 s to the gun leaves a couple of seconds of countdown to come back to, and
that is the sharpest version of this test — the run log below has one. By hand from the watch's app
list the cycle is closer to ten seconds, and the gun will usually pass while you are still finding the
icon, which turns the run into Scenario D without your deciding to. Kill at 20–30 s when a human is
driving, and say which you did.

Do not tap Resume by dead reckoning without checking where it landed: the pre-start screen only offers
Resume when there is something to resume, so if the gun passed during the relaunch the same coordinates
are a plain **Start** and the tap silently runs a fresh race instead. Read the screenshot afterwards.

**Pass** on all three:

- Every cue *still ahead* at the moment of resume fires — the single ticks, then the doubled final five,
  then the gun.
- No cue that had **already passed** before the kill fires again.
- Each surviving cue's `errorMs` is small, and the gun lands at the wall-clock instant step 1 pinned. A
  cue that fires in the right *order* but late has still failed this.

## Scenario D — the gun passes while the process is dead

The literal reading of #125's third criterion, and the honest answer to it: **a gun that fires while the
process is dead cannot sound.** Nothing in the app can un-miss it. What is under test is that the app
says so rather than pretending otherwise.

1. Force-stop with under 10 s to the gun.
2. Wait past the gun instant before relaunching.
3. Relaunch.

**Pass** when the pre-start screen offers no Resume, the countdown is not on the old clock, and a tap on
Start gives a fresh race with **"Old race ended — starting fresh"**. **Fail** on any of: a late gun
blast, a Resume offer, or a resumed clock reading a negative or wrapped time.

C and D together are the whole of "restore must not miss the gun cue". C is the case where cues are
still owed and every one must arrive; D is the case where none are, and the app must say so.

## Recording the result

An observation held only in a session transcript is not an artefact. Post what was seen to the issue the
run was for — wall-clock instants, the remaining time at each kill, which scenario branch was hit, and
the times of the screenshots. Both #122 and this file exist because criteria were once ticked with
nothing behind them.

Then clear the test state so it cannot offer itself on a real launch:

```sh
adb -s $S shell pm clear io.github.sailordave17.racetimer
```

`pm clear` also takes `picked_sequence_id` and the saved custom duration with it. That is the point, but
it means the next launch is a first-ever launch and will look like one.

## What this procedure does not cover

- **Abrupt power loss.** Every kill here is graceful. Since #151 `persistSnapshot` uses `commit()`, so
  the write no longer outlives the call that made it — but that was measured against a **process
  kill**, where data already handed to the kernel survives. A battery dying is a different question
  about a different layer, and it is unexercised here and on #122.
- **The reboot path** — #122, and the wall-clock reconstruction a reboot forces.
- **Audibility.** That a cue fired is what the log line settles. Whether it was loud enough to hear on
  the water is a different question with its own issues (#95, #96) and its own instrument — cairn's
  `android-vibration-usage-and-dnd` note, for whether a haptic reached the wrist at all.

## Runs

Results go here, newest first, one section per run: build under test, who drove what, and the four
scenarios with their instants. Follow the shape of `timing-accuracy.md`'s Measurements section — an
entry that records only "passed" is the thing this file was written to stop.

### 2026-08-16 — the snapshot write window, measured and closed (#151)

SM-R925U, Wear OS on Android 16, on the wireless charger at 100%, screen on throughout
(`screen_off_timeout` raised to 600000 ms for the run and returned to 15000 ms after).
`log.tag.TimerService DEBUG`. adb drove every tap, kill and read; no owner action.

**This run answers #9's `apply()` vs `commit()` question, which had gone unanswered since #9 closed.**

**The instrument, which needs no kill at all.** `TimerEngine.snapshot()` stamps `captured_elapsed_ms`
at the instant `persistSnapshot()` runs, and `gun_wall_clock_ms` / `gun_elapsed_ms` are the same
instant expressed in the two clock domains. So the write window is computable from the persisted file
plus its mtime, entirely on the **watch's** clock — the 1.16 s host/device offset above never enters:

```
T_persist(wall) = gun_wall_clock_ms - (gun_elapsed_ms - captured_elapsed_ms)
window          = mtime(race_timer_state.xml) - T_persist(wall)
```

Read it with `run-as <pkg> stat -c '%y' <prefs>`; resolution is about ±3 ms, which is the floor every
"0 ms" below should be read against.

**The window, on the shipping build** (debug APK `faa9576…c5e9b3`, hash-verified as the installed
artefact):

| Arm | n | Window |
|---|---|---|
| Warm process | 6 | 20.6 – 52.6 ms (median 46.6) |
| Cold process | 5 | 68.6 – **204.6** ms (median 80.6) |
| Cold, first launch after `pm clear` | 1 | 182.6 ms |

The cold process is materially worse, which is what made it worth measuring: the write queues behind
the rest of a cold launch on `QueuedWork`'s single background thread.

**No kill ever reached it, and that is a fact about the harness.** Kills via
`run-as <pkg> kill -9 <pid>` — `am force-stop` is slower, and plain `kill` from the shell uid is
refused outright with `Operation not permitted` — landed 333–953 ms after the anchor across six
trials at nominal delays of 0 to 0.4 s, all **SURVIVED**. The floor is set by `input tap` and
`run-as` spawn latency (~238 ms for `run-as` alone), not by the delay asked for, so a nominal delay
is not a controlled one on this device.

**Positive control, run because a clean result would otherwise prove nothing** (cairn's
`prove-an-instrument-could-have-shown-the-opposite`). With the write deferred 1500 ms in a locally
built harness APK, the same kills went **3/3 LOST** against **6/6 SURVIVED** unmutated. So the
harness can lose the snapshot and can tell that it did; what it cannot do is land inside 205 ms.

**The classification trap, which cost one trial.** A missed tap and a lost snapshot both produce "no
keys on disk". They are told apart by the app's own first-cue line, which `engine.tick()` emits
*before* `persistSnapshot()` runs:

| first cue logged | four keys present | reading |
|---|---|---|
| yes | yes | survived |
| yes | no | **snapshot lost in the window** |
| no | — | invalid trial — the race never started |

One cold trial at a 7 s launch wait came back with no keys and was **not** a loss: the screenshot
showed the pre-start screen and the tap had missed. The launch wait is 9 s in this procedure for that
reason.

**The fix, and its cost.** Both measured on one instrumented APK selecting behaviour by log tag:

| | `apply()` | `commit()` |
|---|---|---|
| Window | 52 – 94 ms | **−1.9 – +1.1 ms** |
| Main-thread cost of the call | 0.40 – 0.99 ms | **6.8 – 9.1 ms** |

The write itself takes ~8 ms, so the tens-to-hundreds of milliseconds `apply()` left exposed were
**queue latency, not I/O** — which is what makes `commit()` close to free rather than a real trade.

**After the change** (`8ad9ea5…1bc02e9`): window **−2.9 to +2.1 ms** across 9 trials (5 cold,
4 warm) — zero at this instrument's resolution, from 68.6–204.6 ms cold.

**Cue timing, verified end to end rather than argued.** One full US Sailing 5-4-1-Go race on the
changed build: **30 of 30 cues delivered, `errorMs` 0–26, `sleptMs=0` throughout**. The 2026-08-10 run
above is the baseline, and its worst cues were 102 ms and 88 ms — so cue dispatch is no worse, and on
this run better. The first cue is dispatched by `engine.tick()` *before* the persist (#62), so it
cannot be affected by construction, and it read `errorMs=2`.

**Why no unit test came with the change, measured rather than asserted.** A probe was written that
reads `race_timer_state.xml` **off disk** — not through `SharedPreferences`, whose in-memory map is
identical either way — and asserts the four keys are there once `onStartCommand` returns. It passes
under `commit()`. Mutating the shipped line back to `apply()` reddened **0**, against a prediction of
0 written first: Robolectric drains the background write inside the test's own looper, so the two are
indistinguishable to it. The probe was discarded rather than committed. **This is a property of the
harness, not of the code** — a test asserting durability here would pass whichever call was in the
file, which is the shape cairn's `a-stubbed-default-cannot-report-the-platform-moved` records. The
window is reachable only by the on-device instrument above.

**Not covered by this run:** abrupt power loss (a different layer, see above), the reboot path
(#122), and the phone module — `PhoneRacePersistence` still uses `apply()` on a rationale this run
falsified, tracked as
[#256](https://github.com/SailorDave17/race-timer/issues/256) rather than changed on the watch's
numbers.

### 2026-08-10 — first run: A, B1, C, D, and C again at a tighter kill (#125)

SM-R925U, Wear OS on Android 16, on charger, screen on throughout. Build under test: the debug APK
built from `develop` @ `64cb87e`, **sha256-identical to the one already installed** —
`fa27e027…09e372` on both sides, so nothing was reinstalled and the artefact under test is the
artefact that was there. `log.tag.TimerService DEBUG`, app state cleared to a first-ever launch before
the first scenario. adb drove every force-stop, relaunch and tap; the owner watched the watch.

Watch clock measured **1.16 s behind** the PC (±6 ms over three samples). Every instant below is the
**watch's** clock, and every cue-versus-anchor comparison is watch-against-watch.

**A — happy path. Pass.** US Sailing 5-4-1-Go, gun anchored at `gun_wall_clock_ms` = 19:43:17.735.
Force-stopped at 19:38:58.4 with the race mid-countdown, dead for 56 s, relaunched at 19:39:54.9.

- The pre-start screen offered **Resume / Start over** reading **3:46** by screenshot at 19:39:32.5,
  against 3:45.2 of arithmetic from the pre-kill anchor — the display rounds up to the whole second,
  so that is exact. The live remaining, not the sequence's 5:00.
- The restored process (pid 19251, against 19052 before the kill) carried the race all the way down
  and **fired the gun at 19:43:17.794 — 59 ms off the anchor set before the kill**, with every cue in
  between on its own boundary (3:00 `errorMs=3`, 2:00 `errorMs=11`, the whole final minute in single
  digits).
- The 4:00 prep cue and its sync run-in were due *inside* the dead window and are absent from the log.
  Correct: the process was not running. Nothing replayed them on restore.
- No sustained re-sync prompt at any point, which is what distinguishes this from the `DEGRADED` path.

**B1 — kill after the gun, inside the teardown window. Pass.** Force-stopped at 19:43:19.4, **1.6 s
after the gun** and comfortably inside the ~6 s teardown (3 s sustained gun cue + 3 s linger). All four
snapshot keys were still present while dead — confirming this was B1 and not B2.

- Relaunch offered a **plain Start with no Resume**: the spent countdown was withheld.
- Tapping Start gave **"Old race ended — starting fresh"** over a fresh 5:00. Screenshot
  `B1-banner1.png`. **This is the `EXPIRED` banner #122 recorded as never having been exercised on
  device.** It has been now.

**C — killed near the gun, back before it. Pass.** Same sequence, gun at 19:48:50.423. Force-stopped
at 19:48:24.5 (gun − 25.9 s), relaunched and Resume tapped at 19:48:32.3 (gun − 18.2 s).

- Every cue still ahead fired, from pid 19700: 0:10, 0:09, 0:08, 0:07, 0:06 single, then 0:05 to 0:01
  doubled, then the **gun at 19:48:50.445 — 22 ms off the anchor**.
- **Nothing replayed.** The 1:00, 0:50, 0:40 and 0:30 cues had fired from pid 19484 before the kill and
  appear exactly once each.
- The 0:20 cue fell inside the dead window and correctly never fired — the queue was rebuilt from what
  was still ahead at the instant of resume, not from the top.
- Cue error was single- to double-digit ms except 0:05 at 102 ms and 0:04 at 88 ms, both with
  `sleptMs=0` — scheduling jitter with the screen on, not doze.

**D — the gun passes while the process is dead. Pass.** Custom 1:00, gun at 19:53:56.697.
The force-stop was issued at 19:53:47.7, **9.0 s before the gun**, and the call returned 0.9 s later,
so the process died somewhere in gun − 9.0 s to gun − 8.1 s. Either way, under ten. Relaunched at
19:54:02.1, 5.4 s after the gun.

- **No gun blast ever fired for that race** — the log jumps from the 0:09 cue straight to the 1:00 cue
  of the next race, with nothing at `offsetMs=0` and none of 0:08 to 0:01 arriving late.
- Relaunch offered no Resume and showed a clean **1:00**, not a stale or negative clock, with the
  `custom_1m` *pick* correctly remembered (#88).
- Tapping Start gave **"Old race ended — starting fresh"**. Screenshot `D2-banner.png`.

**E — Scenario C again, but killed under ten seconds out. Pass.** C above was killed at 25.9 s, which
is the honest limit of a hand-driven relaunch and leaves the criterion's own "under 10 s" wording
untested. Repeated on a Custom 1:00 with the timing corrected for the 1.16 s clock offset, gun at
20:13:46.126.

- Force-stopped at 20:13:37.48 — **gun − 8.56 s**, genuinely inside ten.
- Relaunched and Resume tapped at 20:13:43.51, **gun − 2.53 s**: a dead window of 6.0 s with the race
  still live on the other side of it.
- The restored process (pid 20854) fired 0:02 at `errorMs=49`, 0:01 at `errorMs=1`, and **the gun at
  20:13:46.177 — 51 ms off the anchor set before the kill**.
- The 0:09 through 0:03 cues were due inside the dead window and none of them fired, late or otherwise.
  Nothing from before the kill replayed.

So the sub-ten-second kill does not miss the gun cue when the app is back before the gun, and does not
sound a late one when it is not (D). That is both halves of the criterion, measured rather than
interpreted.

**Not covered by this run:** abrupt power loss, the reboot path (#122), audibility as opposed to
dispatch, and a post-gun kill on a `countUpAfterFinish` sequence — the deliberate exception in
Scenario B, which is JVM-covered and was not exercised on device.

Afterwards the app's state was cleared with `pm clear`, `log.tag.TimerService` was set back to `INFO`,
and `screen_off_timeout`, which had been raised to 600000 ms for the run, was set to **15000 ms** — the
Wear OS stock value, not necessarily what it was before.
