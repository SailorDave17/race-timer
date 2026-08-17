# Battery Baseline — What a Race Costs the Watch

How much charge a race sequence actually takes off the wrist, end to end: foreground service,
keep-screen-on, the max-brightness override, haptics and the ongoing notification together.

Answers [#16](https://github.com/SailorDave17/race-timer/issues/16), whose Definition of Done asked
for this file. The measurements are recorded here rather than left in the issue's comments because a
number that lives only in a comment thread is not a number anyone finds later.

**Status:** two runs on one device — Samsung Galaxy Watch 5 Pro (`SM-R925U`), Wear OS 6 on Android 16
(API 36) — observed on the wrist by the owner on **2026-08-14** and **2026-08-15**. The instrument is
the battery percentage on the watch face, which is coarse enough to matter; see *Limits of these
measurements* at the end, which says plainly what these numbers cannot support.

## The short answer

**A full 5-4-1-Go sequence costs at most 5 percentage points, and a sequence plus an hour of
race-manager count-up costs 3 points in total.**

Racing is not a battery problem on this watch, and nothing measured here clears the bar #16 set for a
follow-up story — *"if the drain is bad enough to be a trust issue"*. It is not.

## What was measured

| Date | Run | Start | End | Drain |
|---|---|---|---|---|
| 2026-08-14 | One full 5-4-1-Go sequence | 100% | 95% | **5 points** |
| 2026-08-15 | One full sequence, then ~1 hour of count-up on the US Sailing race-manager sequence | 94% | 91% | **3 points** |

Both off charger, which the drain itself establishes. Source: the owner's observations recorded on
[#16](https://github.com/SailorDave17/race-timer/issues/16) on those dates.

A third observation from the same session, which is a behavioural fact rather than a measurement:

> when on the pre start screen the app does not stay awake. This is how it is supposed to function.

That is confirmed by the source, and it is load-bearing for how the runs below should be read.

## The second run did more and cost less, and that is two separate facts

Run 2 contains everything run 1 contains and adds an hour, yet it drained 3 points against 5. Two
things contribute, and they are not equally well established:

**1. Surface charge — reasoned, not measured.** A lithium-ion gauge falls fastest in the first few
points after a full charge. Run 1 spends its entire length in exactly that region, so its 5 points
overstate what the sequence costs from anywhere else on the curve. This alone makes the two runs
non-comparable, and it is the first thing to fix in any re-run.

**2. Count-up is the cheapest state the app has — read off the source.** In `TimerState.COUNTING_UP`,
`shared/ScreenPolicy.kt` returns **false** from both `keepsScreenOn` and `forcesMaxBrightness`, in
each case deliberately: the count-up is unbounded, and `forcesMaxBrightness`'s own KDoc names this
battery cost as the reason it is excluded. The `PARTIAL_WAKE_LOCK` does not survive the gun either —
`TimerService.acquireWakeLock` sizes its timeout to `engine.remainingMs + WAKE_LOCK_MARGIN_MS`, which
is the countdown plus a margin, not the race. What is left running for that hour is the foreground
service, its ongoing notification and the tick loop, with the panel dark.

So the honest reading of the pair is: **the sequence costs at or below 5 points, and the count-up hour
is close to free.** The display is the dominant load, and it is only lit for the countdown itself —
which is the outcome the display policy was designed for, now with a number against it.

## The profile matrix #16 asked for, and what the watch actually permits

#16 defined four profiles on 2026-07-25. Two of them assume a control that does not exist on the
watch, which is why the runs above do not map onto them one-for-one.

| #16's profile | Status |
|---|---|
| **Baseline** — battery % at start and end of session | Covered by both runs |
| **Session** — from 100%, one full sequence, screen on throughout, haptics enabled | Run 1 |
| **Extended** — 30 min idle-in-app, then a full sequence | Covered in substance by run 2, in the opposite order |
| **Screen-off** — the same with keep-screen-on disabled | **Not selectable on this app** |

**There is no keep-screen-on setting on the watch.** `ScreenPolicy.keepsScreenOn(state)` is a pure
function of `TimerState` — true for `RUNNING` and `RACE_ENDED`, false for `IDLE`, `PAUSED`, `FINISHED`
and `COUNTING_UP` — and nothing in `:wear` overrides it. The toggle exists only in the phone module
(`DisplayChoice`, [#225](https://github.com/SailorDave17/race-timer/issues/225)), which is a different
app on different hardware.

**Which collapses the extended and screen-off profiles into one run here.** Sitting in the app before
the start is `IDLE`, and `IDLE` is already a screen-sleep, no-brightness-override state — so an
idle-in-app arm *is* the screen-off arm, and there is no way to produce a screen-on idle arm to
contrast it against. That is precisely what the owner's third observation records, and it is the
policy working rather than a fault.

Run 2 puts its long stretch after the gun instead of before it. For power that is the same test: on
both sides of the gun the display is asleep, the brightness override is off, and no wake lock is
held.

## Recommendation

**Is racing with less than X% advisable?**

- **One sequence on a nearly flat watch is fine.** 5 points is the worst observed and is an
  overstatement for the reason above.
- **A full committee day should start above roughly 50%** — several sequences plus hours of count-up
  and idle. That figure is a projection from two runs, not a measurement, and is offered as a rigging
  guideline rather than a result.

**Should the app warn on low battery at sequence start? No — not at these numbers.**

The failure this would guard against is a watch that has enough charge to *start* a sequence and not
enough to *finish* it. At 5 points for a five-minute sequence there is no plausible charge level where
that happens: a watch with enough battery to boot and run the app has an order of magnitude more than
one sequence needs. A warning at these numbers would fire only when the watch was about to die
anyway, which the system's own battery warning already covers.

If that ever stops being true, the surface already exists — the Tier 2 blocking notice shipped in
[#13](https://github.com/SailorDave17/race-timer/issues/13), documented in
[`message-surface.md`](message-surface.md), is where a pre-start battery warning would go.

**What would reverse this recommendation**, stated so it can be checked rather than re-argued: any run
showing a single sequence costing more than ~10 points, or an hour of count-up costing more than ~5.
Either would mean the panel, the wake lock or the tick loop is doing more than this file says, and the
question should be reopened as a story under epic
[#7](https://github.com/SailorDave17/race-timer/issues/7).

## Limits of these measurements

- **The instrument is the percentage on the watch face**, and it is coarse. A 5-point result read to
  1-point granularity carries a ±1 band — a 40% uncertainty on the headline number. Every figure here
  is an order of magnitude, not a measurement to two significant figures.
- **Two runs, one device, no repeats.** `SM-R925U` on Wear OS 6 (SDK 36). Nothing here is an average,
  and vendor power tuning differs between watches.
- **The two runs start from different charge levels** (100% and 94%) and are not directly comparable.
  The comparison that would actually settle the count-up cost — two runs from the same starting point,
  one with the count-up hour and one without — has not been run.
- **Do not read the battery level over adb.** An attached adb-over-Wi-Fi session can itself suppress
  deep sleep, and sleep is most of what is being measured here — so the instrument would remove the
  effect and fail in the reassuring direction. This is the same limitation
  [`timing-accuracy.md`](timing-accuracy.md) records for its doze measurements. Read the percentage
  off the wrist.
- **The build under test was not recorded.** The runs bracket `develop` at `35728da`, but what was
  installed on the watch at the time is unknown.
- **Ambient conditions were not recorded.** Temperature and sunlight both matter to an OLED, and the
  max-brightness override ([#65](https://github.com/SailorDave17/race-timer/issues/65)) means the
  countdown drives the panel hard on purpose. A cold, bright day on the water is the harsher case and
  is not covered here.
- **No haptics-off arm.** Run 1 had haptics enabled per #16's profile; nothing here isolates what they
  cost.

## How to re-run this

1. Charge to 100%, unplug, and **wait ten minutes before starting** so the gauge leaves the
   surface-charge region. This is the single change that would make a re-run comparable to itself.
2. Note the percentage off the watch face, run the profile, note it again. Do not attach adb.
3. Record the installed build — Settings → Apps → Race Timer — alongside the numbers, since this run
   could not.
4. For a count-up arm, use a race-manager sequence and End Race at a recorded elapsed time, so the
   hour is a known quantity rather than an estimate.
5. Add the row to the table above rather than replacing it. Two dated rows that disagree are more
   useful than one that has been overwritten.
