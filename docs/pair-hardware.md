# Pair on Hardware — the Gun, the Controls and a Dropped Link, on Release Builds

What the phone and the watch did when a race was run across the pair on the owner's devices, on
release-signed builds, with one microphone timing the two guns. Answers
[#223](https://github.com/SailorDave17/race-timer/issues/223), the pair phase's hardware story in epic
[#196](https://github.com/SailorDave17/race-timer/issues/196), and the hardware criterion
[#219](https://github.com/SailorDave17/race-timer/issues/219) deferred to it.

The budget is **D2, 100 ms**, from [`pair-skew.md`](pair-skew.md). The protocol under test is
[`pair-protocol.md`](pair-protocol.md). The instrument is [`harness/pair-gun/`](../harness/pair-gun/).

**Status:** one pair, one session on 2026-09-29, 10:17–11:33 EDT. Four clean pair guns. The numbers
are a sample, not a guarantee; *Limits* says what this run did not cover. The DND and doze states
(#223's fourth criterion) were not run; they are carried by
[#335](https://github.com/SailorDave17/race-timer/issues/335).

## The short answer

**Every clean gun sounded within 100 ms on the two devices, the worst at 94 ms, with the link down
through the gun.**

| Race | Started on | Sequence | What else happened | Skew at the gun | First to sound |
|---|---|---|---|---|---|
| A1 | phone | Custom 1:00 | nothing | **55 ms** | phone |
| A2 | watch | Custom 1:00 | nothing | **34 ms** | phone |
| B1 | phone | Scholastic - Race Manager | another app's alarm rang across the gun | *void, not clean* | — |
| B2 | phone | Scholastic - Race Manager | a Sync taken on the watch at 2:14, snapping both to 2:00 | **55 ms** | phone |
| C1 | phone | Scholastic - Race Manager | the phone's Bluetooth off from 2:20 left until after the gun | **94 ms** | phone |

- **This is what an ear hears, not only what the clocks agree on.** One microphone heard both guns, so
  each reading is the clock skew at the gun **plus** the difference between the two speakers' output
  latencies. D2 budgets the clock half ([`pair-protocol.md`](pair-protocol.md)); the sound is the
  stricter quantity, and it met the budget.
- **The phone sounded first in every race**, whichever device started and whichever joined. That is a
  steady output-latency difference inside every reading: the watch's speaker path is slower. One
  microphone cannot split it from the clock skew. The two are separate only with a clock instrument on
  each gun, which this run did not use (*Limits*).
- **The worst case is C1, 94 ms, 6 ms inside the budget.** C1 ran the same way as A1 (a phone start
  the watch joined), which read 55 ms. After the start, neither engine reads the link (#220, #222), so
  the drop cannot have moved either gun. The 39 ms between them is the join's placement, reported at
  ±15–27 ms on the pre-start rows, plus any variation in output latency. *Reasoned*: nothing measured
  here separates the two.

Each dispatch log agrees that the guns went out on time. Every gun above was dispatched 0–13 ms from
its schedule on its own device (`ToneManager: cue lateMs`) and delivered all 144,000 frames, 3000 ms.

## The builds

| | Phone | Watch |
|---|---|---|
| Source | `develop @ ca64ecb` (the merge of #334) | same |
| Artifact | `phone-release.apk`, versionCode 2, 1.1 | `wear-release.apk`, versionCode 3, 1.1 |
| sha256 | `5994049c149d391d711457b2fbd11026b1cb16d455216154a1280c8a02b7b0d7` | `4b1d5229c08a5cfb33993ae4a8874198d79db7a146cc61ea7e4625e8f4398845` |
| Signed | upload key, SHA-256 `918a8257…abfe2dc8f6` ([`release-signing.md`](release-signing.md)), by `apksigner` | same |
| Installed | sideloaded over an uninstalled debug build, confirmed by pulling the APK and hashing it; not debuggable; `signatures:[67b379bc]` | same |

Both builds carry one certificate, so the Data Layer pairs them. A Play-installed build on one device
and a sideloaded one on the other would not pair (the epic's mixed-provenance risk). Both apps started
from a clean install.

| | Phone | Watch |
|---|---|---|
| Model | Samsung SM-S918U, Android 16 (SDK 36) | Samsung SM-R925U, Wear OS (SDK 36) |
| State | on USB power, stay-awake, lying on the desk ~40 cm from the mic | on its charger (stay-awake), face up ~3 cm from the mic |
| Cue volume | alarm stream 8 of 15 (lowered from 15 for the placement below) | the app's own volume borrow, music stream 15 |

## How the gun was timed

Both devices sound the same gun: one 3000 ms tone (941 + 1633 Hz, 2 ms ramps, `CueWaveform`). One
microphone cannot tell two onsets of one waveform apart, but it hears the **first onset** and the
**last end** cleanly, because each borders silence. So

```
skew = (last end − first onset) − 3000 ms − the excess of the device that sounded last
```

A device's *excess* is what the acoustic path adds to its gun on its own: the band filter, the
speaker, and the room ringing on after a loud tone stops. Which device led is read from the level of
the stretch where only the first gun sounds, checked against the stretch where only the last one does.

**Calibration.** Two 1-minute races, each with the other app force-stopped so that only one device
sounded (neither app has a listener service, so a stopped app cannot be woken by its peer):

| | Level at the mic | Excess |
|---|---|---|
| Phone alone | −45.2 dBFS | 9 ms |
| Watch alone | −37.9 dBFS | 0 ms |

The edges are cut at one level for the whole session, 10 dB under the quieter device (−55.2 dBFS). The
two devices must sit 6–12 dB apart. Two identical tones partly cancel where they overlap (by up to
29 dB at equal levels), and at 6 dB apart or more the overlap never falls below the quieter device
alone, whatever the phase. The first placement had the two 1.7 dB apart; the phone's alarm stream came
down from 15 to 8 rather than moving anything.

**What the instrument got wrong first, so the next run does not:**

1. **A cut 20 dB down landed in the room's ring.** The phone 40 cm away ends its gun with a 6–8 dB
   step and then decays at ~0.4 dB/ms, so a cut that low moves ~2 ms per dB. The same gun read 3027 ms
   and 3041 ms on two runs. At 10 dB down it sits near the step.
2. **One averaged excess was the wrong model.** The phone rings 9–12 ms past its gun and the watch
   barely at all, so the reading subtracts the excess of whichever device sounded last. When the
   leader cannot be named, it subtracts the mean and says how far out that can be.
3. **A leader that rings past the other gun ends the recording itself.** The skew is then only
   bounded. The analyser refuses to name a leader whose own ring could be what ended it.
4. **The room is not quiet in the gun's band.** Speech or a television read up to −42 dBFS at scattered
   seconds. A long run of it merged across gaps read as a second "gun" 38 dB under the real one. A gun
   must now be within 20 dB of the loudest thing recorded, gaps are bridged only up to 20 ms (the
   flicker of a decaying two-tone ring), and any sound within 300 ms of a gun marks it **not clean**.
   B1 is that case: another app's alarm, flagged, and not counted.

**Resolution.** 1 ms windows. With the phone leading every time, the watch's end closed each reading
and its excess is 0 ms, so a reading's error is the difference between the two onsets' rise at the
cut: about ±3 ms. No reading above needed the unnamed-leader fallback.

**Proven before use.** `python harness/pair-gun/analyze.py --selftest` runs 14 synthetic recordings
whose answers are fixed in the file. They cover known skews, equal levels, both deepest cancellations,
a ringing leader, a noise blip and a 150 ms negative control that must read over budget, plus a
placement the calibration must refuse. It went through three mutation rounds as the analyser changed:
5, 7 and 10 mutations, every red count predicted first. Where a count missed its prediction, the cause
was found:
- a deep-cancellation case is guarded twice, by the fixed cut and by the plateau rule;
- a 10 ms ringing lead is refused by the lead-stretch check as well as by the ring rule;
- the merge gap turned out to be load-bearing, which its comment had denied, and the comment is
  corrected;
- three counts came in one over, from a noise-blip case added after those predictions were written.

The capture is `harness/pair-gun/capture.sh`: the mic for a set time, both devices' pair and cue
logs, and a bracketed wall-clock offset per device to find a gun in the logs, never to time one.

## The controls, the setup and the drop

Observed on the release builds, alongside the guns above.

**The pair finds itself, and the offset is real (#219).** Each pre-start row reported the other:
*Watch −71:57:38.198 ±25 ms* on the phone and *Phone +71:57:38.202 ±22 ms* on the watch. Both lie
inside `/proc/uptime` read on both devices over adb, each read bracketed by the host clock:
−71:57:38.273 to −38.080. Rounds ran at 55–172 ms round trips.

**The pre-start setup mirrors both ways (D8, #221).** The phone's pick of Custom 1:00 appeared on the
watch's pre-start screen, taken from the peer. The watch's picks of *Club 3-2-1-Go* and then
*Scholastic - Race Manager* each appeared on the phone's pre-start screen, 8 s apart, one per pick.
A pick made while the phone still showed a gun's *GO!* was refused, as the protocol says (*peer setup
not taken: a race is on screen here*). Leaving *GO!* made the phone send its own pick, and the watch
answered with its later one, taken 0.5 s after.

**Sync on either device (#221).** A Sync taken on the watch at 2:14 reached the phone about 0.5 s
later (±0.2 s, from each device's log placed on the laptop's clock through a bracketed offset). Both snapped to 2:00 and counted down together, and the gun that followed read 55 ms, the same
as an unsynced race.

**End Race on either device (#221).** Taken on the phone (B1), both froze at 2:01. Taken on the watch
(B2), both froze at 0:08. The phone's service leaves the foreground at RACE_ENDED. The watch's keeps
its notification until *Done*, which is the watch's own RACE_ENDED behaviour (`TimerService`) however
the race ended. Its partial wake lock was released.

**A dropped link says so, and both reach their own gun (#222).** The phone's airplane mode **did not
drop the link**: this phone kept Bluetooth on through it (`bluetooth_on=1` read back), so Bluetooth
was switched off on its own (`cmd bluetooth_manager disable`). The phone logged *link lost mid-race*
about 5.0 s later and the watch 0.8 s after it, on the laptop's clock, as #219 measured (5.0 and
5.8 s). The lines appeared without giving up the countdown: *Watch out of
range — counting alone* on the phone's notice line, and *Phone out of range — counting alone* on the
watch's amber Tier 3 plate. Both fired their gun on the offset they held, 94 ms apart. When the radios
came back after the gun, the watch refused a *check* that reached it through the cloud (*not the
nearby peer*). A second later it had the phone nearby and sent its own; past the gun, nothing moved.

**Another app's alarm (B1).** The phone's Clock alarm held transient alarm focus from 11:15:01.6 to
11:15:25.0, across the phone's gun at 11:15:20.55. Every cue on both devices still dispatched on
schedule and delivered in full. Whether the gun was *audible* over the alarm is not established: the
mic heard both, and the analyser flagged the gun rather than time it.

## Found, not fixed

- **A setup taken while the phone shows its sequence picker is recorded and shown nowhere.** *Back*
  from *GO!* lands on the picker, which marks no current choice, so a watch pick taken there
  (`peer setup taken`) is invisible until the officer picks again. The protocol promises only that a
  pre-start screen shows a setup, so this is not a breach. It is the phone's picker, not the link.
  Filed as [#336](https://github.com/SailorDave17/race-timer/issues/336).

## Limits

- **One pair, one session, indoors, both devices on power with their screens on.** A console phone in
  sun and a watch on a moving wrist are not this.
- **Sound, not clocks.** Each reading includes the two speakers' output-latency difference. A
  clock-only figure at the gun needs a gun time logged on each device and a proven offset; neither
  build logs one.
- **Four clean guns.** The worst case, 94 ms, is the worst of four, not a tail. It is 6 ms inside the
  budget.
- **Not run here:** the watch's DND and doze states (#223's fourth criterion), and D6 on hardware. The
  link came back after the gun, so no correction or flag had anything to act on. Both are
  [#335](https://github.com/SailorDave17/race-timer/issues/335)'s.
- **Room audio stays out of the repo**, as [`phone-cue-delivery.md`](phone-cue-delivery.md) says. Only
  the readings are recorded here.

## Running it again

1. Build and install release APKs of the commit under test on both devices, and confirm each by hash
   and by `apksigner`, never by `keytool` on an APK ([`release-signing.md`](release-signing.md)).
2. Arm the cue log on both: `adb -s <serial> shell setprop log.tag.ToneManager DEBUG`.
3. Place the watch close to the mic and the phone farther, then calibrate. Take one 1-minute race per
   device with the other app force-stopped, captured by `capture.sh`. Then run
   `python harness/pair-gun/analyze.py --calibrate phone=<wav>,watch=<wav>`. It refuses a placement
   under 6 dB apart, or a lone gun with anything else nearby.
4. For each pair race, `PHONE=<serial> WATCH=<serial> sh harness/pair-gun/capture.sh <dir> <seconds>`,
   then `analyze.py <dir>/mic.wav` with the `--levels` and `--excess` the calibration printed. Count a
   reading only when it names a leader. That is the proof both devices were heard. A *not clean*
   reading is void.
5. Keep the room quiet for about 5 s either side of each gun, and restore each device to the state
   recorded before the run.
