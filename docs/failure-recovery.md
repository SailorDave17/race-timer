# Failure Recovery — What the Sailor Does When a Gun Is Dropped

What happens when the app dies, a cue goes unfelt, or the sailor loses the thread of a sequence
already under way — what the app recovers on its own, what it asks the sailor to do, and what it
cannot do at all.

Answers [#120](https://github.com/SailorDave17/race-timer/issues/120), the residual of
[#24](https://github.com/SailorDave17/race-timer/issues/24) — the spike that decided this and whose
fourth criterion, *"result captured in `docs/failure-recovery.md`"*, was the one thing it never did.

**Status:** the behaviour sections are read off `develop` and are current as of this file's commit.
The disposition table near the end is **proposed, not decided** — #24's per-mode rationale was never
recorded anywhere, so it is reconstructed here for the owner to accept or reject rather than
presented as history.

## The verdict, and what it rests on

> **no backup timer needed**
>
> — owner, [#24](https://github.com/SailorDave17/race-timer/issues/24), 2026-07-30

**That stands.** It is not unconditional, and #24 never wrote down what it rests on, which is the gap
this section closes. The verdict holds while all four of these are true:

| Precondition | Why it matters | If it is false |
|---|---|---|
| **DND keeps permitting the feedback class** | Under DND the audio channel is silenced either way; the haptics — the gun included — arrive only because cues are declared `USAGE_TOUCH`, the one unprivileged usage total-silence DND lets through. Measured, not inferred ([#144](https://github.com/SailorDave17/race-timer/issues/144) → [#187](https://github.com/SailorDave17/race-timer/pull/187)) | **The verdict does not hold, and nothing would notice** — no test can see it. The re-check is [`dnd-haptics-recheck.md`](dnd-haptics-recheck.md) ([#186](https://github.com/SailorDave17/race-timer/issues/186)) |
| **The watch has a working vibrator** | `HapticManager.play` returns silently when it does not, and nothing on screen says so | Cues are audio-only; nothing warns |
| **The app is on screen, or was started with the screen on** | A backgrounded race relies on a timed wake lock; the countdown stays exact but cue *dispatch* can slip while the CPU is suspended | Cues fire late by roughly the sleep. See `docs/timing-accuracy.md` |
| **The sailor can read the screen** | A countdown nobody can read is a dropped gun with extra steps | [#139](https://github.com/SailorDave17/race-timer/issues/139) / [#147](https://github.com/SailorDave17/race-timer/issues/147), open |

The first read "Do Not Disturb is off" until #187: while #144 was open, a race under DND had **no gun
signal at all** — a hard failure the recovery paths below cannot reach, because they all assume the
sailor eventually notices something. #187 closed it by declaring `USAGE_TOUCH` on every cue, so a
race under DND is now haptic-only rather than silent. What survives is the dependency itself: the
delivery rests on this device's DND policy continuing to permit the feedback class, and if that ever
moves, the failure returns with no error and no failing test. That fragility is
[#186](https://github.com/SailorDave17/race-timer/issues/186), and
[`dnd-haptics-recheck.md`](dnd-haptics-recheck.md) is the check.

The last two are not in #24's enumeration. They were found afterwards, and they are why this list is
preconditions rather than a restatement of the three modes.

## The three failure modes #24 enumerated

1. **The app is killed and not restored in time** — the process dies mid-sequence.
2. **A haptic is missed** — the cue fires, the wrist never feels it.
3. **The sailor loses track after a missed cue** — the sequence is running and the sailor no longer
   trusts where they are in it.

Each gets a section below: what the app does on its own, what the sailor does, and what neither can
do.

---

## Mode 1 — the app is killed mid-sequence

**Recoverable, and shipped.** This is the mode with the most machinery behind it, and the only one
where the app fully recovers without the sailor doing anything.

### What is saved, and when

Four keys in `race_timer_state`, written as a set by `TimerService.persistSnapshot()`:

```
sequence_id, gun_elapsed_ms, gun_wall_clock_ms, captured_elapsed_ms
```

They carry the gun in **both** clock domains plus the monotonic reading at capture, which is what
makes the reboot case separable from the ordinary one.

| Written | Cleared |
|---|---|
| At the tail of `ACTION_START` | Stop |
| On every accepted Sync (`onSync`) | Start over (`freshStart`) |
| On a wall-clock step (`onClockAdjusted`) | An `EXPIRED` restore |
| | The post-gun teardown |

The sailor's *picked sequence* is separate state (`PREF_PICKED_SEQUENCE_ID`), written on every
selection and cleared by nothing — that is
[#88](https://github.com/SailorDave17/race-timer/issues/88)'s fix, and it is why `clearPersistedState()`
removes the four keys by name rather than calling `edit().clear()`.

### What comes back

`TimerEngine.restore` returns one of three outcomes. The distinction is made on the one signal a
wall-clock step cannot forge: `elapsedRealtime` increases within a boot and resets to zero across
one, so a current reading that has **not** gone backwards proves the monotonic anchor is still valid.

| Outcome | When | Gun time | What the sailor sees |
|---|---|---|---|
| **`EXACT`** | Same boot, monotonic anchor intact | Trusted verbatim — **zero drift**, immune to NTP steps | Tier 1 banner: *"Resumed race in progress"* |
| **`DEGRADED`** | Reboot or clock step detected | Reconstructed from wall clock, best effort | Tier 3 line: *"Recovered — tap Sync to confirm"*, persisting until Sync is tapped |
| **`EXPIRED`** | The gun already fired while the process was dead | — race discarded | Tier 1 banner: *"Old race ended — starting fresh"* |

Tiers are `docs/message-surface.md`'s. `DEGRADED` is deliberately Tier 3 rather than Tier 1: it is the
one restore outcome that asks for a *sustained action*, and a 3-second banner cannot carry an
instruction.

**A race-manager sequence does not expire.** For a `countUpAfterFinish` sequence — `US Sailing - Race
Manager`, `Scholastic - Race Manager` — a gun that passed while the process was dead is not `EXPIRED`:
the committee's race is still running whether or not the watch was, so restore resumes straight into
`COUNTING_UP` on the same anchor.

### What the sailor does

**Nothing, in the ordinary case.** Relaunch the app. The pre-start screen opens on the saved race
already showing its *live* remaining time, and offers **Resume** / **Start over** side by side.

Two things worth knowing because they are not obvious from the screen:

- **The number next to Resume is what Resume will give you**, not the sequence's full duration. It is
  computed by the same code the restore uses, so the offer and the tap cannot disagree.
- **After a reboot, tap Sync at the next committee signal.** A `DEGRADED` restore is a wall-clock
  reconstruction, and the persistent *"Recovered — tap Sync to confirm"* line stays on screen through
  the whole race until you do. It is asking for mode 3's remedy.

### What is not recovered

| Case | Behaviour | Designed or gap |
|---|---|---|
| Watch reboots mid-race | Race is **reconstructed**, flagged `DEGRADED`, re-sync prompted | **Designed** — and a reversal of what #9 expected; see the reconciliation below |
| A different sequence is selected and Start is tapped | The saved race is overwritten. A Tier 3 line — *"Start discards saved &lt;name&gt;"* — warns first | **Designed** ([#89](https://github.com/SailorDave17/race-timer/issues/89)) |
| The saved `sequence_id` no longer resolves | Race is gone. Tier 1: *"Saved race unreadable — starting fresh"* | **Designed** ([#102](https://github.com/SailorDave17/race-timer/issues/102)) — announced, never absorbed |
| Process killed within the snapshot's write window | Snapshot may be lost; the race comes back as if never started | **Gap, unmeasured.** `persistSnapshot()` uses `apply()`, not `commit()`. #9's notes asked for this to be verified and nothing records an answer — [#151](https://github.com/SailorDave17/race-timer/issues/151) |

---

## Mode 2 — the cue fires and the wrist never feels it

**Partially recoverable.** The open defect that voided the verdict — DND taking both channels — is
closed ([#144](https://github.com/SailorDave17/race-timer/issues/144) →
[#187](https://github.com/SailorDave17/race-timer/pull/187)), and the sailor is told when the audio
half is gone (#96, shipped). What remains is a measured dependency on DND policy (below).

### Two channels, and what silences each

| Channel | Path | Silenced by |
|---|---|---|
| Audio | `ToneManager`, `AudioTrack` on `USAGE_ALARM`; the cue stream is raised to an audible floor for the race and restored after (#95 / PR #132) | Do Not Disturb — `setStreamVolume` is refused **silently** and the app records it in `cueVolumeRefused` |
| Haptic | `HapticManager`, `VibrationEffect.createWaveform`, declared `USAGE_TOUCH` via `WearHapticUsagePolicy` | No vibrator on the device (silent return); screen-off truncation mid-waveform. DND took this channel too until #187 (below) |

### Do Not Disturb took both — #144, closed by #187

Until #187, `vibrator.vibrate(effect)` was called with **no `VibrationAttributes`**, so the platform
classified each effect for itself, by duration — the 120 ms sync ticks and 300 ms single pips landed
as `TOUCH` and were delivered under DND, while the 600–900 ms multi-pulse cues and the 3000 ms gun
landed as `UNKNOWN` and were dropped, `ignored_app_ops`, never started. The sailor felt the minute
pips and lost the prep signals, the final five seconds, and the gun — the app's *longest and most
important* cues, the opposite of any priority intended.

#187 closed it by declaring `USAGE_TOUCH` on every cue: **30 of 30 delivered at `zen_mode=2`, the
3000 ms gun included**. The honest `USAGE_ALARM` was measured first and is worse than declaring
nothing — 0 of 30, the alarm class being exactly what total-silence DND restricts. The full arm
table, the re-check triggers and the baseline live in
[`dnd-haptics-recheck.md`](dnd-haptics-recheck.md); the reasoning is in `WearHapticUsagePolicy`'s
KDoc; the original measurement and zero-point test are on
[#144](https://github.com/SailorDave17/race-timer/issues/144).

### What the sailor does

- **Before the race: come out of Do Not Disturb if you want the tones.** Under DND the race is
  haptic-only — every cue buzzes (#187), no cue sounds — and the watch now says so during the race:
  [#96](https://github.com/SailorDave17/race-timer/issues/96) shipped the Tier 3 line *"Do Not
  Disturb — cues silent, wrist still buzzing"*, built on the refusal the app already observes rather
  than a prediction of audibility.
- **During the race: fall back to the screen.** The countdown display cannot drift — it is recomputed
  from the monotonic anchor on every tick, never integrated — so a sailor who missed a signal has an
  exact number in front of them the moment they look. **This is the reason the verdict holds**: losing
  a cue costs you a signal, not the race clock.
- **If the sequence position itself is in doubt, that is mode 3.**

### What the app does not do

It does not detect an undelivered haptic, and it cannot with the API it uses — `vibrate()` returns
having *asked*, with no signal that anything played. `dumpsys vibrator_manager` is the only instrument
that answers it, and that is an adb-side read, not something the app can do to itself.

---

## Mode 3 — the sailor loses track after a missed cue

**Recoverable, and shipped as the Sync button.** This is #24's "quick re-anchor gesture", and it exists.

### What Sync does

Tap Sync on the committee's signal — a flag, a horn, a whistle. `TimerEngine.sync()` snaps the
remaining time to **a whole minute** and re-anchors the gun there, re-queuing only the cues that have
not yet fired.

The tap is read as coming from someone watching for that signal: on it, or a fraction late, but never
early. So an up-correction of **10 s or less** rounds up to the minute above, and anything else floors
to the minute below ([#150](https://github.com/SailorDave17/race-timer/issues/150)).

| Guard | Value | Why |
|---|---|---|
| Late-tap window | 10 s | How far a sync may move the countdown **up**. Beyond it the tap is read as a watch running behind rather than as a late tap, and the countdown floors instead. There is no separate correction ceiling: one existed under round-to-nearest to stop a floor deleting 3:55 to 3:00, and under this rule 3:55 rounds *up* ([#150](https://github.com/SailorDave17/race-timer/issues/150)) |
| Double-tap guard | 1 s | A second tap inside a second is ignored |
| Lead-in | Inert | A sync during the run-up has no signal to correct against, and snapping there would delete the lead ([#104](https://github.com/SailorDave17/race-timer/issues/104)) |
| Wake lock | Re-armed | The lock is sized from the countdown at the instant it is taken, and a sync moves the gun later ([#126](https://github.com/SailorDave17/race-timer/issues/126)) |

Sync confirms with its own light tick and beep, and persists the corrected snapshot immediately — so a
re-anchored race survives a kill at its corrected time, not its original one.

### What the sailor does

1. **Watch for the next committee signal** rather than trying to reconstruct the one that was missed.
2. **Tap Sync on it.** The clock snaps to a whole minute; the label flashes what it snapped to, so
   the correction is legible before it matters.
3. **After a `DEGRADED` restore, do this once regardless** — the persistent prompt is asking for
   exactly this.

### What Sync cannot do

- **It cannot rescue a watch that is more than 10 s *ahead* of the sequence.** Correcting upward is
  what the late-tap window bounds, and past it the same tap is read the other way — as a watch
  carrying time the sequence has spent — so the countdown is floored and the sailor loses a minute
  rather than gaining one. This is the designed limit of a one-tap correction, not a gap: nothing in
  the tap distinguishes "I am 20 s ahead" from "I am 40 s behind", and only one of those two happens
  in practice.
- **It cannot correct a watch more than 59 s behind.** Flooring reaches the minute below and no
  further, so a sailor a whole minute out of step is snapped to the wrong minute. Watch for the next
  signal and tap again — each tap corrects within its own minute.
- **It does nothing after the gun.** The Sync button is not on screen once the sequence is spent —
  there is no committee flag left to sync against.

---

## Recovery paths, at a glance

The question this table exists to answer: **is this a designed limitation or a gap?**

| Situation | Today | Which |
|---|---|---|
| Process killed mid-sequence, same boot | Exact restore, zero drift, Resume offered | **Supported** |
| Process killed, watch rebooted | Wall-clock reconstruction, re-sync prompted | **Supported, degraded** |
| Process killed after the gun (countdown) | Race discarded, sailor told | **Designed limitation** |
| Process killed after the gun (race-manager) | Count-up resumes on the same anchor | **Supported** |
| Saved race unreadable | Discarded, sailor told | **Designed limitation** |
| Start tapped on a different sequence | Saved race overwritten, warned first | **Designed limitation** ([#89](https://github.com/SailorDave17/race-timer/issues/89)) |
| Sailor loses sequence position | Sync re-anchors: up to 10 s of late-tap correction upward, otherwise floors to the minute below | **Supported** |
| Watch is up to 59 s **behind** the sequence | Floored to the minute below — corrected | **Supported** ([#150](https://github.com/SailorDave17/race-timer/issues/150)) |
| Watch is more than 10 s **ahead** of the sequence | Read as a late tap on the minute below, and floored | **Designed limitation** — the tap carries no way to tell the two cases apart, and only one of them happens ([#150](https://github.com/SailorDave17/race-timer/issues/150)) |
| Cue fires while the CPU is suspended | Fires **late**, not dropped — `tick()` drains every overdue cue in order | **Designed limitation** — `docs/timing-accuracy.md` |
| Haptics under Do Not Disturb | Every cue delivered as `USAGE_TOUCH` (#187) — resting on DND policy permitting the feedback class | **Supported, fragile** — re-checked per [`dnd-haptics-recheck.md`](dnd-haptics-recheck.md) ([#186](https://github.com/SailorDave17/race-timer/issues/186)) |
| Audio refused under Do Not Disturb | Tones stay silent; a Tier 3 line — *"Do Not Disturb — cues silent, wrist still buzzing"* — says so during the race (#96) | **Supported, degraded** — the wrist carries every cue |
| Foreground service blocked / permission denied | Unhandled — `handleStart` assumes it works | **Gap** — [#13](https://github.com/SailorDave17/race-timer/issues/13), Tier 2 unbuilt |
| Display renders upside down | Countdown unreadable | **Gap** — [#139](https://github.com/SailorDave17/race-timer/issues/139) / [#147](https://github.com/SailorDave17/race-timer/issues/147) |
| Snapshot lost to an `apply()` race | Unmeasured | **Gap, unquantified** — [#151](https://github.com/SailorDave17/race-timer/issues/151) |

One line is worth pulling out because it is counter-intuitive: **a cue deferred by doze is late, not
lost.** `tick()` drains every cue whose time has passed, in order, so a watch that sleeps through two
cues fires both the moment it wakes — back to back. That is a designed consequence of the queue, and
it is more confusing on the wrist than a single late cue.

---

## Proposed dispositions — #24's unmet second criterion

#24 asked for *"a decision recorded: recoverable in-MVP vs. explicitly out of scope with rationale"*
per mode. **No such record exists.** Its three ticked boxes were ticked in a three-second burst and
the issue was closed sixteen seconds later on a four-word comment, so what follows is reconstructed
from what shipped, not recovered from what was decided.

**Proposed, for the owner to accept or reject:**

| Mode | Proposed disposition | Rationale |
|---|---|---|
| App killed, same boot | **Recoverable in-MVP — done** | Shipped and exact. #57 / #64 / #87 / #88 / #89 all landed on this path |
| App killed, after reboot | **Recoverable in-MVP, degraded — done** | Wall-clock reconstruction plus a re-sync prompt beats discarding a live race |
| App killed after the gun | **Out of scope, by design** | Nothing left to run. Discarding it is correct, and the sailor is told |
| Haptic missed — ordinary | **Out of scope** | Undetectable from inside the app; the screen is the fallback and it cannot drift |
| Haptic missed — under DND | **In scope — done** | Different from the above: the app *caused* it by not declaring attributes. #187 declares `USAGE_TOUCH` and the wrist now carries every cue under DND; the residual dependency on DND policy is [#186](https://github.com/SailorDave17/race-timer/issues/186) |
| Cues silent — DND | **In scope — done** | The refusal was already observed; #96 shipped the warning built on it (*"Do Not Disturb — cues silent, wrist still buzzing"*, Tier 3, running only) |
| Lost sequence position | **Recoverable in-MVP — done** | Sync is the re-anchor gesture #24 hypothesised, and it shipped |
| Lost position by more than 30 s | **In scope — done, and decided the other way** | The proposal here was *out of scope: beyond half a minute the sailor should stop and restart against the committee*. [#150](https://github.com/SailorDave17/race-timer/issues/150) decided against it: a watch running behind is now corrected up to 59 s by flooring, because the tap itself is the evidence. What survives of the proposal is the **ahead** direction, where past 10 s there is nothing an unaided tap can do |
| Display unreadable | **In scope, not done** | Not enumerated by #24. Tracked as #139 / #147 |

Two of these had no issue behind them when this document was written. The owner accepted both as
in-scope on 2026-08-10 and they are now filed: the >30 s mis-anchor is
[#150](https://github.com/SailorDave17/race-timer/issues/150) — **since decided, and shipped as the
late-tap rule described above** — and the `apply()` write window is
[#151](https://github.com/SailorDave17/race-timer/issues/151). Neither prejudged the outcome —
#150's first criterion was a decision, since taken and rewritten as the rule it produced, and #151's
is still a measurement.

---

## Reconciling #24 with what shipped since

#120's third criterion. Four things have changed under the spike's conclusion.

**The message surface exists now (#22, #102).** #24 predates any defined way to *tell* the sailor
something went wrong. `docs/message-surface.md` now defines three tiers, and every recovery outcome
above has a tier assigned — which is the difference between a recovery path and a recovery path the
sailor knows happened. #102 is the reason to trust that they render at all: the two restore banners
fired from `onCreate` were, measurably, never seen, because a cold launch takes 4.4 s to first paint
and the 3-second dwell was counted from the post rather than from the composition.

**The restore path is substantially stronger (#87, #88, #89).** At the time of #24 the restore rules
lived in `wear/`, behind an Android `Context`, some of them written out twice and disagreeing. They
are now one copy each in `shared/RestorePlan.kt`, unit-testable, and the three defects that duplication
produced are fixed: the stale pre-start clock (#87), the picked sequence surviving a cold launch (#88),
and the silent destruction of a recoverable race (#89).

**#9's reboot expectation was reversed, and nothing recorded it.** #9's notes said:

> Monotonic clock resets across reboots. If the watch reboots mid-sequence, expected behavior =
> sequence discarded (document this).

**That is not what shipped.** A reboot is detected and the race is *reconstructed* from the wall-clock
anchor, flagged `DEGRADED`, with a persistent prompt to re-sync. The behaviour is better than the plan
and the plan was never updated — this document is the first place the change is written down.

**The verdict's own basis narrowed, then partially recovered.** "No backup timer needed" was
reasonable on 2026-07-30 given a countdown that cannot drift and a restore path that works. #144
introduced a case the spike did not consider: not a *missed* cue, but **no cue at all, on both
channels, for the gun itself**. #187 took the haptic half of that back — under DND every cue now
reaches the wrist — so the verdict rests on a narrower precondition than "DND off": that DND keeps
permitting the feedback class. That is written down as a precondition, measured, and re-checked per
[`dnd-haptics-recheck.md`](dnd-haptics-recheck.md).

### A note on this document's provenance

Worth stating plainly, since a later reader will otherwise take #24's three ticks at face value.
**There is no artefact behind them.** No enumeration, no per-mode rationale, and no follow-up stories
were ever filed against #24's third criterion. The record consists of the four words quoted at the top
of this file. Everything else here is read off the code, off measured runs recorded on #144 and #126,
and off the issues that shipped the behaviour — not recovered from the spike.

## Limits of this document

- **The behaviour sections are read off source, not measured.** Where a number is measured it says so
  and names the run. The restore outcomes in particular are exercised by the JVM suite
  (`RestorePlanTest`, `TimerEngineTest`) but the *sequence* of relaunch, banner and tap is a hardware
  path, and it is verified by the issues that shipped it rather than re-verified here.
- **The DND measurements are one device.** SM-R925U, Wear OS on Android 16 (API 36) — one race per
  arm, across the arms recorded in [`dnd-haptics-recheck.md`](dnd-haptics-recheck.md). The duration
  boundary that decided `TOUCH` vs `UNKNOWN` for an *undeclared* effect sits somewhere between 300 ms
  and 600 ms and was not measured precisely; it may differ elsewhere, and since #187 it no longer
  decides anything for this app's cues.
- **The disposition table is a proposal.** Until the owner accepts it, it records what a reader would
  reasonably conclude, not what was decided.
- **The `apply()` write window is unquantified.** It is listed as a gap on the strength of #9 having
  asked the question, not on the strength of anyone having answered it.
  [#151](https://github.com/SailorDave17/race-timer/issues/151) is where it gets measured.

---

Source: this repo's code as of the `develop` branch, plus issues #24, #9, #10, #22, #57, #87, #88,
#89, #96, #102, #126, #144, #186, PR #187, and `docs/message-surface.md` / `docs/timing-accuracy.md`
/ `docs/dnd-haptics-recheck.md`.
Owner: SailorDave17.
Last reviewed: 2026-08-16 (#150 — Sync's rounding rule changed from nearest-minute to the late-tap
rule, so the What Sync does / cannot do sections, the guard table and both disposition tables were
rewritten; the >30 s row was decided against its own proposal).
Previously: 2026-08-13 (#186 — the DND sections rewritten after #187 reversed the both-channels
state and #96 shipped its warning; the re-check procedure and baseline split out to
`docs/dnd-haptics-recheck.md`).
Previously: 2026-08-10 (#120 — first capture of the #24 result; #150 and #151 filed against the two
untracked gaps the same day).
