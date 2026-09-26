# Pair Skew — How Closely the Phone and the Watch Can Agree About One Gun

What the Wearable Data Layer lets two devices know about each other's clocks, measured on the owner's
phone and watch before any production pair code exists. It is the evidence for epic
[#196](https://github.com/SailorDave17/race-timer/issues/196)'s decision **D2**, the skew budget for
"wrist and console never disagree", and for **D6**, the bound on correcting a running gun after a
reconnect.

Answers [#218](https://github.com/SailorDave17/race-timer/issues/218). The harness that took the
measurements, and how to run it again, is in [`harness/pair-skew/`](../harness/pair-skew/README.md). The
raw capture and the analysis report are in
[`harness/pair-skew/captures/2026-09-25/`](../harness/pair-skew/captures/2026-09-25/).

**Status:** one pair, one 34-minute session on 2026-09-25. The numbers are a sample, not a guarantee;
*Limits* at the end says what this instrument cannot see. **No budget is chosen here.** D2 and D6 are
the owner's to ratify, and this file recommends; it does not decide.

## The short answer

**Over Bluetooth, 100 ms is a budget the pair can meet; over the cloud, no useful budget is.**

- A single offset exchange over the Data Layer is **slow**. The round trip has a median of 144–198 ms,
  so one round alone bounds the offset to about ±75–100 ms. **Never translate a gun from one round.**
- **Intersecting rounds pays.** A burst of five back-to-back rounds bounds the offset to 39 ms at the
  median and 74 ms at p90. Keeping every round of the last minute gets the median to 26 ms.
- **A 100 ms budget fits 97% of Bluetooth bursts**, and still 96% for a gun five minutes after the burst
  when the stated drift is 30 ppm. The four that miss were all taken in the three slow minutes after
  Bluetooth came back from a drop, and #217's arithmetic reports those as out of budget.
- **The two clocks drift apart at 10 ppm** (the watch slow), so a held offset ages by well under a
  millisecond a minute. Across a 76-second link drop, the gun the watch held was **12 ms** from where a
  fresh exchange put it afterwards.
- **The cloud path is not a clock link.** With Bluetooth off, the Data Layer reached the other device
  through the cloud. The round trip had a median of 820 ms, the tail ran to 52 s, and **no burst met
  even 200 ms**. For the gun, "peer not nearby" has to mean "link lost".

**Recommendation: ratify D2 at 100 ms, and D6's correction bound at the same 100 ms**, provided the
production link (#219, #220) meets three conditions:

1. **Exchange a burst when a start is armed, and keep exchanging until the gun.** Pass every round to
   `translateGun`: it intersects them, so more rounds only tighten the bound.
2. **State about 30 ppm**, three times the measured rate, and treat that as provisional until a second
   phone and watch have been measured.
3. **Treat a peer that is not nearby as no peer** for gun purposes: count on the held offset and say so
   (#222). Never translate a gun over the cloud.

## The options

The share of Bluetooth bursts, 140 of them, whose bound fits each candidate budget. Each bound is
translateGun over one burst's five rounds, for a gun at the burst's end or a minute or five later, with
a stated drift of 30 ppm. At the harness's deliberately wide 100 ppm the five-minute column drops,
because aging is linear in the stated rate: 100 ms fits 88% at five minutes rather than 96%.

| D2 option | at the burst | gun 1 min later | gun 5 min later | What it asks of the link |
|---|---|---|---|---|
| 50 ms | 71% | 69% | 55% | Continuous exchange right up to the gun, and an officer who often sees "out of budget" |
| 75 ms | 91% | 90% | 87% | A burst at arming, and a fresh one before a long countdown |
| **100 ms** (the epic's candidate) | **97%** | **97%** | **96%** | **A burst at arming, and rounds kept until the gun** |
| 150 ms | 98% | 98% | 98% | Nothing more. It admits one more burst in 140 than 100 ms does |

For scale, and as arithmetic rather than a claim about racing: a boat at 6 knots covers 0.31 m in
100 ms and 0.15 m in 50 ms.

**Why not 50 ms?** The link can reach it, since keeping a minute of rounds gives a median bound of
26 ms. But 29% of bursts miss it at the burst, and a gun five minutes past the last burst misses 45% of
the time. Each miss would reach the officer as an out-of-budget result (#217), mostly at starts where
the clocks very likely agreed anyway: a single round's typical disagreement is 28 ms (below).

**Why not 150 ms?** It buys almost nothing. Four bursts miss 100 ms, and three of them miss 150 ms too.
All four come from the slow minutes after a reconnect described below, with bounds of 123, 247 and
254 ms, and 6.3 s.

**D6, the reconnect correction.** The measured drift is 10 ppm, so a held offset ages about 0.6 ms a
minute: it would take nearly three hours to drift 100 ms. The two guns measured here moved 2 ms and
12 ms against a fresh exchange. So a correction bound equal to the budget, 100 ms, auto-corrects every
disagreement drift can produce. A larger disagreement after a reconnect signals something else, such as
a reboot or a clock step, and that is exactly the case D6 sends to a notice rather than applying.

## What was measured

774 completed rounds over 33.8 minutes, on:

| | Phone | Watch |
|---|---|---|
| Model | Samsung SM-S918U | Samsung SM-R925U (Galaxy Watch 5 Pro) |
| Android | SDK 36 | SDK 36 (Wear OS) |
| Build | harness `phone`, debug, sha256 `556f471e…` | harness `wear`, debug, sha256 `b445fd57…` |
| State | on USB power, screen on | on its wireless charger, screen on |

Both are sideloaded debug builds of the scratch applicationId `io.github.sailordave17.racetimer.pairskew`,
signed with the same debug key and installed beside the release app. The installed APKs were confirmed
by hash on each device.

The session ran in stages (times on the phone's clock, 2026-09-25):

| Stage | When | What |
|---|---|---|
| Phone asks, Bluetooth | 17:18:36–17:28:13 | 260 rounds, 0 failures |
| Watch asks, Bluetooth | 17:28:20–17:52:34 | the rest of the run |
| Gun `phone-1`, no drop | armed 17:33:34, fired 17:36:34 | a control for the gun measurement |
| Gun `phone-2`, link drop | armed 17:40:53; Bluetooth off 17:42:38–17:43:06; fired 17:43:53 | the third criterion |
| Cloud | phone Bluetooth off 17:45:58–17:51:33 | the second transport state |

### Round trips

| Transport | Asked by | Primitive | Rounds | RTT min | median | p90 | max | Single-round bound, median |
|---|---|---|---|---|---|---|---|---|
| Bluetooth | phone | message pair | 130 | 83 ms | 198 ms | 440 ms | 1.1 s | 102 ms |
| Bluetooth | phone | request/reply | 130 | 79 ms | 190 ms | 328 ms | 0.9 s | 97 ms |
| Bluetooth | watch | message pair | 221 | 40 ms | 154 ms | 651 ms | 26 s | 78 ms |
| Bluetooth | watch | request/reply | 221 | 37 ms | 144 ms | 618 ms | 31 s | 73 ms |
| Cloud | watch | message pair | 36 | 500 ms | 816 ms | 29 s | 52 s | 410 ms |
| Cloud | watch | request/reply | 36 | 578 ms | 820 ms | 29 s | 50 s | 411 ms |

Three things in that table are worth carrying into #219:

- **The same link is faster when the watch asks.** The median is 144–154 ms against 190–198 ms, and
  the minimum is 37 ms against 79 ms. The cause is not established. #219 should measure its own
  exchanges both ways rather than assume which side ought to ask.
- **The two primitives are the same.** `sendMessage` both ways and `sendRequest`/`RpcService` are
  within about 10 ms of each other at the median, in both directions. Either will do.
- **A cold link and a reconnecting one are slow.** The first round of the session took 1.1 s. After
  Bluetooth came back at 17:43:06, requests arrived within 100 ms but **replies stalled for about 30 s**,
  which is where the 26 s and 31 s maxima come from. The link then stayed slow for about three
  minutes, with a per-minute median round trip near 500 ms, and all four Bluetooth bursts over 100 ms
  fall in that window. translateGun turns a slow round into a wide bound, so it can never produce a
  falsely tight gun; it only delays a good one.

### How far a single round is from the truth

Each round's own RTT-halving estimate, judged against the intersection of every other round within
60 s: the median disagreement over Bluetooth is **28 ms**, against a single-round bound of 73–102 ms.
Across all 774 rounds, no round's interval failed to overlap its neighbours', at a stated 30 or
100 ppm. That is the check that the stamps are sound, and that the drift never outran the stated rate.

This measures scatter, not truth. A delay that is always longer in one direction than the other shifts
every estimate by the same amount, and nothing that exchanges messages over the link can see that. Only
the bound covers it, which is why the budget is judged against the bound.

### Drift

Fitted to the 176 tightest Bluetooth rounds over 33.4 minutes: **−10.1 ppm, ±4.9 at two standard
errors**. The watch's clock runs slow against the phone's. #217 asked for this number: the rate
production states should carry headroom above it, and 30 ppm is three times it.

### The link drop

At 17:42:38 the phone's Bluetooth went off, 105 s after gun `phone-2` was armed and adopted. Both
devices lost each other within 8 s and found each other again through the cloud within 9 s. The
watch's questions then reached the phone, but no reply came back before Bluetooth returned at 17:43:06.
After that, replies stalled for about 30 s more (see above).

**The gun fired on both devices at 17:43:53, 76 s after the last completed exchange**: 0 ms late on the
phone's clock and 2 ms late on the watch's. Through the gap, each device logged the offset it held
every five seconds, with no new sample:

| | samples held | offset held | bound held |
|---|---|---|---|
| Phone | 640 throughout | unchanged | 27 → 31 ms (aging at the stated rate) |
| Watch | 640 throughout | unchanged | 27 → 32 ms |

Once rounds resumed, a fresh exchange put the watch's gun **12 ms** from where it had held it, with the
fresh estimate good to ±30 ms. The control gun, `phone-1`, fired with no drop and was 2 ms from fresh
(±19). Both are well inside the bounds the watch adopted them with (±43 and ±36 ms).

### The cloud

With the phone's Bluetooth off for 5 min 35 s, the first round completed 62 s in. In all, 72 rounds
completed through the cloud (18 of them after their 5 s timeout). 4 never came back, besides 5 asked
while the devices were still finding each other. Over the same path, bursts had a median bound of
313 ms and a p90 of 10 s, and **none of 15 fitted 200 ms**. Only the watch asked during this stage, so
the phone asking over the cloud is not measured. The one hint about it is the short drop above: there,
the phone's replies to the watch never arrived, and phone-to-watch is the leg a phone-asked round
starts with.

## Limits

What this instrument cannot see, so that none of it is read as covered:

- **One pair, one afternoon.** Both devices were on power with their screens on, a metre apart
  indoors, with no doze and no screen-off. A console phone in the sun and a watch on a moving wrist
  are not this. The five-minute column is also the one most exposed to a warmer or colder crystal.
- **Clock agreement only, not the sound.** Each device's own cue latency adds on top of the clock skew:
  the watch's audible cue is at most 61 ms late against its schedule (#82). What the officer's ear hears
  between wrist and console is this budget plus the difference between the two devices' output
  latencies. [#223](https://github.com/SailorDave17/race-timer/issues/223) measures the real thing at the
  gun, on release builds.
- **A one-sided delay is bounded, not measured** (above). The skew numbers at the two guns are
  differences between two translations, each good to its stated bound. They are not observations of
  two sounds.
- **The cloud stage asked in one direction only**, and *Wi-Fi* here means the Data Layer's cloud relay,
  as reported by `Node.isNearby()`. No direct Wi-Fi link was seen or tested.
- **Debug builds, sideloaded.** A Play-installed build is signed with Google's key, so a Play-installed
  watch and a sideloaded phone cannot pair at all. The epic records this. It does not change the
  physics, but #223's release-build run is the one that counts.
