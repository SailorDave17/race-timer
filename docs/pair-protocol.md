# Pair Protocol — One Start, One Race, on Either Device

What crosses the Wearable Data Layer between the phone app and the watch app, and the rules each
device applies to it. The link that measures the two clocks is
[#219](https://github.com/SailorDave17/race-timer/issues/219); a start that runs the same race on
both is [#220](https://github.com/SailorDave17/race-timer/issues/220). Both belong to epic
[#196](https://github.com/SailorDave17/race-timer/issues/196), whose decision registry the D-numbers
below refer to.

The code is the authority and this file points at it: the protocol is `PairLink`
(`shared/.../PairLink.kt`) and the start rule is `PairStarts` and `PairRaceBook`
(`shared/.../PairRace.kt`), all in `:shared`, where the JVM tests drive two simulated devices against
each other. The Android glue is one copy in `:shared-android` (`WearablePairLink`, `PairRaces`).

## The budget the hardware story measures

**D2, the skew budget for "wrist and console never disagree": 100 ms** — `PAIR_SKEW_BUDGET_MS`.
Ratified by the owner on 2026-09-25 at #218's gate, from the harness run written up in
[`pair-skew.md`](pair-skew.md). **This is the number
[#223](https://github.com/SailorDave17/race-timer/issues/223) measures against** on release builds:
the worst-case disagreement about one gun between the two devices, judged against the bound each
join reports rather than against a median.

It is a budget on **clock agreement**, not on sound. Each device's own cue output latency adds on
top, so what an ear hears between wrist and console is this budget plus the difference between the
two devices' output latencies (`pair-skew.md`, *Limits*).

D2 was ratified on three conditions for the production link. Where each one lives:

| Condition | Where it is met |
|---|---|
| A burst when a start is armed, and rounds kept coming until the gun | `PairLink.holdUntil`, called with the gun on both devices the moment a race starts or is joined (#220). A device holding for a gun asks with its screen off |
| State about 30 ppm, provisional until a second pair is measured | `PAIR_STATED_DRIFT_PPM`, passed to every translation (#219) |
| A peer that is not nearby is no peer for the gun | `PairLink` asks, answers, and sends or takes a start only for a peer whose `Node.isNearby()` is true (#219, #220) |

## The messages

One line of UTF-8 text each, a version tag first: `rtpair1 <kind> <fields…>`. A device that reads a
tag or a kind it does not know drops the line, so two app versions that disagree about the format
have no link rather than a wrong one. The version tag did not move for #220: an app from before it
reads `start` as a kind it does not know and drops it.

| Kind | Fields | Sent when |
|---|---|---|
| `ping` | round id | A device asks for a round (#219) |
| `pong` | round id, when the ping arrived, when the reply left — on the answering clock | The answer |
| `sample` | the four stamps of a completed round, as the asker holds it | So the answering device keeps the round too, and both estimates rest on the same rounds |
| `start` | the stamp (the tap, on the phone's clock), the race id, when it left and the gun (both on the sender's clock), and the sequence id | A race was started, or kept after a conflict and sent again (#220) |

Every field is a number except a start's sequence id. That id is the string a saved race already
carries (`custom_8m`, `scholastic_race_manager_alert60s`), and `BuiltInSequences.resolve` rebuilds
Custom lengths and lead-ins from it. It is checked against `[A-Za-z0-9_]{1,64}` at both ends.

## A start

1. **The device where Start is tapped runs its race first.** The first cue sounds, the race is
   persisted, the wake lock and the foreground are taken, and only then is the start sent. A device
   with no watch or no phone sends nothing, and the race it runs is exactly the race it ran before
   the pair existed (#220 AC 3).
2. **The start carries the gun on the sender's clock.** The receiver moves it onto its own
   `elapsedRealtime` through the rounds it holds (`translateGun`, with the sender as the exchange's
   responder), so each device anchors the one gun to its own clock (#220 AC 1).
3. **The receiver runs its own full engine to that gun** (`TimerEngine.join`). Nothing is ticked
   remotely, so a link lost after the join changes nothing about either count, by construction
   (#220 AC 2). A joined race is armed exactly as a tapped one is — the same service path, the same
   #62 ordering, persisted, held awake — so it survives the screen, Doze and a process death.
4. **The warning the start's own trip made late still sounds.** A start takes tens to hundreds of
   milliseconds to cross, and the first cue of every sequence is due the instant the sender anchored
   its gun. A device joining from idle sounds a cue that came due within
   `JOIN_LATE_CUE_GRACE_MS` (500 ms, the p90 one-way leg #218 measured) and none older, because a
   signal seconds late is a wrong signal. A device **switching** from a race of its own gets no
   grace: it has already sounded that race's first cue, and would otherwise sound it twice.
5. **A start is not joined** when its gun has already passed (a race-manager sequence joins its
   count-up instead), or when its sequence id is one this app cannot resolve.

The receiving app has to be **open**. A Data Layer message cannot start a foreground service from
the background on Android 12 and later, and the link lives in the app's process. Both pre-start
screens hold the display on, which is how the pair is used. A start that reaches a device with the
app closed is not joined, and the sender is not told; showing the link's state is
[#222](https://github.com/SailorDave17/race-timer/issues/222)'s.

## When the gun cannot be vouched for (#220 AC 5)

The receiver **joins anyway, and says so until the officer taps Sync** — the owner's rule at #220's
pickup, 2026-09-28. A clock that is counting and says it is unsure beats one that is not counting.

| The translation | The gun joined | The standing line (`pairJoinNotice`) |
|---|---|---|
| Within the budget | the translated gun | none |
| Outside the budget | the best-effort gun, the midpoint of what the rounds allow | *Phone start ±180 ms — tap Sync to confirm* |
| No rounds at all | anchored to the start's arrival: the latest the gun can be, late by the message's own trip | *Phone start unmeasured — tap Sync to confirm* |

On the watch it is the degraded-restore prompt's Tier 3 line, for the same reason in the same words'
shape ([`message-surface.md`](message-surface.md)); on the phone it is the notice line under the
sequence name. Sync against the next flag clears it, and so does the race ending.

## Which start wins (#220 AC 4)

**The start tapped last wins** — the owner's rule at pickup. It covers two starts tapped at nearly
the same moment, and a restart on one device while the other still runs the old race.

Each start carries a **key**: a stamp, then a random race id to break a tie. The stamp is **the
instant of the tap on the console's clock** — the phone's `elapsedRealtime`. The phone reads its own
clock; the watch moves its tap onto the phone's through the offset the link measured (`peerOffsetMs`).
It is then **raised past every start that device has already seen**, so a start made after seeing
another always outranks it, and so does a start from a watch with no rounds to place its tap by. Two
starts that cross in flight are ordered as they were tapped, to within the link's bound.

**Both devices compare the same two keys, carried in the messages, so they always reach the same
answer.** That is why the device that tapped fixes the stamp, rather than each device placing the
other's tap on its own clock: two translations carry two error bounds, and two taps closer together
than those bounds would be ordered one way on the wrist and the other on the console. Two devices
that disagree about the winner each switch to the other's race and never converge.

**Not the wall clock, measured.** The stamp was the tap's time of day until the rule was tried on
the owner's pair. With automatic time on both, the SM-R925U's wall clock ran **3.2–3.4 s behind the
SM-S918U's**. Two taps can cross only inside one message's trip, about 0.1–0.3 s, so a skew ten times
that decided every crossing: the phone won each one, and its panel said so — *Watch started too,
0.1 s later — both following the phone*.

**The one case the console's clock cannot order: the phone rebooting mid-session.** Its
`elapsedRealtime` restarts from zero, and a watch that saw starts from before the reboot keeps raising
its stamps past them. Until the watch app restarts, the watch's starts outrank the phone's. The pair
still converges, and the phone still offers the other start.

| This device | The peer's start | Does |
|---|---|---|
| No race running | any | Joins it |
| Running a race | outranks this device's | Switches to it, with no late-cue grace |
| Running a race | is outranked by this device's | Keeps its own, and **sends it again** |
| — | a race id already known, under the same or an older key | Nothing: a copy |

Sending the winner again is what makes the pair converge even when one of the two crossing starts is
lost in transit. A race restored after a process death carries no key, and yields to any start the
peer sends.

### The choice on the phone

**Whenever a race started on the phone and a race started on the watch collide, the phone says so
and offers both** — the second half of the owner's rule. The line names who started too, how far
apart, and which start both devices now follow (*Watch started too, 0.6 s later — both following the
watch*), over two equal halves, **Sync to phone** and **Sync to watch**. Choosing the start already
followed changes nothing. Choosing the other re-sends it under a new key with the same race id, so
it outranks the start that beat it, and both devices move to it. A device that already knows a race
by its id keeps its own gun for it: exact for the one it started, rather than a second translation of
its own gun back across the link.

The choice is up only while the countdown runs. It is never offered on the watch, and never for two
starts that were both tapped on the watch: then the phone was following the watch all along.

## What each device keeps

No wall-clock time crosses the link: every number a start carries is a reading of a device's clock
since boot, or a random id.

The keys and race ids live in memory (`PairRaceBook`, the most recent 64), for the process's life. A
joined race is saved as any race is — its sequence and its gun on this device's clock — so it is
restored after a process death like one started here, without its key.

## What the pair does not do yet

- **Sync and End Race are local.** Mirroring them, and pre-start setup (D8), is
  [#221](https://github.com/SailorDave17/race-timer/issues/221).
- **Stop is mirrored by no story.** A Stop on one device leaves the other counting. A Start tapped
  after it takes the other device over, as the rule above says, so a general recall restarts both.
- **A disagreement found after a reconnect is not corrected.** That is D6, ratified at 100 ms, and
  [#222](https://github.com/SailorDave17/race-timer/issues/222)'s.
- **Nothing here is measured on hardware.** [#223](https://github.com/SailorDave17/race-timer/issues/223)
  measures the skew at the gun against D2 on release builds.

## Where it is tested

`PairRaceTest` drives two simulated devices — each with its own clock, link, book and engine —
through every rule above and judges each by the physical instant both guns fire. `PairLinkTest`
covers the wire and the nearby rule; `TimerEngineTest` covers `join`; each app's `PairJoinTest` covers
the service arm; `FollowRemoteStartTest` and `TimerScreenPairChoiceTest` cover the phone's screen.
