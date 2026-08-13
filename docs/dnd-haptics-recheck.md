# DND Haptics Re-check — Proving Cues Still Reach the Wrist Under Do Not Disturb

Race cues are declared `VibrationAttributes.USAGE_TOUCH`, and that is a **deliberate mislabel**
([#144](https://github.com/SailorDave17/race-timer/issues/144) /
[#187](https://github.com/SailorDave17/race-timer/issues/187)): total-silence Do Not Disturb on the
test watch restricts the honest alarm class outright and permits the feedback class, so the accurate
declaration is the one that silences the gun. The reasoning lives in `WearHapticUsagePolicy`'s KDoc;
this file is the operational half that
[#186](https://github.com/SailorDave17/race-timer/issues/186) asked for — when to re-check that the
lie still buys delivery, how to run the check, and the baseline to compare against.

**Why a procedure and not a test**: if DND policy ever restricts the feedback class, every cue goes
silent under DND with no error, no crash, and no failing build — nothing CI runs can reach a
`vibrate` call, and nothing anywhere can execute *this watch's* zen policy (see
[Why this is manual](#why-this-is-manual-and-what-could-be-automated)). The only instrument that has
ever detected this class of failure is a race run on the wrist read back through
`dumpsys vibrator_manager`.

## When to run it

Any one of these obligates a re-run of the two arms below:

- **A `targetSdk` bump** in anything that ships to the watch.
- **A Wear OS platform upgrade on the test watch.** The policy being measured is the *device's*, so
  an OS update moves it while the app is untouched.
- **Any change to the attributes the haptic path declares** — `HapticManager.emit`, the
  `HapticUsage` / `HapticUsagePolicy` plumbing, or `WearHapticUsagePolicy`'s answer. (#186 named
  this trigger as "`HapticManager`'s attributes"; #200 moved the answer into
  `WearHapticUsagePolicy`, so the trigger follows the declaration, not the file.)

The first two re-test what the platform *does with* the declaration; the third re-tests the
declaration itself. Run arm 3 as well whenever a platform trigger fired — a policy loose enough to
honour the honest class is the outcome that retires the lie.

## The procedure

Prerequisites:

- Watch reachable over adb, and the build under test confirmed as the one installed — both are
  [`docs/watch-setup.md`](watch-setup.md).
- Record the pre-test state before touching anything, and restore it after:
  `adb shell settings get global zen_mode`.
- **Keep the screen awake for the whole race.** Screen-off truncates a waveform mid-play
  (`cancelled_by_screen_off` in the dump) — an artefact of the test condition, not of DND, and it
  reads as a failure if you let it happen.

Each arm is one full `US Sailing 5-4-1-Go` race — the sequence exercising every effect shape:
single, double and triple blasts, the light sync ticks, and the 3000 ms sustained gun.

### Arm 1 — DND on

```sh
adb shell cmd notification set_dnd on
adb shell settings get global zen_mode    # must print 2 — read the state back, don't trust the set
```

Run the race, then read the record:

```sh
adb shell dumpsys vibrator_manager
```

The **Recent vibrations** section carries one line per vibration — requesting package, status,
declared usage, and the `played:` step list with per-step duration and normalised amplitude:

```
18:56:29.691 | effect | finished | duration: 166ms | start: 18:56:29.753 | ... | usage: TOUCH
  | com.racetimer.wear (uid=10188) | played: [Step=0ms(amplitude=-1.00),Step=60ms(amplitude=0.43),Step=60ms(amplitude=0.00)]
```

Statuses that matter: **`finished`** — it played; **`ignored_app_ops`** with an empty `start:` —
refused before it began, which is what a DND drop looks like; **`cancelled_by_screen_off`** — the
test condition slipped, re-run the arm.

### Arm 2 — control, one variable changed

```sh
adb shell cmd notification set_dnd off
adb shell settings get global zen_mode    # must print 0
```

Same race, same read. This arm is what makes arm 1 evidence: a cue dropped in both arms is a
different defect entirely, and a clean arm 2 pins any arm-1 drop on DND alone.

### What to compare

From each dump, count this package's cue vibrations **issued** (every line naming it) and
**delivered** (`finished`). The pass condition is *every issued cue delivered in both arms* —
compare the delivered-to-issued ratio, not the absolute count, because the count belongs to the
sequence and sequences change (#117 already changed this one). At baseline the sequence produced
30 cue vibrations.

Alongside the counts, spot-check the shapes against the baseline below — a policy can also *scale*
rather than drop (the `scale:` field on each line), and the sync-tick/blast gap is a verified feel
criterion (#137).

**Fail condition**: any cue `ignored_app_ops` in arm 1 that `finished` in arm 2. That means DND
policy has moved against the feedback class — the fragility #186 predicted. The declaration decision
in `WearHapticUsagePolicy` must then be re-taken from a fresh arm table, and until it is, a race
under DND has lost its haptics again with nothing on the watch saying so.

### Arm 3 — has the honest declaration become viable?

`USAGE_ALARM` is the accurate class for a race gun, and at baseline it is the silent one — 0 of 30
under DND. Whenever a platform trigger forced arms 1–2, also check whether that is still true:

1. On a throwaway branch, point both returns in `WearHapticUsagePolicy.vibrationUsageFor` at
   `VibrationAttributes.USAGE_ALARM`.
2. Build, install, run arm 1 only.
3. Every cue `finished` → the platform has stopped restricting the alarm class: file a story to
   switch to the honest declaration, citing the run. Anything dropped → the lie is still
   load-bearing: discard the branch and note the date against the baseline.

`FLAG_BYPASS_INTERRUPTION_POLICY` is not a fourth arm worth running: it is stripped in transit for
unprivileged apps — the record reads `flags: 0` and nothing is raised (measured; the only vibrations
on this watch carrying `flags: 1` belong to a platform app).

## Baseline — SM-R925U, Wear OS on Android 16 (API 36)

Established across #144's investigation (unattributed arm 2026-08-10, `develop @ 64cb87e`) and
#187's fix (remaining arms 2026-08-12, merged as `4499ff2`). One full race per arm, one variable
between arms, screen awake throughout:

| Declared usage | `zen_mode=2` (DND) | `zen_mode=0` |
|---|---|---|
| none — duration-inferred | 20 of 30 | 30 of 30 |
| `USAGE_ALARM` | **0 of 30** — every one `ignored_app_ops`, empty `start:` | 29 of 29 |
| `USAGE_ALARM` + `FLAG_BYPASS_INTERRUPTION_POLICY` | **0 of 30** — flag stripped, `flags: 0` | — |
| **`USAGE_TOUCH`** — what ships | **30 of 30**, gun at 3032 ms | 30 of 30 |

Shape reference from the same runs (#137's feel criterion): sync ticks `60 ms @ 0.43`, short blasts
`150 ms @ 0.78`, gun `3000 ms @ 1.00` — amplitudes normalised against 255.

A re-run that changes the picture **appends a new dated baseline section here** rather than editing
this one — the next run compares against the latest, and the history is what would show a policy
tightening slowly instead of all at once.

## Why this is manual, and what could be automated

Assessed for #186's automation criterion, against the module layout as of #200:

- The subject of the check is the **watch's zen policy**, not the app's code. No harness in this
  repo executes that policy: `shared/` cannot reference `android.os`, and a Robolectric shadow would
  assert *Robolectric's* model of AppOps and zen mode when the question is precisely whether *this
  device's* has moved. A harness that fakes the platform cannot report that the platform changed —
  it keeps certifying the world it was written against.
- `:shared-android` has **no test source set by decision** — its build file records why, and the
  audio/haptic path is exactly the scope the testing strategy rules out for Robolectric.
- `wear/` has no test source set yet ([#160](https://github.com/SailorDave17/race-timer/issues/160),
  open). Once it lands, the one automatable fragment is a **change-detector on the declaration**: a
  test pinning `WearHapticUsagePolicy.vibrationUsageFor` to `USAGE_TOUCH` for both usages would turn
  an accidental edit into a red build — trigger 3 above, automated. It asserts what is declared and
  says nothing about what the platform does with it, so it replaces none of the arms.

So the delivery half of this check is **permanently manual** — that is a property of what is being
measured, not a gap a future harness closes — and the declaration half is a one-line test worth
adding when #160 gives it a home.
