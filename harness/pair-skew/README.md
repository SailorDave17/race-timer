# PairSkew: the #218 skew harness

A throwaway phone app and watch app that exchange clock offsets over the Wearable Data Layer and log
every exchange, plus a JVM tool that turns those logs into numbers. It exists to measure how closely
the phone's and the watch's clocks can be made to agree about one gun, before any production pair
code is written ([#218](https://github.com/SailorDave17/race-timer/issues/218), epic
[#196](https://github.com/SailorDave17/race-timer/issues/196), decision D2). What was measured, and
what it means for the budget, is in [`docs/pair-skew.md`](../../docs/pair-skew.md).

**This is a build of its own.** The product build and CI never see it, nothing here ships, and deleting
this directory removes it completely. It is also the only place in the repo that depends on
`play-services-wearable`. Putting that dependency into the shipped modules is #219's job, together with
the manifest and Play-declaration checks that go with it.

## What it runs

| Module | What it is |
|---|---|
| `protocol` | Pure JVM. The one line format, used for logs, payloads and the analysis. The phone frame every round is read in. **`:shared`'s sources compiled in place**, so `translateGun` here is the product's own. |
| `common` | Android library. The exchange engine, the Data Layer glue, the log and one screen. Both apps run it unchanged. |
| `phone`, `wear` | Shells around `common` that differ only in their manifests. Both carry the scratch applicationId `io.github.sailordave17.racetimer.pairskew`, so each installs beside the release app, never over it. The Data Layer connects only apps with the same applicationId **and** the same signing certificate, so build both on one machine. |
| `analysis` | JVM. Reads captures and writes the report the write-up is built from. |

**A round** is four `elapsedRealtimeNanos` stamps. The requester sends at t1, the responder reads t2 on
arrival and t3 on replying, and the requester reads t4 when the reply lands. Rounds go in bursts of
five, back to back, every ten seconds. They alternate between a message pair (`sendMessage` both ways)
and a request with its reply (`sendRequest` / `RpcService`), so the data holds both a cold link and a
warm one, over both primitives. Each round records whether the peer was `isNearby` (Bluetooth) or
reached through the cloud. Either device can ask. The requester sends every completed round back, so
both devices hold the same samples.

**The frame.** Every round is stored with the phone as #217's requester clock and the watch as its
responder, whichever device actually asked (see `Round.phoneFrameMs`). θ is always the watch's clock
minus the phone's.

**The gun.** *Arm gun +3:00* puts a gun three minutes ahead on the arming device's clock and sends
it across. The other device translates it once, through `translateGun` at the harness's stated
100 ppm, and holds the result on its own clock. After every later round it logs where a fresh
translation would put the gun, without moving it. That shows a device keeping its last-known offset
through a dropped link, which is #218's third criterion.

## Build, install, run

From the repo root, with the root wrapper:

```
./gradlew -p harness/pair-skew :phone:assembleDebug :wear:assembleDebug
./gradlew -p harness/pair-skew :protocol:test --rerun :analysis:test --rerun
```

This build does not include the product's `build-logic`, so it builds in a checkout where the product
build cannot.

Install with `adb -s <serial> install <apk>`, pinning the serial. A watch on wireless debugging can
show up twice, once by address and once by mDNS name, and so can a phone that also has wireless
debugging on. Confirm what landed by hash:

```
adb -s <serial> shell sha256sum $(adb -s <serial> shell pm path io.github.sailordave17.racetimer.pairskew | sed 's/package://')
```

Launch each app and keep both screens on for the whole run (the app holds the screen on itself). A
watch that falls back to its watch face stops answering. Capture each device live:

```
adb -s <serial> logcat -v threadtime -s PairSkew:I > <device>-logcat.txt
```

Each app also appends every line to `files/pairskew.log` in its own storage. That file survives an adb
link dropping mid-run. Pull it at the end (both builds are debuggable):

```
adb -s <serial> exec-out run-as io.github.sailordave17.racetimer.pairskew cat files/pairskew.log > <device>.log
```

## Analyse

```
./gradlew -p harness/pair-skew :analysis:run --args="[--ppm=N] <report.md> <capture> [<capture>...]"
```

Give it every capture you have, logcat and file alike. A line read from both is counted once, and a
round is one round whether it came from the requester's `ROUND` line or the responder's `SAMPLE` copy.
Paths are resolved from `analysis/`, so pass absolute paths. `--ppm` is the drift rate stated wherever a
bound is aged. It defaults to the 100 ppm the apps stated on the devices; a lower rate shows what a
production link stating it would get.

`captures/2026-09-25/` is the run behind [`docs/pair-skew.md`](../../docs/pair-skew.md): each device's
logcat capture, and the report at the apps' 100 ppm and at 30 ppm. Each app's own log file and a second
logcat stream held exactly the same lines as these (1747 on the phone, 1648 on the watch), so they are
not kept twice.

## The log format

`v1 KIND key=value ...`, one record per line. Every record carries `side` (the device that logged it)
and `at` (that device's `elapsedRealtimeNanos` when it logged).

| Kind | Logged when |
|---|---|
| `HELLO` | the app starts: maker, model, SDK, version, `run` (process start), stated ppm |
| `NODE`, `PEER` | its own Data Layer node, and the peer it chose, with `near` (1 = Bluetooth, 0 = cloud) |
| `LINK` | its own Bluetooth adapter changed state |
| `AUTO` | *Start rounds* / *Stop rounds* |
| `ROUND` | the requester finished a round: `req run id prim near burst seq t1 t2 t3 t4 rtt_us late` |
| `RESP` | the responder answered a round: its `t2` and `t3` |
| `SAMPLE` | the responder received the requester's completed round |
| `FAIL` | a round did not complete: `reason` is `timeout`, `no-peer`, `send-api<code>` or `rpc-api<code>` |
| `STATE` | every 5 s: samples held, the offset this side believes now and its bound aged to now, age of the last round, the held gun |
| `GUN` | `armed`, `sent`, `adopted`, `retranslated` (with `moved_ms`), `fired` (with `late_ms`) |
| `ERROR` | anything else that went wrong |

A round answered after its 5 s timeout is still a sound round, only a wide one. It is logged as
`ROUND … late=1` after its `FAIL … reason=timeout`, and the analysis counts it once, as a round.
