# Pair Protocol — One Race, One Clock, on Either Device

What crosses the Wearable Data Layer between the phone app and the watch app, and the rules each
device applies to it. The link that measures the two clocks is
[#219](https://github.com/SailorDave17/race-timer/issues/219); a start that runs the same race on
both is [#220](https://github.com/SailorDave17/race-timer/issues/220); Sync, End Race and the
pre-start setup taken on either device and applied on both are
[#221](https://github.com/SailorDave17/race-timer/issues/221); what a dropped link and its return
mean for a race is [#222](https://github.com/SailorDave17/race-timer/issues/222). All belong to epic
[#196](https://github.com/SailorDave17/race-timer/issues/196), whose decision registry the D-numbers
below refer to.

The code is the authority and this file points at it: the protocol is `PairLink`
(`shared/.../PairLink.kt`), the start and control rules are `PairStarts` and `PairRaceBook`
(`shared/.../PairRace.kt`), and the setup rule is `PairSetup` (`shared/.../PairSetup.kt`), all in
`:shared`, where the JVM tests drive two simulated devices against each other. The Android glue is one
copy in `:shared-android` (`WearablePairLink`, `PairRaces`), and each app's service carries a control
out as `PairRaces.RaceService`.

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
have no link rather than a wrong one. The version tag did not move for #220, #221 or #222: an app
from before any of them reads the new kind as one it does not know and drops it.

| Kind | Fields | Sent when |
|---|---|---|
| `ping` | round id | A device asks for a round (#219) |
| `pong` | round id, when the ping arrived, when the reply left — on the answering clock | The answer |
| `sample` | the four stamps of a completed round, as the asker holds it | So the answering device keeps the round too, and both estimates rest on the same rounds |
| `start` | the stamp (the tap, on the phone's clock), the race id, when it left and the gun (both on the sender's clock), and the sequence id | A race was started, or kept after a conflict and sent again (#220) |
| `sync` | the Sync's stamp (on the phone's clock) and a random id, the race id, when it left and the moved gun (both on the sender's clock) | A Sync was taken, or kept after two crossed and sent again (#221) |
| `end` | the race id, and the elapsed time the race was frozen at | End Race was taken, or an earlier end kept and sent back (#221) |
| `setup` | the pick's stamp (on the phone's clock) and a random id, the lead-in alert in seconds, and the sequence id without any lead-in | A pre-start pick was made, the peer came into range, a race ended, or the later pick is sent back (#221) |
| `check` | a `start`'s stamp and race id, the stamp and random id the gun was last set under (a Sync's, or the start's own), when it left and the gun (both on the sender's clock), 1 if the sender set that gun itself and 0 if it placed it through the link, and the sequence id | The peer came into range while a race is running here (#222) |

Every field is a number except the sequence id a `start`, a `setup` or a `check` ends with. That id is the
string a saved race already carries (`custom_8m`, `scholastic_race_manager_alert60s`), and
`BuiltInSequences.resolve` rebuilds Custom lengths and lead-ins from it. It is checked against
`[A-Za-z0-9_]{1,64}` at both ends, and a setup's alert against the values the lead-in picker can
offer. An `end`'s elapsed time is a duration, not a clock reading: it is the same number on both
clocks, and crosses untranslated.

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
app closed is not joined, and the sender is not told — though the next time the two meet with the
race still running, its check offers the start again ([below](#when-the-link-drops-and-when-it-comes-back-222)).

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
starts that were both tapped on the watch: then the phone was following the watch all along. A Sync
on either device takes it down (#221): the officer has just set the gun against the flag, which is
the question the choice was asking.

## Sync on either device (#221 AC 1)

**A Sync travels as the gun it produced, never as "sync now".** The device where Sync is tapped
snaps its own countdown as it always has (up to the minute inside 10 s, floored beyond, #150), and
only a snap it actually took is sent: one refused in a lead-in or by the double-tap guard moves
nothing and says nothing. The message carries the new gun on the sender's clock, and the receiver
moves it onto its own through the rounds it holds — `translateGun`, the start's translation, never a
wall clock. Each device snapping for itself would round two readings a message's trip apart, and
4:50.0 rounds up to 5:00 where 4:49.9 floors to 4:00: a minute between wrist and console.

The receiver moves its gun (`TimerEngine.moveGun`) and does everything around it that a Sync there
does: the cues still to come are re-aimed, and the ones it has already sounded stay sounded — the
same rule as its own Sync, so both devices sound the same cues after one Sync; the race is
persisted; **the wake lock is re-sized from what is left to run (#221 AC 4)**, because a moved gun
can be later than the lock was sized for, which is #126's defect reached by a new road; and the
Sync is felt and heard, with the label naming the device that took it — *Phone synced → 5:00* on the
watch, *Watch synced → 5:00* on the phone's notice line. The label reads the countdown the peer's
Sync set, as the peer read it when sending — the `sync` carries the gun and the sending instant on
the sender's clock — to the nearest second. It is not worked out from this device's countdown when
the move lands: that is a message's trip later, and on the owner's pair a Sync took about 0.6 s to
cross, which read *Watch synced → 0:59* for a Sync to 1:00 until #221's hardware run found it.

A gun that could not be placed inside D2's budget is flagged as a joined start's is, in a Sync's
words (*Phone sync ±180 ms — tap Sync to confirm*), and one placed inside it clears the flag.

**Two Syncs that cross settle on the later.** Each Sync carries a key made exactly as a start's is
— the instant on the phone's clock, raised past everything the device has seen, then a random id —
and each race remembers the key its gun was last set under (`PairRace.gunKey`). A device takes a
Sync only if it outranks that key; one that holds the later sends its own again, so the pair
converges even when one of the two is lost in transit.

## End Race on either device (#221 AC 2)

**End Race travels as the elapsed time it froze**, and the receiver freezes at that time rather than
at its own reading when the message lands (`TimerEngine.endRaceAt`). The final elapsed time is the
number the committee writes down, and a reading taken a message's trip later could floor to a
different second on the two screens. Each device winds down as its own End Race does: the phone
leaves the foreground and clears its saved race; the watch leaves its summary up until Done.

**Two End Race taps that cross settle on the earlier.** A device already ended keeps the earlier of
its own end and the peer's, and one that keeps its own sends it back, so the pair converges on one
time even when one of the two is lost.

## A control for a race not running here (#221 AC 3)

Every `sync` and `end` names its race. A device applies one only to **the race it names, and only
while that race is running here**; anything else is dropped and said — *Phone synced a race not
running here* as a Tier 1 banner on the watch, *Watch ended a race not running here* on the phone's
notice line for three seconds. It is never applied to whatever the device happens to be running.
That covers a control delayed past a link drop and a restart on this device, and one for a race
this device has since stopped: a device remembers the race it last started or joined after the race
is over, so a late control for it is recognised as its own rather than taken for another's.

A control reaches a device whose race is running whether or not its screen is on — unlike a start,
which needs the receiving app open: the race's foreground service keeps the process, and with it the
link, alive, and the control is carried out by that service directly rather than by starting one.

## The pre-start setup (#221 AC 5, epic decision D8)

What a pre-start screen is set to — **the sequence the next Start runs, and the alert the lead-in
picker opens on** — mirrors across the pair, so one device drives the other from the dock. A Custom
length is part of the sequence (`custom_8m`), so it mirrors as a selection does. A lead-in is not:
arming one starts the race on both apps, and the start mirrors it; what mirrors before a start is
the alert both pickers open on (the owner's reading of D8 at #221's pickup).

**The rule, decided by the owner at #221's pickup: the later pick wins, ordered like starts.**

- Every pick carries a key made exactly as a start's is: the instant **on the phone's clock**,
  raised past everything the device has seen, then a random id. The watch places its pick on the
  phone's clock through the offset its rounds hold — **held through a drop**, so a pick made on the
  watch out of range is still ordered by when it was made.
- **A setup nobody has touched loses to any that has been.** What an app opens on comes from its own
  memory and has no key in this process, as a restored race has none. **When neither has been
  touched, the phone's wins**: the tie is broken by which device is the console, so both reach it.
- **A race is a pick of its own sequence.** A race started here takes the start's key, since it is
  the latest thing the officer did; a race joined here keeps this device's key, so the device whose
  start it was wins the comparison afterwards. Either way the setup follows the race — its sequence
  without the lead-in, and a lead-in's alert — so after a race both screens are set to what was run.
- **A device with a race on screen takes no setup**: not a countdown, not a count-up, not a "GO!"
  still showing, not a frozen summary waiting for Done. Only its pre-start screen shows a setup.

**When the two are compared:** every pick is sent as it is made; both devices send theirs when the
peer comes into range (`PairLink`'s `onPeerNearby`); and a device sends its own when its race is over
and its pre-start screen is back. Both compare the same two keys, so they always reach the same
answer, and the device holding the later pick sends it back — which is also how a device that was
busy when the other picked catches up once its race is over.

A setup whose sequence this app cannot resolve is not taken, rather than shown as something else.

## When the link drops, and when it comes back (#222)

**A dropped link is information, never a dead clock.** Nothing either device counts reads the link
after its start (#220 AC 2), so a drop changes nothing about either count, at any phase — pre-start,
lead-in, countdown or count-up. What it changes is what the officer is told.

### Saying so

A race that had the peer in range — measured, or found and being measured — and has lost it is
**link-lost** (`PairStarts.linkLost`) for as long as the two are apart:

| | The line | Where |
|---|---|---|
| Watch | *Phone out of range — counting alone* | The Tier 3 plate, as `armedNotice`'s lowest line: under the prompts that ask for Sync and under the cue-volume warning. Countdown and count-up; never a finished screen |
| Phone | *Watch out of range — counting alone* | The notice line, lowest of what it can carry, while a race runs |

**Only a race that had the link is told it lost it.** A phone with no watch, or a watch reachable
only through the cloud from the start — at home on its charger — never had one, and says nothing:
phone-standalone is a hard requirement, and the absence of a pair is a normal state (#222 AC 3).
A race started while the two were apart has not lost a link either. The pre-start status row is
unchanged: it already says *out of range* before Start.

### Meeting again

When the peer comes back into range, **each device running a race sends a `check`**: the race it is
running, and its gun as it now stands, placed on the receiver's clock as a start's is. The rounds are
held across a drop (#219), so the gun is placed at once, without waiting out the reconnect stall.
A device running nothing sends nothing. The owner's rule at #222's pickup decides what the receiver
does:

| The receiver | Does |
|---|---|
| Running the same race | Judges the gun by D6 (below) |
| Ended that race while apart | Sends its End Race again, so the peer freezes at the same time (#221's earlier-end rule) |
| Stopped that race while apart | Nothing. Stop is not mirrored until [#328](https://github.com/SailorDave17/race-timer/issues/328), and a stopped race is never rejoined |
| Idle, or running another race | Takes it as a start (#220's rule): joins from idle; a running race switches if the check's start was tapped later, or keeps its own and sends it again; the phone offers the choice as #220 does |

### D6: the gun, corrected or flagged

**D6, the reconnect correction bound: 100 ms** — `PAIR_RECONNECT_BOUND_MS`, ratified with D2 on
2026-09-25 ([`pair-skew.md`](pair-skew.md)), the owner overriding *never move a running gun*
(`judgeGunCheck`).

- **Whose gun is followed:** the one set last, by the key it was set under — so a Sync taken on one
  device while the two were apart makes that device's the gun followed. Under the same key, the gun
  its own device set (a start or a Sync tapped there) rather than one placed through the link. The
  console breaks a tie nothing else can. Both devices hold both keys, so they agree which one judges,
  and only that one moves or says so.
- **Within 100 ms, measured through a translation inside D2's budget:** corrected, with nothing
  buzzed or beeped, and said — *Phone back — gun matched, 40 ms*, a Tier 1 banner on the watch and a
  three-second line on the phone. The owner's rule at #222's pickup: a dropped link never bends a
  running race silently, however little. The wake lock is re-sized and the race persisted, as a
  Sync's move does.
- **Beyond it:** never moved by itself. The device that follows owes the officer the standing line
  *Phone gun 8.0 s apart — tap Sync to confirm* (milliseconds under a second), on the joined start's
  plate, until a Sync settles it. A Sync on either device then moves both, as #221 does.
- **A gap the link cannot place inside D2's budget** is flagged only if even its bound cannot bring it
  inside 100 ms, and never corrected. With no rounds at all nothing is said.
- **Past the gun nothing moves**: a count-up has no countdown to correct and no Sync to ask for.

A gun a device set itself is its own start or Sync, exact on its own clock. A drift between two
held clocks is 10 ppm (#218), about 0.6 ms a minute, so the disagreement drift alone produces stays
far inside 100 ms; what a check finds beyond it is a Sync taken while apart, or a clock that moved.

## What each device keeps

No wall-clock time crosses the link: every number a start, a Sync, a pick or a check carries is a
reading of a device's clock since boot, a random id, or — for End Race — a duration; and a check's
last number is a single bit.

The keys and race ids live in memory (`PairRaceBook`, the most recent 64), for the process's life,
and so do the setup's key (`PairSetup`). A joined race is saved as any race is — its sequence and its
gun on this device's clock — so it is restored after a process death like one started here, without
its key; a moved gun is saved the same way, and so is one a reconnect corrected (#222). Whether a
race has lost its link is held in memory only (`PairStarts`). A setup taken from the peer is saved as a pick made here
is — the sequence and the lead-in alert, in the keys each app already had — without its key.

## What the pair does not do yet

- **Stop is mirrored by no story yet.** A Stop on one device leaves the other counting. A Start tapped
  after it takes the other device over, as the rule above says, so a general recall restarts both.
  [#328](https://github.com/SailorDave17/race-timer/issues/328) mirrors it under the control rule
  above.
- **A race restored after a process death takes no control from the peer, and sends none.** It has
  no key and no race id here, so a Sync or End Race naming the race it was is stale on this device,
  and one taken on it is not told. The next start on either device joins both again.
- **A single Sync lost in transit while both are in range is not sent again.** Only two that cross
  are resent, by the device holding the later. The two guns then differ by the Sync until the next
  one. A Sync taken while the two were *apart* is found when they meet (#222, above); one lost with
  both in range is not, since nothing sends a check without a drop.
- **A gun a suspend moved is not seen.** A device suspended between races can come back with its
  clock offset moved by a fraction of a second, and a start it joins before a fresh round lands on
  the stale rounds, reporting itself in budget. That is
  [#330](https://github.com/SailorDave17/race-timer/issues/330)'s. A running race holds a wake lock,
  so the rounds a check is placed through are held across a drop, not a suspend.
- **Nothing here is measured on hardware.** [#223](https://github.com/SailorDave17/race-timer/issues/223)
  measures the skew at the gun against D2 on release builds, observes the mirrored controls and
  setup on the owner's pair, and forces a drop mid-sequence (#222's lines and D6 on hardware).

## Where it is tested

`PairRaceTest` drives two simulated devices — each with its own clock, link, book, setup and engine
— through every rule above and judges each by the physical instant both guns fire; for #222 it drops
the link at every phase (pre-start, lead-in, countdown, count-up), and holds D6 at its bound with a
gap of exactly 100 ms corrected and 101 ms flagged, both signs, over a link with no error. `PairLinkTest`
covers the wire, the nearby rule and the held offset; `TimerEngineTest` covers `join`, `moveGun` and
`endRaceAt`; each app's `PairJoinTest` covers the service arm, and each app's `PairControlsTest` the
service carrying out the peer's Sync (the wake lock re-sized, #221 AC 4), End Race and setup, and a
reconnect's correction (not heard as a Sync) and flag (#222);
`FollowRemoteStartTest`, `TimerScreenPairChoiceTest` and `PairNewsOnScreenTest` cover the phone's
screen.
