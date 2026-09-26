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

That was confirmed by the source, and it is load-bearing for how the runs below should be read.
**It is no longer how the app behaves.** On 2026-09-25 the owner decided that the pre-start screen
should stay awake until Start is pressed, with no timeout
([#300](https://github.com/SailorDave17/race-timer/issues/300)). Pressing Start on the signal had
meant waking the watch first. The quote is kept because it describes the build both runs were taken on.

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
is close to free.** The display is the dominant load, and in both runs it was lit only for the
countdown itself — which is the outcome the display policy was designed for, now with a number
against it. Since #300 the display is also held on the pre-start screen for as long as the sailor waits
there, and neither run contains that load (see the next section).

## The profile matrix #16 asked for, and what the watch actually permits

#16 defined four profiles on 2026-07-25. The two runs above were taken before #300 changed what
waiting in the app costs, which is why they do not map onto the profiles one-for-one.

| #16's profile | Status |
|---|---|
| **Baseline** — battery % at start and end of session | Covered by both runs |
| **Session** — from 100%, one full sequence, screen on throughout, haptics enabled | Run 1 |
| **Extended** — 30 min idle-in-app, then a full sequence | **Not measured on the current build.** Since #300, idle-in-app on the pre-start screen is a lit panel, and neither run has one |
| **Screen-off** — the same with keep-screen-on disabled | Covered in substance by run 2, in the opposite order, on a build where every wait in the app slept. On the current build it is the same wait on the sequence picker |
| **Pre-start hold** *(added by #300, not one of #16's)* — an hour on the pre-start screen, untouched | **Not measured** |

**There is still no keep-screen-on setting on the watch, but since #300 there are two ways to wait in
the app.** `ScreenPolicy.keepsScreenOn(state, onTimerScreen)` decides for the wearer. It is true for
`RUNNING` and `RACE_ENDED`, and false for `PAUSED`, `FINISHED` and `COUNTING_UP`. For `IDLE` it
depends on the screen: true on the timer screen, which in `IDLE` is the pre-start screen, and false on
the sequence picker and the other screens stacked over it. Nothing in `:wear` overrides it. The toggle
still exists only in the phone module (`DisplayChoice`,
[#225](https://github.com/SailorDave17/race-timer/issues/225)), which is a different app on different
hardware.

**So a screen-on idle arm can now be produced, and it has its own control.** Until #300, sitting in the
app before the start was `IDLE` with the screen allowed to sleep, so an idle-in-app arm *was* the
screen-off arm and the two profiles collapsed into one. Now waiting on the pre-start screen holds the
panel on at the system's brightness. The brightness override stays off, because `forcesMaxBrightness`
is unchanged. And the app takes no wake lock of its own, because the service's `PARTIAL_WAKE_LOCK`
belongs to a race and nothing is being timed. Waiting on the sequence picker is the same wait with the
screen allowed to sleep, so that is the screen-off arm.

Run 2 puts its long stretch after the gun instead of before it. When it ran, that was the same test for
power, because both sides of the gun had the display asleep. **It is not the same test now.** A
stretch waited out on the pre-start screen keeps a lit panel that run 2's count-up hour never had.

**The cost of an hour held on the pre-start screen is unmeasured.** Neither run contains a lit idle
stretch, so nothing in this file bounds it. Extrapolating from run 1's five lit minutes would multiply
a number this file already calls overstated, and run 1 was lit at full brightness where the hold is
not. Measuring it takes an hour off the charger with no adb attached. *How to re-run this* below
covers it as step 5.

## Recommendation

**Is racing with less than X% advisable?**

- **One sequence on a nearly flat watch is fine.** 5 points is the worst observed and is an
  overstatement for the reason above.
- **A full committee day should start above roughly 50%** — several sequences plus hours of count-up
  and idle. That figure is a projection from two runs, not a measurement, and is offered as a rigging
  guideline rather than a result. It predates #300: its idle hours were a sleeping screen, and time
  now waited on the pre-start screen is lit and unmeasured. A postponement waited out there is the
  case to measure before leaning on the 50%.

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
5. For a pre-start arm (#300), open the app on the pre-start screen, put the watch down untouched for
   a timed hour, and read the percentage when the hour is up. The screen should still be on at the
   end, and if it is not, the run did not measure the hold. For its control, do the same hour on the
   sequence picker, where the screen sleeps. Only the picker hour needs a tap to wake the watch
   before reading the percentage.
6. Add the row to the table above rather than replacing it. Two dated rows that disagree are more
   useful than one that has been overwritten.
